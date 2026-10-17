package org.dallasmakerspace.smartwaiver

import java.sql.SQLException

/**
 * A message that is safe to log or store. Database errors are reduced to their SQL state and code:
 * Exposed puts the whole statement, with its values (names, emails, birth dates), into the message.
 */
fun Throwable.safeMessage(): String {
  val sql = generateSequence(this) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
  return if (sql != null) {
    "database error (SQL state ${sql.sqlState ?: "unknown"}, code ${sql.errorCode})"
  } else {
    message ?: javaClass.simpleName
  }
}

/** True when a write failed because the row already exists (a unique or primary key). */
internal fun Throwable.isDuplicateKey(): Boolean =
    generateSequence(this) { it.cause }.filterIsInstance<SQLException>().any {
      it.sqlState?.startsWith("23") == true || it.errorCode == MYSQL_DUPLICATE_ENTRY
    }

private const val MYSQL_DUPLICATE_ENTRY = 1062
