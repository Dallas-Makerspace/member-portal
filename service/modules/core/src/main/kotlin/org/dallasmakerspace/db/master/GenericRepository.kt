package org.dallasmakerspace.db.master

import java.sql.ResultSet
import javax.inject.Inject
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.statements.StatementType

class GenericRepository @Inject constructor() {

  data class QueryResult(val data: List<Map<String, Any?>>, val columns: List<ColumnInfo>)

  data class ColumnInfo(val name: String, val type: DataType)

  @Serializable
  enum class DataType {
    STRING,
    NUMBER,
    DATE,
    BOOLEAN,
    BIGINT
  }

  suspend fun getReportData(query: String): QueryResult? {
    return suspendTransaction {
      // Execute the query and get the result
      this.exec(query, explicitStatementType = StatementType.SELECT) { resultSet: ResultSet ->
        val columns = mutableListOf<ColumnInfo>()
        val data = mutableListOf<Map<String, Any?>>()

        // Process the result set
        while (resultSet.next()) {
          val row = mutableMapOf<String, Any?>()
          for (i in 1..resultSet.metaData.columnCount) {
            val columnName = resultSet.metaData.getColumnName(i)
            val columnType = resultSet.metaData.getColumnTypeName(i)
            val value = resultSet.getObject(i)
            row[columnName] = value
            columns.add(
                ColumnInfo(
                    columnName,
                    when (columnType.uppercase()) {
                      "VARCHAR",
                      "CHAR",
                      "TEXT" -> DataType.STRING
                      "INT",
                      "INTEGER",
                      "SMALLINT",
                      "TINYINT",
                      "MEDIUMINT",
                      "BIGINT" -> DataType.NUMBER
                      "DATE",
                      "DATETIME",
                      "TIMESTAMP" -> DataType.DATE
                      "BOOLEAN",
                      "BIT" -> DataType.BOOLEAN
                      else -> DataType.STRING
                    }))
          }
          data.add(row)
        }
        return@exec QueryResult(data, columns)
      }
    }
  }
}
