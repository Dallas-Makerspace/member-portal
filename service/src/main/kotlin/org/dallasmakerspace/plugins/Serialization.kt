package org.dallasmakerspace.plugins

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
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

fun Application.configureSerialization() {
  install(ContentNegotiation) {
    gson {
      setPrettyPrinting()
      registerTypeAdapter(Instant::class.java, IsoStringAdapter(Instant::parse))
      registerTypeAdapter(LocalDate::class.java, IsoStringAdapter(LocalDate::parse))
      registerTypeAdapter(LocalDateTime::class.java, IsoStringAdapter(LocalDateTime::parse))
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
