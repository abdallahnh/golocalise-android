package com.golocalise.sdk

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public data class GoLocaliseConfiguration(
  val baseUrl: URL,
  val token: String,
  val projectId: String,
  val environment: String,
  val locale: String,
  val timeoutMillis: Int = 5_000,
  val refreshOnInitialize: Boolean = true,
  val transport: OtaTransport = UrlConnectionTransport(),
  val cache: PersistentCacheAdapter = MemoryCacheAdapter(),
  val bundled: BundledTranslationAdapter? = null,
) {
  init {
    require(token.startsWith("gl_sdk_")) { "A public gl_sdk_ credential is required" }
    require(projectId.isNotBlank() && environment.isNotBlank() && locale.isNotBlank()) {
      "projectId, environment, and locale are required"
    }
    require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    val loopback = baseUrl.host in setOf("localhost", "127.0.0.1", "::1")
    require(baseUrl.protocol == "https" || (baseUrl.protocol == "http" && loopback)) {
      "baseUrl must use HTTPS except on localhost"
    }
  }
}

public class GoLocaliseClient(private val configuration: GoLocaliseConfiguration) {
  private val refreshMutex = Mutex()
  private val stateLock = Any()
  private val json = Json { ignoreUnknownKeys = false }
  private var activeLocale: String = canonicalLocale(configuration.locale)
  private var state: CacheEnvelope? = null

  public val currentLocale: String get() = synchronized(stateLock) { activeLocale }
  public val currentRelease: Int? get() = synchronized(stateLock) { state?.manifest?.release }

  public suspend fun initialize(): RefreshResult {
    loadCache(currentLocale)
    return if (configuration.refreshOnInitialize) refresh()
    else RefreshResult.Unchanged(currentRelease)
  }

  public suspend fun refresh(): RefreshResult = refreshMutex.withLock {
    val locale = currentLocale
    val previous = synchronized(stateLock) { state }
    try {
      val manifestResponse = configuration.transport.get(
        manifestUrl(locale), requestHeaders(previous?.manifestETag), configuration.timeoutMillis,
      )
      if (manifestResponse.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
        return@withLock RefreshResult.Unchanged(previous?.manifest?.release)
      }
      requireSuccess(manifestResponse, "manifest")
      val manifest = decode<OtaManifest>(manifestResponse.body)
      validate(manifest, locale)
      val entry = manifest.locales[locale] ?: invalid("Manifest does not contain requested locale")
      if (previous != null && manifest.release < previous.manifest.release) {
        return@withLock RefreshResult.Unchanged(previous.manifest.release)
      }
      if (previous != null && manifest.release == previous.manifest.release) {
        if (entry.hash != previous.manifest.locales[locale]?.hash) {
          invalid("Same release returned different artifact hash")
        }
        return@withLock RefreshResult.Unchanged(manifest.release)
      }
      val artifactResponse = configuration.transport.get(
        resolvedArtifactUrl(entry.url), requestHeaders(null), configuration.timeoutMillis,
      )
      requireSuccess(artifactResponse, "artifact")
      if (artifactResponse.body.size != entry.size) invalid("Artifact size mismatch")
      if (artifactResponse.body.sha256() != entry.hash) invalid("Artifact hash mismatch")
      val artifact = decode<OtaArtifact>(artifactResponse.body)
      validate(artifact, manifest, locale)
      val next = CacheEnvelope(manifest, manifestResponse.headers["etag"], artifact)
      configuration.cache.writeAtomically(
        cacheKey(locale), json.encodeToString(next).encodeToByteArray(),
      )
      val installed = synchronized(stateLock) {
        if (activeLocale != locale) false else { state = next; true }
      }
      if (installed) RefreshResult.Updated(manifest.release)
      else RefreshResult.Failed("Locale changed during refresh")
    } catch (error: Exception) {
      RefreshResult.Failed(error.message ?: error::class.java.simpleName)
    }
  }

  public suspend fun setLocale(locale: String, refresh: Boolean = true): RefreshResult {
    if (locale.isBlank()) return RefreshResult.Failed("locale is required")
    val canonicalLocale = canonicalLocale(locale)
    synchronized(stateLock) { activeLocale = canonicalLocale; state = null }
    loadCache(canonicalLocale)
    return if (refresh) this.refresh() else RefreshResult.Unchanged(currentRelease)
  }

  public fun translation(
    key: String,
    namespace: String = "default",
    fallback: String? = null,
  ): String {
    val (locale, ota) = synchronized(stateLock) {
      activeLocale to state?.artifact?.namespaces?.get(namespace)?.get(key)
    }
    return ota ?: configuration.bundled?.translation(locale, namespace, key) ?: fallback ?: key
  }

  public fun translation(
    key: String,
    namespace: String = "default",
    arguments: List<String>,
    fallback: String? = null,
  ): String = formatAndroidMessage(translation(key, namespace, fallback), arguments)

  public fun namespaces(): List<String> = synchronized(stateLock) {
    state?.artifact?.namespaces?.keys.orEmpty().sorted()
  }

  public fun keys(namespace: String? = null): List<String> = synchronized(stateLock) {
    val names = namespace?.let { listOf(it) } ?: state?.artifact?.namespaces?.keys.orEmpty()
    names.flatMap { state?.artifact?.namespaces?.get(it)?.keys.orEmpty() }.sorted()
  }

  public fun translations(namespace: String? = null): List<TranslationEntry> = synchronized(stateLock) {
    val names = namespace?.let { listOf(it) } ?: state?.artifact?.namespaces?.keys.orEmpty()
    names.flatMap { name -> state?.artifact?.namespaces?.get(name).orEmpty().map { (key, value) -> TranslationEntry(key, name, value) } }
      .sortedWith(compareBy({ it.namespace }, { it.key }))
  }

  public suspend fun supportedLocales(): List<String> {
    val response = configuration.transport.get(
      localesUrl(), requestHeaders(null), configuration.timeoutMillis,
    )
    requireSuccess(response, "locales")
    return decode<List<String>>(response.body)
  }

  public fun translation(
    key: String,
    namespace: String = "default",
    count: Double,
    arguments: List<String> = emptyList(),
    fallback: String? = null,
  ): String {
    val (locale, otaPlural) = synchronized(stateLock) {
      activeLocale to state?.artifact?.pluralMessages?.get(namespace)?.get(key)
    }
    val bundledPlural = (configuration.bundled as? BundledPluralTranslationAdapter)
      ?.pluralTranslation(locale, namespace, key)
    val plural = otaPlural ?: bundledPlural
      ?: return translation(key, namespace, arguments, fallback)
    val selected = plural.forms[pluralCategory(locale, count)]
      ?: plural.forms["other"] ?: fallback ?: key
    val withCount = replaceNamedToken(selected, plural.variable, displayCount(count))
      ?: return selected
    return if (arguments.isEmpty()) withCount else formatAndroidMessage(withCount, arguments)
  }

  private suspend fun loadCache(locale: String) {
    try {
      val data = configuration.cache.read(cacheKey(locale)) ?: return
      val cached = decode<CacheEnvelope>(data)
      validate(cached.manifest, locale)
      validate(cached.artifact, cached.manifest, locale)
      synchronized(stateLock) { if (activeLocale == locale) state = cached }
    } catch (_: Exception) {
      synchronized(stateLock) { if (activeLocale == locale) state = null }
    }
  }

  private fun validate(manifest: OtaManifest, locale: String) {
    if (manifest.protocolVersion != GOLOCALISE_PROTOCOL_VERSION) {
      invalid("Unsupported OTA protocol version")
    }
    if (manifest.projectId != configuration.projectId ||
      manifest.environment != configuration.environment || manifest.release <= 0
    ) invalid("Manifest scope mismatch")
    val entry = manifest.locales[locale] ?: invalid("Manifest does not contain requested locale")
    if (entry.size < 0 || !entry.hash.matches(Regex("^sha256:[a-f0-9]{64}$"))) {
      invalid("Manifest artifact metadata is invalid")
    }
    try { Instant.parse(manifest.generatedAt) }
    catch (_: Exception) { invalid("Manifest timestamp is invalid") }
  }

  private fun validate(artifact: OtaArtifact, manifest: OtaManifest, locale: String) {
    if (artifact.protocolVersion != GOLOCALISE_PROTOCOL_VERSION) {
      invalid("Unsupported OTA protocol version")
    }
    if (artifact.projectId != configuration.projectId ||
      artifact.environment != configuration.environment || artifact.locale != locale ||
      artifact.release != manifest.release
    ) invalid("Artifact scope mismatch")
  }

  private fun manifestUrl(locale: String): URL {
    val base = configuration.baseUrl.toString().trimEnd('/')
    return URL(
      "$base/ota/v1/projects/${segment(configuration.projectId)}/environments/" +
        "${segment(configuration.environment)}/manifest?locale=${segment(locale)}",
    )
  }

  private fun localesUrl(): URL = URL(
    "${configuration.baseUrl.toString().trimEnd('/')}/ota/v1/projects/${segment(configuration.projectId)}/environments/${segment(configuration.environment)}/locales",
  )

  private fun resolvedArtifactUrl(value: String): URL {
    val resolved = configuration.baseUrl.toURI().resolve(value).toURL()
    val base = configuration.baseUrl.toURI()
    val candidate = resolved.toURI()
    if (base.scheme != candidate.scheme || base.host != candidate.host ||
      effectivePort(base) != effectivePort(candidate)
    ) invalid("Cross-origin artifact URL rejected")
    return resolved
  }

  private fun requestHeaders(etag: String?): Map<String, String> = buildMap {
    put("Authorization", "Bearer ${configuration.token}")
    etag?.let { put("If-None-Match", it) }
  }

  private fun requireSuccess(response: OtaHttpResponse, resource: String) {
    if (response.status !in 200..299) invalid("$resource request failed with HTTP ${response.status}")
  }

  private fun cacheKey(locale: String): String =
    "golocalise-v1:${configuration.projectId}:${configuration.environment}:$locale"

  private inline fun <reified T> decode(bytes: ByteArray): T = try {
    json.decodeFromString(bytes.decodeToString())
  } catch (error: SerializationException) {
    throw IllegalArgumentException("Malformed OTA payload", error)
  }

  private fun segment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

  private fun effectivePort(uri: URI): Int =
    if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

  private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}

public data class TranslationEntry(val key: String, val namespace: String, val value: String)

private fun pluralCategory(locale: String, count: Double): String {
  val language = locale.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
  val integer = count.toInt()
  if (language == "ar" && count == integer.toDouble()) {
    if (integer == 0) return "zero"
    if (integer == 1) return "one"
    if (integer == 2) return "two"
    return when (kotlin.math.abs(integer) % 100) {
      in 3..10 -> "few"
      in 11..99 -> "many"
      else -> "other"
    }
  }
  return if (count == 1.0) "one" else "other"
}

private fun canonicalLocale(locale: String): String = locale.replace('_', '-').split('-')
  .mapIndexed { index, segment ->
    when {
      index == 0 -> segment.lowercase(Locale.ROOT)
      segment.length in 2..3 && segment.all(Char::isLetter) -> segment.uppercase(Locale.ROOT)
      segment.length == 4 -> segment.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
      else -> segment
    }
  }.joinToString("-")

private fun displayCount(count: Double): String =
  if (count == count.toLong().toDouble()) count.toLong().toString() else count.toString()

private fun replaceNamedToken(message: String, name: String, value: String): String? {
  val output = StringBuilder()
  var index = 0
  while (index < message.length) {
    when (message[index]) {
      '}' -> return null
      '{' -> {
        if (index + 1 < message.length && message[index + 1] == '{') {
          output.append('{')
          index += 2
          continue
        }
        val closing = message.indexOf('}', index + 1)
        if (closing < 0) return null
        val token = message.substring(index + 1, closing)
        when {
          token == name -> output.append(value)
          token.toIntOrNull() != null -> output.append('{').append(token).append('}')
          else -> return null
        }
        index = closing + 1
        continue
      }
      else -> output.append(message[index])
    }
    index++
  }
  return output.toString()
}

private fun formatAndroidMessage(message: String, arguments: List<String>): String {
  if ('{' in message || '}' in message) return formatCanonicalMessage(message, arguments)
  var index = 0
  var sequential = 0
  val positional = mutableSetOf<Int>()
  while (index < message.length) {
    if (message[index] != '%') { index++; continue }
    index++
    if (index >= message.length) return message
    if (message[index] == '%') { index++; continue }
    val start = index
    while (index < message.length && message[index].isDigit()) index++
    if (index > start) {
      if (index >= message.length || message[index] != '$') return message
      val position = message.substring(start, index).toIntOrNull() ?: return message
      index++
      if (position < 1 || index >= message.length || message[index] != 's') return message
      positional += position
    } else {
      if (message[index] != 's') return message
      sequential++
    }
    index++
  }
  if (sequential > 0 && positional.isNotEmpty()) return message
  val count = if (positional.isEmpty()) sequential else positional.maxOrNull() ?: 0
  if (count != arguments.size || (positional.isNotEmpty() && positional != (1..count).toSet())) {
    return message
  }
  return String.format(Locale.ROOT, message, *arguments.toTypedArray())
}

private fun formatCanonicalMessage(message: String, arguments: List<String>): String {
  val pattern = Regex("\\{(\\d+)\\}")
  val indexes = pattern.findAll(message).map { it.groupValues[1].toInt() }.toSet()
  val withoutTokens = pattern.replace(message, "")
  val oneBased = indexes.isNotEmpty() && 0 !in indexes && indexes.minOrNull() == 1 && indexes.maxOrNull() == arguments.size
  val expected = if (oneBased) arguments.indices.map { it + 1 }.toSet() else arguments.indices.toSet()
  if ('{' in withoutTokens || '}' in withoutTokens || indexes != expected) return message
  return pattern.replace(message) { match ->
    val index = match.groupValues[1].toInt() - if (oneBased) 1 else 0
    arguments.getOrNull(index) ?: match.value
  }
}

private fun ByteArray.sha256(): String = "sha256:" + MessageDigest.getInstance("SHA-256")
  .digest(this).joinToString("") { "%02x".format(it) }
