package org.dallasmakerspace.core

import javax.inject.Inject
import javax.inject.Singleton
import org.jetbrains.exposed.sql.Database

/**
 * Connection class for dms-master DB Server. Used for AccessControl, dms-makermanager, dms-whmcs
 * databases
 */
@Singleton
class DBMasterConnection @Inject constructor(val appConfig: AppConfig) {

  fun connect() {
    var dbUrl = appConfig.requireStringProperty("app.db-master.url")
    val dbUser = appConfig.requireStringProperty("app.db-master.user")
    val dbPassword = appConfig.requireStringProperty("app.db-master.password")

    // Add connection pool parameters to JDBC URL (if not already present)
    if (!dbUrl.contains("pool")) {
      val separator = if (dbUrl.contains("?")) "&" else "?"
      dbUrl +=
          "$separator" +
              "maxPoolSize=10&" +
              "minPoolSize=1&" +
              "maxIdleTime=60000&" +
              "serverTimezone=UTC&" +
              "autoReconnect=true&" +
              "connectTimeout=3000&" +
              "socketTimeout=30000&" +
              "characterEncoding=UTF-8&" +
              "useUnicode=true"
    }

    db = Database.connect(dbUrl, user = dbUser, password = dbPassword)
  }

  companion object {
    lateinit var db: Database
  }
}
