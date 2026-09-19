package com.golocalise.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val GOLOCALISE_PROTOCOL_VERSION: Int = 1

@Serializable
public data class ManifestLocale(val hash: String, val url: String, val size: Int)

@Serializable
public data class OtaManifest(
  val protocolVersion: Int,
  val projectId: String,
  val environment: String,
  val release: Int,
  val generatedAt: String,
  val locales: Map<String, ManifestLocale>,
)

@Serializable(with = OtaArtifactSerializer::class)
public data class OtaArtifact(
  val protocolVersion: Int,
  val projectId: String,
  val environment: String,
  val release: Int,
  val locale: String,
  val namespaces: Map<String, Map<String, String>>,
  val pluralMessages: Map<String, Map<String, OtaPluralMessage>> = emptyMap(),
)

@Serializable
public data class OtaPluralMessage(
  val type: String = "plural",
  val variable: String,
  val forms: Map<String, String>,
)

public object OtaArtifactSerializer : KSerializer<OtaArtifact> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): OtaArtifact {
    val input = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    val scalar = mutableMapOf<String, MutableMap<String, String>>()
    val plurals = mutableMapOf<String, MutableMap<String, OtaPluralMessage>>()
    input.getValue("namespaces").jsonObject.forEach { (namespace, rawEntries) ->
      rawEntries.jsonObject.forEach { (key, rawMessage) ->
        val primitive = rawMessage as? JsonPrimitive
        if (primitive?.isString == true) {
          scalar.getOrPut(namespace, ::mutableMapOf)[key] = primitive.content
        } else {
          val message = rawMessage.jsonObject
          require(message["type"]?.jsonPrimitive?.content == "plural") {
            "Plural message type must be plural"
          }
          val forms = message.getValue("forms").jsonObject.mapValues {
            it.value.jsonPrimitive.content
          }
          require(forms["other"] != null) { "Plural messages require an other form" }
          val plural = OtaPluralMessage(
            variable = message.getValue("variable").jsonPrimitive.content,
            forms = forms,
          )
          plurals.getOrPut(namespace, ::mutableMapOf)[key] = plural
          scalar.getOrPut(namespace, ::mutableMapOf)[key] = forms.getValue("other")
        }
      }
    }
    return OtaArtifact(
      protocolVersion = input.getValue("protocolVersion").jsonPrimitive.int,
      projectId = input.getValue("projectId").jsonPrimitive.content,
      environment = input.getValue("environment").jsonPrimitive.content,
      release = input.getValue("release").jsonPrimitive.int,
      locale = input.getValue("locale").jsonPrimitive.content,
      namespaces = scalar,
      pluralMessages = plurals,
    )
  }

  override fun serialize(encoder: Encoder, value: OtaArtifact) {
    val namespaces = buildJsonObject {
      (value.namespaces.keys + value.pluralMessages.keys).sorted().forEach { namespace ->
        put(namespace, buildJsonObject {
          val scalar = value.namespaces[namespace].orEmpty()
          val plurals = value.pluralMessages[namespace].orEmpty()
          (scalar.keys + plurals.keys).sorted().forEach { key ->
            val plural = plurals[key]
            if (plural == null) put(key, JsonPrimitive(scalar.getValue(key)))
            else put(key, buildJsonObject {
              put("type", JsonPrimitive("plural"))
              put("variable", JsonPrimitive(plural.variable))
              put("forms", buildJsonObject {
                plural.forms.toSortedMap().forEach { (category, form) ->
                  put(category, JsonPrimitive(form))
                }
              })
            })
          }
        })
      }
    }
    (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
      put("protocolVersion", JsonPrimitive(value.protocolVersion))
      put("projectId", JsonPrimitive(value.projectId))
      put("environment", JsonPrimitive(value.environment))
      put("release", JsonPrimitive(value.release))
      put("locale", JsonPrimitive(value.locale))
      put("namespaces", namespaces)
    })
  }
}

@Serializable
internal data class CacheEnvelope(
  val manifest: OtaManifest,
  val manifestETag: String? = null,
  val artifact: OtaArtifact,
)

public sealed interface RefreshResult {
  public data class Updated(val release: Int) : RefreshResult
  public data class Unchanged(val release: Int?) : RefreshResult
  public data class Failed(val message: String) : RefreshResult
}
