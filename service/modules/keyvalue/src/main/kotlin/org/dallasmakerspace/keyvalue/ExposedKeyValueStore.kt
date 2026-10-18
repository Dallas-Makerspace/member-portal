package org.dallasmakerspace.keyvalue

import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.db.suspendTransaction
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** [KeyValueStore] over the `key_value` table in the member portal database. */
@Singleton
class ExposedKeyValueStore @Inject constructor() : KeyValueStore {
  private val utcZone = ZoneId.of("UTC")

  override suspend fun get(namespace: String, key: String): String? {
    validate(namespace, key)
    return suspendTransaction {
      KeyValueTable.select { match(namespace, key) }
          .limit(1)
          .firstOrNull()
          ?.get(KeyValueTable.itemValue)
    }
  }

  override suspend fun put(namespace: String, key: String, value: String) {
    validate(namespace, key, value)
    suspendTransaction {
      // Update first; insert if nothing matched; if a concurrent insert wins, update again.
      if (updateValue(namespace, key, value) == 0 && !insertRow(namespace, key, value)) {
        updateValue(namespace, key, value)
      }
    }
  }

  override suspend fun putIfAbsent(namespace: String, key: String, value: String): Boolean {
    validate(namespace, key, value)
    return suspendTransaction { insertRow(namespace, key, value) }
  }

  override suspend fun putAllIfAbsent(namespace: String, entries: Map<String, String>): Int {
    entries.forEach { (key, value) -> validate(namespace, key, value) }
    return suspendTransaction { entries.count { (key, value) -> insertRow(namespace, key, value) } }
  }

  override suspend fun contains(namespace: String, key: String): Boolean {
    validate(namespace, key)
    return suspendTransaction { KeyValueTable.select { match(namespace, key) }.any() }
  }

  override suspend fun delete(namespace: String, key: String): Boolean {
    validate(namespace, key)
    return suspendTransaction { KeyValueTable.deleteWhere { match(namespace, key) } > 0 }
  }

  override suspend fun keys(namespace: String, prefix: String): Set<String> {
    validate(namespace, "")
    // Filtered here, not with LIKE: no wildcard escaping to get wrong, and namespaces are small.
    return suspendTransaction {
      KeyValueTable.select { KeyValueTable.namespace eq namespace }
          .map { it[KeyValueTable.itemKey] }
          .filter { it.startsWith(prefix) }
          .toSet()
    }
  }

  private fun match(namespace: String, key: String): Op<Boolean> =
      (KeyValueTable.namespace eq namespace) and (KeyValueTable.itemKey eq key)

  private fun updateValue(namespace: String, key: String, value: String): Int =
      KeyValueTable.update({ match(namespace, key) }) {
        it[itemValue] = value
        it[updatedAt] = now()
      }

  /** Inserts the row; false if the key already exists, rethrows anything else. */
  private fun insertRow(namespace: String, key: String, value: String): Boolean =
      try {
        KeyValueTable.insert {
          it[KeyValueTable.namespace] = namespace
          it[itemKey] = key
          it[itemValue] = value
          it[updatedAt] = now()
        }
        true
      } catch (e: ExposedSQLException) {
        if (isDuplicateKey(e)) false else throw e
      }

  private fun now(): LocalDateTime = LocalDateTime.now(utcZone)

  /** H2 reports SQLSTATE 23505; MariaDB reports error 1062 ("Duplicate entry"). */
  private fun isDuplicateKey(e: ExposedSQLException): Boolean {
    val cause = e.cause as? SQLException
    return e.sqlState == "23505" ||
        cause?.errorCode == MARIADB_DUPLICATE_ENTRY ||
        e.message?.contains("Duplicate entry", ignoreCase = true) == true
  }

  private fun validate(namespace: String, key: String, value: String? = null) {
    require(namespace.isNotBlank() && namespace.length <= KeyValueStore.MAX_NAMESPACE_LENGTH) {
      "Namespace must be 1-${KeyValueStore.MAX_NAMESPACE_LENGTH} characters"
    }
    require(key.length <= KeyValueStore.MAX_KEY_LENGTH) {
      "Key must be at most ${KeyValueStore.MAX_KEY_LENGTH} characters"
    }
    if (value != null) {
      require(key.isNotEmpty()) { "Key must not be empty" }
      require(value.toByteArray(Charsets.UTF_8).size <= KeyValueStore.MAX_VALUE_BYTES) {
        "Value must be at most ${KeyValueStore.MAX_VALUE_BYTES} bytes"
      }
    }
  }

  private companion object {
    const val MARIADB_DUPLICATE_ENTRY = 1062
  }
}
