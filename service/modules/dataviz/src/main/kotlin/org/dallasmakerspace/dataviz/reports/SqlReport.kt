package org.dallasmakerspace.dataviz.reports

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.core.api.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.toJsonElement
import org.dallasmakerspace.models.toJsonElementForDate

private const val MILLIS_IN_SECOND = 1000f

abstract class SqlReport(private val genericRepository: GenericRepository) : DataVizReport() {

  // Helper function to infer DataType from a value
  protected fun inferDataType(value: Any?): DataType {
    return when (value) {
      is String -> DataType.STRING
      is Number -> DataType.NUMBER // Covers Int, Long, Double, Float, BigDecimal
      is Date -> DataType.DATE
      is Boolean -> DataType.BOOLEAN
      null -> DataType.STRING // Default for null values, consider if a more specific type is needed
      else -> DataType.STRING // Fallback for other types
    }
  }

  abstract fun getQuery(): String

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val dbData =
        try {
          genericRepository.getReportData(getQuery().trimIndent())
        } catch (ex: Exception) {
          val errorMessage =
              if (ex.message?.contains("max_statement_time exceeded") == true) {
                "Query timed out (max_statement_time exceeded)"
              } else {
                "Query failed: ${ex.message}"
              }
          return DataVizResponse(
              data = null,
              dataFields = emptyList(),
              metadata = mapOf("error" to errorMessage),
          )
        }
    val endTime = System.currentTimeMillis()
    val timeTaken = endTime - startTime
    val timeTakenInSec = String.format(Locale.US, "%.3f", timeTaken / MILLIS_IN_SECOND)

    val dataFields: List<DataField> = getDataFields(dbData)

    val dataItems: List<DataItem>? = getDataItems(dbData)

    val metadata: Map<String, String> =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "$timeTakenInSec sec",
            "SQL Query" to getQuery().trimIndent(),
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }

  protected open fun getDataItems(dbData: GenericRepository.QueryResult?) =
      dbData?.data?.map { row ->
        val valuesMap =
            row.entries.associate { entry ->
              val key = entry.key
              val colValue = entry.value
              key to
                  when (colValue) {
                    is String -> colValue.toJsonElement()
                    is Int -> colValue.toJsonElement() // Uses existing Int?.toJsonElement
                    is Double -> colValue.toJsonElement() // Uses existing Double?.toJsonElement
                    is Boolean -> colValue.toJsonElement() // Uses existing Boolean?.toJsonElement
                    is Date ->
                        colValue
                            .toString()
                            .toJsonElementForDate() // Convert Date to 'yyyy-MM-dd' format
                    is Number -> JsonPrimitive(colValue) // Handles Long, BigDecimal, Float, etc.
                    null -> JsonNull
                    else -> colValue.toString().toJsonElement() // Fallback: convert to string
                  }
            }
        DataItem(values = valuesMap)
      }

  protected open fun getDataFields(dbData: GenericRepository.QueryResult?) =
      dbData?.data?.firstOrNull()?.let { firstRow ->
        firstRow.keys.map { columnName ->
          DataField(
              name = columnName,
              type = inferDataType(firstRow[columnName]),
              label = columnName // Use column name as label by default
              )
        }
      } ?: emptyList()
}
