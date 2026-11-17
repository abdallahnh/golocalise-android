package com.golocalise.sdk

import android.content.res.Resources
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public data class OtaHttpResponse(
  val status: Int,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray = byteArrayOf(),
)

public fun interface OtaTransport {
  public suspend fun get(url: URL, headers: Map<String, String>, timeoutMillis: Int): OtaHttpResponse
}

public interface PersistentCacheAdapter {
  public suspend fun read(key: String): ByteArray?
  public suspend fun writeAtomically(key: String, data: ByteArray)
}

public fun interface BundledTranslationAdapter {
  public fun translation(locale: String, namespace: String, key: String): String?
}

public interface BundledPluralTranslationAdapter : BundledTranslationAdapter {
  public fun pluralTranslation(
    locale: String,
    namespace: String,
    key: String,
  ): OtaPluralMessage?
}

public class UrlConnectionTransport : OtaTransport {
  override suspend fun get(url: URL, headers: Map<String, String>, timeoutMillis: Int): OtaHttpResponse =
    withContext(Dispatchers.IO) {
      val connection = url.openConnection() as HttpURLConnection
      try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        headers.forEach(connection::setRequestProperty)
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
          ?.use { it.readBytes() } ?: byteArrayOf()
        val responseHeaders = connection.headerFields
          .filterKeys { it != null }
          .mapKeys { it.key.lowercase() }
          .mapValues { it.value.joinToString(",") }
        OtaHttpResponse(status, responseHeaders, body)
      } finally {
        connection.disconnect()
      }
    }
}

public class MemoryCacheAdapter : PersistentCacheAdapter {
  private val values = ConcurrentHashMap<String, ByteArray>()
  override suspend fun read(key: String): ByteArray? = values[key]?.copyOf()
  override suspend fun writeAtomically(key: String, data: ByteArray) { values[key] = data.copyOf() }
}

public class FileCacheAdapter(private val directory: File) : PersistentCacheAdapter {
  init { require(directory.mkdirs() || directory.isDirectory) { "Cache directory cannot be created" } }

  override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
    file(key).takeIf(File::isFile)?.readBytes()
  }

  override suspend fun writeAtomically(key: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
    val target = file(key)
    val temporary = File.createTempFile("golocalise-", ".tmp", directory)
    try {
      FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
      Files.move(
        temporary.toPath(), target.toPath(),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
      )
    } finally { temporary.delete() }
  }

  private fun file(key: String): File = File(directory, "${key.sha256()}.json")
}

public class MapBundledTranslations(
  private val values: Map<String, Map<String, Map<String, String>>>,
) : BundledTranslationAdapter {
  override fun translation(locale: String, namespace: String, key: String): String? =
    values[locale]?.get(namespace)?.get(key)
}

public class AndroidResourcesBundledTranslations(
  private val resources: Resources,
  private val identifiers: Map<String, Int>,
) : BundledTranslationAdapter {
  override fun translation(locale: String, namespace: String, key: String): String? {
    val qualifiedKey = if (namespace == "default") key else "$namespace.$key"
    return identifiers[qualifiedKey]?.let(resources::getString)
  }
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
  .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Bundled translations from CI/CD export files of build-only namespaces.
 *
 * The pipeline writes one JSON file per namespace (`checkout.json`, …) from
 * `GET /api/v1/projects/{id}/translations?environment=…&namespace=…&format=json`
 * into the app's assets. Build-only namespaces are not in OTA releases, so
 * lookups fall through to these strings.
 *
 * @param files namespace → export file contents (JSON text).
 */
public class ExportedBundledTranslations(files: Map<String, String>) : BundledTranslationAdapter {
  /** namespace → locale ("*" for single-language files) → key → text */
  private val values: Map<String, Map<String, Map<String, String>>> =
    files.mapValues { (_, json) -> parseTranslationExport(json) }

  override fun translation(locale: String, namespace: String, key: String): String? {
    val byLocale = values[namespace] ?: return null
    return byLocale[locale]?.get(key) ?: byLocale["*"]?.get(key)
  }

  public companion object {
    /** Loads every `*.json` file in `directory` of the app assets; the file name is the namespace. */
    @JvmStatic
    @JvmOverloads
    public fun fromAssets(
      assets: android.content.res.AssetManager,
      directory: String = "golocalise",
    ): ExportedBundledTranslations = ExportedBundledTranslations(
      (assets.list(directory) ?: emptyArray())
        .filter { it.endsWith(".json") }
        .associate { name ->
          name.removeSuffix(".json") to assets.open("$directory/$name").bufferedReader().use { it.readText() }
        },
    )
  }
}

internal fun parseTranslationExport(json: String): Map<String, Map<String, String>> {
  val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()
    as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
  fun strings(element: kotlinx.serialization.json.JsonElement?): Map<String, String> =
    (element as? kotlinx.serialization.json.JsonObject)
      ?.mapNotNull { (key, value) ->
        (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
      }
      ?.toMap()
      .orEmpty()
  val data = root["data"] as? kotlinx.serialization.json.JsonObject
  return if (data != null) data.mapValues { (_, locale) -> strings(locale) } else mapOf("*" to strings(root))
}
