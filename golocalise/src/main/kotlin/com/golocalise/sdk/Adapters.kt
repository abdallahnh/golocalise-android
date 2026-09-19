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
