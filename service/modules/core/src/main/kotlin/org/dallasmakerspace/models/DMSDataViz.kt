package org.dallasmakerspace.models

import java.text.DecimalFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class DataVizResponse(
    val data: List<DataItem>?,
    val dataFields: List<DataField>,
    val metadata: Map<String, String>? = null
)

@Serializable data class DataItem(val values: Map<String, JsonElement>)

@Serializable
data class DataField(
    val name: String,
    val type: DataType,
    val format: String? = null, // e.g., date format, number format
    val label: String? = null, // Optional label for display purposes
    val description: String? = null, // Optional description for the field
)

@Serializable
enum class DataType {
  STRING,
  NUMBER,
  DATE,
  BOOLEAN,
  MEMBER, // Member card with username, displayName, avatarUrl
  LINK, // Link with text and url
  BADGE, // Badge with variant (success, danger, etc.) and text, renders with icon
  RELATIVE_DATE // Date rendered as relative time using dms-relative-date component
}

// Extension functions for easy conversion.
fun Double?.toJsonElement(format: String? = null): JsonElement {
  return this?.let {
    if (format != null) {
      // Handle number formatting
      try {
        val df = DecimalFormat(format)
        JsonPrimitive(df.format(it))
      } catch (e: ParseException) {
        JsonPrimitive(it) // If format is invalid, return the original double
      }
    } else {
      JsonPrimitive(it)
    }
  } ?: JsonNull
}

fun Int?.toJsonElement(format: String? = null): JsonElement {
  return this?.let {
    if (format != null) {
      // handle number formatting
      try {
        val df = DecimalFormat(format)
        JsonPrimitive(df.format(it))
      } catch (e: ParseException) {
        JsonPrimitive(it) // If format is invalid, return the original int
      }
    } else {
      JsonPrimitive(it)
    }
  } ?: JsonNull
}

fun String?.toJsonElementForDate(): JsonElement {
  val isoFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" // ISO 8601 with milliseconds and timezone
  return this?.let {
    try {
      val sdf = SimpleDateFormat(isoFormat, Locale.getDefault())
      val date = sdf.parse(it)
      JsonPrimitive(date.time)
    } catch (e: ParseException) {
      JsonPrimitive(it)
    }
  } ?: JsonNull
}

fun String?.toJsonElement(format: String? = null): JsonElement {
  return this?.let { JsonPrimitive(it) } ?: JsonNull
}

fun Boolean?.toJsonElement(): JsonElement {
  return this?.let { JsonPrimitive(it) } ?: JsonNull
}
