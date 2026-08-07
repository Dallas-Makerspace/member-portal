package org.dallasmakerspace.plugins

import io.ktor.server.application.*
import org.dallasmakerspace.di.AppComponentHolder

fun Application.configureDatabase() {
  val dbConnection = AppComponentHolder.instance.getDBConnection()
  dbConnection.connect()
  val dbMasterConnection = AppComponentHolder.instance.getDBMasterConnection()
  dbMasterConnection.connect()
}
