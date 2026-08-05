package org.dallasmakerspace.plugins

import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import java.lang.reflect.Type
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray as KxJsonArray
import kotlinx.serialization.json.JsonElement as KxJsonElement
import kotlinx.serialization.json.JsonNull as KxJsonNull
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.JsonPrimitive as KxJsonPrimitive

fun Application.configureSerialization() {
  install(ContentNegotiation) {
    gson {
      setPrettyPrinting()
      registerTypeAdapter(Instant::class.java, IsoStringAdapter(Instant::parse))
      registerTypeAdapter(LocalDate::class.java, IsoStringAdapter(LocalDate::parse))
      registerTypeAdapter(LocalDateTime::class.java, IsoStringAdapter(LocalDateTime::parse))
      registerTypeHierarchyAdapter(KxJsonElement::class.java, KotlinxJsonElementAdapter)
    }
  }
}

/**
 * Serializes any kotlinx.datetime type as its ISO-8601 `toString()` and parses it back with
 * [parse]. Gson's default reflective adapter can't handle these types - each wraps a private
 * `java.time.*` field (e.g. `java.time.Instant#seconds`, `java.time.LocalDateTime#date`) that the
 * JDK module system blocks reflective access to, and Gson builds adapters for a class's entire
 * field graph eagerly, so this bites any response type that merely *references* one of these types
 * somewhere in its object graph, even via a field that's null at runtime.
 */
private class IsoStringAdapter<T : Any>(private val parse: (String) -> T) :
    JsonSerializer<T>, JsonDeserializer<T> {
  override fun serialize(src: T, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
      JsonPrimitive(src.toString())

  override fun deserialize(
      json: JsonElement,
      typeOfT: Type,
      context: JsonDeserializationContext
  ): T = parse(json.asString)
}

/**
 * Converts kotlinx.serialization's [KxJsonElement] tree (used by [DataItem.values] and similar
 * dynamic-JSON fields) into Gson's own [JsonElement] tree. Without this, Gson's default reflective
 * adapter serializes the *internal* fields of JsonPrimitive/JsonObject/JsonArray (e.g. `content`,
 * `isString`) instead of the value they represent, turning every leaf value into a JS
 * `[object Object]` on the frontend.
 */
private object KotlinxJsonElementAdapter : JsonSerializer<KxJsonElement> {
  override fun serialize(
      src: KxJsonElement,
      typeOfSrc: Type,
      context: JsonSerializationContext
  ): JsonElement = convert(src)

  private fun convert(element: KxJsonElement): JsonElement =
      when (element) {
        is KxJsonNull -> JsonNull.INSTANCE
        is KxJsonPrimitive ->
            if (element.isString) {
              JsonPrimitive(element.content)
            } else {
              JsonParser.parseString(element.content)
            }
        is KxJsonObject -> JsonObject().apply { element.forEach { (k, v) -> add(k, convert(v)) } }
        is KxJsonArray -> JsonArray().apply { element.forEach { add(convert(it)) } }
      }
}
