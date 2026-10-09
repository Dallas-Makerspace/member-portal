package org.dallasmakerspace.core

import javax.inject.Inject
import javax.inject.Singleton
import org.jetbrains.exposed.sql.Database

/** Connection class for member portal database. */
@Singleton
class DBMemberPortalConnection @Inject constructor(val appConfig: AppConfig) {
  fun connect() {
    var dbUrl = appConfig.requireStringProperty("app.db.url")
    val dbUser = appConfig.requireStringProperty("app.db.user")
    val dbPassword = appConfig.requireStringProperty("app.db.password")

    // Add connection pool parameters to JDBC URL (if not already present)
    if (!dbUrl.contains("pool")) {
      val separator = if (dbUrl.contains("?")) "&" else "?"
      dbUrl +=
          "$separator" +
              // Without pool=true the driver ignores the pool options and connects per transaction.
              "pool=true&" +
              // Matches Exposed's per-transaction setting, saving two round trips per transaction.
              "autocommit=false&" +
              "maxPoolSize=10&" +
              "minPoolSize=1&" +
              "maxIdleTime=300&" +
              "serverTimezone=UTC&" +
              "autoReconnect=true&" +
              "connectTimeout=3000&" +
              "socketTimeout=30000"
    }

    db = Database.connect(dbUrl, user = dbUser, password = dbPassword)
  }

  companion object {
    lateinit var db: Database
  }
}
