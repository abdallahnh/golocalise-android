package com.golocalise.sdk

import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class GoldenFixture(
  val projectId: String,
  val environment: String,
  val namespace: String,
  val key: String,
  val englishA: String,
  val arabicA: String,
  val englishB: String,
  val arabicB: String,
)

private sealed interface Stub {
  data class Response(val value: OtaHttpResponse) : Stub
  data class Failure(val value: Exception) : Stub
}

private class QueueTransport : OtaTransport {
  private val queue = ArrayDeque<Stub>()
  val requests = mutableListOf<Pair<URL, Map<String, String>>>()
  fun enqueue(vararg values: Stub) { queue.addAll(values) }
  override suspend fun get(
    url: URL,
    headers: Map<String, String>,
    timeoutMillis: Int,
  ): OtaHttpResponse {
    requests += url to headers
    return when (val stub = queue.removeFirstOrNull() ?: throw IOException("offline")) {
      is Stub.Response -> stub.value
      is Stub.Failure -> throw stub.value
    }
  }
}

private class InterruptibleCache : PersistentCacheAdapter {
  private val values = mutableMapOf<String, ByteArray>()
  var failWrites = false
  override suspend fun read(key: String): ByteArray? = values[key]?.copyOf()
  override suspend fun writeAtomically(key: String, data: ByteArray) {
    if (failWrites) throw IOException("interrupted")
    values[key] = data.copyOf()
  }
}

private data class ReleaseResponses(
  val manifest: OtaHttpResponse,
  val artifact: OtaHttpResponse,
)

public class GoLocaliseClientTest {
  private val json = Json
  private val token = "gl_sdk_public_reference_token_abcdefghijklmnopqrstuvwxyz"
  private val fixture: GoldenFixture by lazy {
    val resource = requireNotNull(
      javaClass.classLoader?.getResource("fixtures/golden.json")
    ) { "Bundled OTA fixture not found" }

    json.decodeFromString(
      resource.readText(),
    )
  }

  @Test
  public fun canonicalizesRegionalLocaleVariants(): Unit = runTest {
    val client = client(QueueTransport(), locale = "AR_lb")
    assertEquals("ar-LB", client.currentLocale)
    assertEquals(RefreshResult.Unchanged(null), client.setLocale("ar_iq", refresh = false))
    assertEquals("ar-IQ", client.currentLocale)
  }

  @Test
  public fun initializeLookupFallbackAndLocaleSwitch(): Unit = runTest {
    val transport = QueueTransport()
    val english = release(1, "en", fixture.englishA)
    val arabic = release(1, "ar", fixture.arabicA)
    transport.enqueue(
      Stub.Response(english.manifest), Stub.Response(english.artifact),
      Stub.Response(arabic.manifest), Stub.Response(arabic.artifact),
    )
    val client = client(
      transport,
      bundled = MapBundledTranslations(mapOf("en" to mapOf("common" to mapOf("bundled" to "Bundled")))),
    )
    assertEquals(RefreshResult.Updated(1), client.initialize())
    assertEquals(fixture.englishA, client.translation(fixture.key, fixture.namespace))
    assertEquals(listOf(fixture.namespace), client.namespaces())
    assertEquals(listOf(fixture.key), client.keys(fixture.namespace))
    assertEquals(
      listOf(TranslationEntry(fixture.key, fixture.namespace, fixture.englishA)),
      client.translations(),
    )
    assertEquals("Bundled", client.translation("bundled", "common"))
    assertEquals("Fallback", client.translation("missing", fallback = "Fallback"))
    assertEquals("missing", client.translation("missing"))
    assertEquals(RefreshResult.Updated(1), client.setLocale("ar"))
    assertEquals(fixture.arabicA, client.translation(fixture.key, fixture.namespace))
    assertTrue(transport.requests.all { it.second["Authorization"]?.startsWith("Bearer gl_sdk_") == true })
  }

  @Test
  public fun safeFormattingSupportsAndroidAndCanonicalPlaceholders() {
    val bundled = MapBundledTranslations(mapOf("en" to mapOf("default" to mapOf(
      "android" to "%2\$s then %1\$s (100%%)",
      "canonical" to "{0} → {1}",
      "oneBased" to "Testing {1} and {2}",
      "malformed" to "Hello %d",
    ))))
    val client = client(QueueTransport(), bundled = bundled)
    assertEquals("second then first (100%)", client.translation("android", arguments = listOf("first", "second")))
    assertEquals("مرحبا → World", client.translation("canonical", arguments = listOf("مرحبا", "World")))
    assertEquals("Testing one and two", client.translation("oneBased", arguments = listOf("one", "two")))
    assertEquals("Hello %d", client.translation("malformed", arguments = listOf("World")))
    assertEquals("{0} → {1}", client.translation("canonical", arguments = listOf("only one")))
  }

  @Test
  public fun localeAwarePluralFormattingUsesOtaCache(): Unit = runTest {
    val plural = OtaPluralMessage(
      variable = "count",
      forms = mapOf(
        "zero" to "لا عناصر",
        "one" to "عنصر واحد",
        "two" to "عنصران",
        "few" to "{count} عناصر لـ {0}",
        "many" to "{count} عنصراً لـ {0}",
        "other" to "{count} عنصر لـ {0}",
      ),
    )
    val body = json.encodeToString(
      OtaArtifact(
        1,
        fixture.projectId,
        fixture.environment,
        1,
        "ar",
        mapOf(fixture.namespace to mapOf(fixture.key to plural.forms.getValue("other"))),
        mapOf(fixture.namespace to mapOf(fixture.key to plural)),
      ),
    ).encodeToByteArray()
    val response = releaseData(1, "ar", body)
    val transport = QueueTransport().apply {
      enqueue(Stub.Response(response.manifest), Stub.Response(response.artifact))
    }
    val client = client(transport, locale = "ar")
    assertEquals(RefreshResult.Updated(1), client.initialize())
    assertEquals(
      "7 عناصر لـ المتجر",
      client.translation(
        fixture.key,
        fixture.namespace,
        count = 7.0,
        arguments = listOf("المتجر"),
      ),
    )
    assertEquals("عنصران", client.translation(fixture.key, fixture.namespace, count = 2.0))
  }

  @Test
  public fun offlineTimeoutAndHttpFailuresPreserveLastKnownGood(): Unit = runTest {
    val cache = MemoryCacheAdapter()
    val online = QueueTransport()
    val first = release(1, "en", fixture.englishA)
    online.enqueue(Stub.Response(first.manifest), Stub.Response(first.artifact))
    assertEquals(RefreshResult.Updated(1), client(online, cache).initialize())

    for (failure in listOf<Stub>(
      Stub.Failure(IOException("offline")), Stub.Failure(IOException("timeout")),
      *listOf(401, 403, 404, 429, 500).map { Stub.Response(OtaHttpResponse(it)) }.toTypedArray(),
    )) {
      val transport = QueueTransport().apply { enqueue(failure) }
      val restarted = client(transport, cache)
      assertTrue(restarted.initialize() is RefreshResult.Failed)
      assertEquals(1, restarted.currentRelease)
      assertEquals(fixture.englishA, restarted.translation(fixture.key, fixture.namespace))
    }
  }

  @Test
  public fun malformedHashScopeAndMissingLocaleAreRejected(): Unit = runTest {
    val malformed = QueueTransport().apply {
      enqueue(Stub.Response(OtaHttpResponse(200, body = "{".encodeToByteArray())))
    }
    assertTrue(client(malformed).refresh() is RefreshResult.Failed)

    val unsupported = QueueTransport().apply {
      enqueue(Stub.Response(OtaHttpResponse(200, body = "{\"protocolVersion\":2}".encodeToByteArray())))
    }
    assertTrue(client(unsupported).refresh() is RefreshResult.Failed)

    for (kind in listOf("malformed", "hash", "scope")) {
      val transport = QueueTransport()
      val first = release(1, "en", fixture.englishA)
      val validBody = artifactData(2, "en", fixture.englishB)
      val body = when (kind) {
        "malformed" -> "{".encodeToByteArray()
        "scope" -> artifactData(2, "en", fixture.englishB, "other")
        else -> validBody.copyOf().also { bytes ->
          val index = bytes.indexOf('W'.code.toByte())
          bytes[index] = 'V'.code.toByte()
        }
      }
      val second = release(2, "en", fixture.englishB, if (kind == "hash") validBody else body)
      transport.enqueue(
        Stub.Response(first.manifest), Stub.Response(first.artifact),
        Stub.Response(second.manifest), Stub.Response(OtaHttpResponse(200, body = body)),
      )
      val client = client(transport)
      client.initialize()
      assertTrue(client.refresh() is RefreshResult.Failed)
      assertEquals(1, client.currentRelease)
    }

    val valid = release(1, "en", fixture.englishA)
    val decoded = json.decodeFromString<OtaManifest>(valid.manifest.body.decodeToString())
    for (invalid in listOf(
      decoded.copy(locales = emptyMap()),
      decoded.copy(projectId = "another-project"),
    )) {
      val transport = QueueTransport().apply {
        enqueue(Stub.Response(OtaHttpResponse(200, body = json.encodeToString(invalid).encodeToByteArray())))
      }
      assertTrue(client(transport).refresh() is RefreshResult.Failed)
    }
  }

  @Test
  public fun releaseAndEtagBehaviorMatchesProtocol(): Unit = runTest {
    val transport = QueueTransport()
    val first = release(1, "en", fixture.englishA)
    val second = release(2, "en", fixture.englishB)
    transport.enqueue(Stub.Response(first.manifest), Stub.Response(first.artifact))
    val client = client(transport)
    client.initialize()
    transport.enqueue(Stub.Response(OtaHttpResponse(304)))
    assertEquals(RefreshResult.Unchanged(1), client.refresh())
    transport.enqueue(Stub.Response(first.manifest))
    assertEquals(RefreshResult.Unchanged(1), client.refresh())
    transport.enqueue(Stub.Response(second.manifest), Stub.Response(second.artifact))
    assertEquals(RefreshResult.Updated(2), client.refresh())
    transport.enqueue(Stub.Response(first.manifest))
    assertEquals(RefreshResult.Unchanged(2), client.refresh())
    assertEquals(fixture.englishB, client.translation(fixture.key, fixture.namespace))
    assertTrue(transport.requests.any { it.second.containsKey("If-None-Match") })
  }

  @Test
  public fun concurrentRefreshesAreSerializedSafely(): Unit = runTest {
    val transport = QueueTransport()
    val first = release(1, "en", fixture.englishA)
    transport.enqueue(Stub.Response(first.manifest), Stub.Response(first.artifact))
    val client = client(transport)
    client.initialize()
    transport.enqueue(
      Stub.Response(OtaHttpResponse(304)),
      Stub.Response(OtaHttpResponse(304)),
    )
    val results = listOf(async { client.refresh() }, async { client.refresh() }).awaitAll()
    assertEquals(listOf(RefreshResult.Unchanged(1), RefreshResult.Unchanged(1)), results)
    assertEquals(fixture.englishA, client.translation(fixture.key, fixture.namespace))
  }

  @Test
  public fun interruptedAndAtomicFileWritesAreSafe(): Unit = runTest {
    val cache = InterruptibleCache()
    val transport = QueueTransport()
    val first = release(1, "en", fixture.englishA)
    val second = release(2, "en", fixture.englishB)
    transport.enqueue(Stub.Response(first.manifest), Stub.Response(first.artifact))
    val client = client(transport, cache)
    client.initialize()
    cache.failWrites = true
    transport.enqueue(Stub.Response(second.manifest), Stub.Response(second.artifact))
    assertTrue(client.refresh() is RefreshResult.Failed)
    assertEquals(1, client.currentRelease)
    cache.failWrites = false
    val restarted = client(QueueTransport(), cache)
    assertTrue(restarted.initialize() is RefreshResult.Failed)
    assertEquals(fixture.englishA, restarted.translation(fixture.key, fixture.namespace))

    val directory = createTempDirectory("golocalise-android-").toFile()
    try {
      val fileCache = FileCacheAdapter(directory)
      val value = fixture.arabicA.encodeToByteArray()
      fileCache.writeAtomically("ar", value)
      assertArrayEquals(value, fileCache.read("ar"))
    } finally { directory.deleteRecursively() }
  }

  @Test
  public fun httpsAndSameOriginAreRequired(): Unit = runTest {
    assertThrows(IllegalArgumentException::class.java) {
      configuration(QueueTransport(), baseUrl = URL("http://ota.example"))
    }
    val transport = QueueTransport()
    val first = release(1, "en", fixture.englishA, artifactUrl = "https://attacker.example/a.json")
    transport.enqueue(Stub.Response(first.manifest))
    assertTrue(client(transport).refresh() is RefreshResult.Failed)
    assertEquals(1, transport.requests.size)
  }

  private fun client(
    transport: OtaTransport,
    cache: PersistentCacheAdapter = MemoryCacheAdapter(),
    bundled: BundledTranslationAdapter? = null,
    locale: String = "en",
  ): GoLocaliseClient = GoLocaliseClient(configuration(transport, cache, bundled, locale = locale))

  private fun configuration(
    transport: OtaTransport,
    cache: PersistentCacheAdapter = MemoryCacheAdapter(),
    bundled: BundledTranslationAdapter? = null,
    baseUrl: URL = URL("https://api.example.com"),
    locale: String = "en",
  ): GoLocaliseConfiguration = GoLocaliseConfiguration(
    baseUrl, token, fixture.projectId, fixture.environment, locale,
    transport = transport, cache = cache, bundled = bundled,
  )

  private fun release(
    number: Int,
    locale: String,
    value: String,
    hashedBody: ByteArray = artifactData(number, locale, value),
    artifactUrl: String = "/artifacts/$number/$locale.json",
  ): ReleaseResponses {
    val body = artifactData(number, locale, value)
    val entry = ManifestLocale(hashedBody.hash(), artifactUrl, hashedBody.size)
    val manifest = OtaManifest(
      1, fixture.projectId, fixture.environment, number, "2026-01-01T00:00:00Z",
      mapOf(locale to entry),
    )
    return ReleaseResponses(
      OtaHttpResponse(200, mapOf("etag" to "\"release-$number\""), json.encodeToString(manifest).encodeToByteArray()),
      OtaHttpResponse(200, body = body),
    )
  }

  private fun releaseData(number: Int, locale: String, body: ByteArray): ReleaseResponses {
    val manifest = OtaManifest(
      1,
      fixture.projectId,
      fixture.environment,
      number,
      "2026-01-01T00:00:00Z",
      mapOf(locale to ManifestLocale(body.hash(), "/artifacts/$number/$locale.json", body.size)),
    )
    return ReleaseResponses(
      OtaHttpResponse(200, body = json.encodeToString(manifest).encodeToByteArray()),
      OtaHttpResponse(200, body = body),
    )
  }

  private fun artifactData(
    release: Int,
    locale: String,
    value: String,
    projectId: String = fixture.projectId,
  ): ByteArray = json.encodeToString(
    OtaArtifact(1, projectId, fixture.environment, release, locale, mapOf(fixture.namespace to mapOf(fixture.key to value))),
  ).encodeToByteArray()

  private fun ByteArray.hash(): String = "sha256:" + MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
}
