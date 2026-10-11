package org.dallasmakerspace.workflows.store

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Stand-in for MariaDB's UTC_TIMESTAMP(); tests move "database time" by bumping [offsetSeconds]. */
object TestDbClock {
  @Volatile var offsetSeconds = 0L

  @JvmStatic
  fun utcTimestamp(): LocalDateTime =
      LocalDateTime.now(ZoneOffset.UTC).withNano(0).plusSeconds(offsetSeconds)
}

object TestDb {
  val db: Database by lazy {
    val database =
        Database.connect(
            "jdbc:h2:mem:workflows-store;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver")
    transaction(database) {
      exec(
          "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR " +
              "\"org.dallasmakerspace.workflows.store.TestDbClock.utcTimestamp\"")
      SchemaUtils.create(
          WorkflowRunTable, WorkflowStepRunTable, WorkflowRunSubjectTable, WorkflowEventTable)
    }
    database
  }

  fun reset() {
    DBMemberPortalConnection.db = db
    TestDbClock.offsetSeconds = 0
    transaction(db) {
      WorkflowEventTable.deleteAll()
      WorkflowStepRunTable.deleteAll()
      WorkflowRunSubjectTable.deleteAll()
      WorkflowRunTable.deleteAll()
    }
  }
}
