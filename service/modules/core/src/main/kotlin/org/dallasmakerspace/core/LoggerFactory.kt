package org.dallasmakerspace.core

import io.ktor.util.logging.*
import javax.inject.Inject
import org.dallasmakerspace.core.logging.AppInMemoryLogger

class LoggerFactory @Inject constructor() {
  fun create(clazz: Class<*>): Logger = appLogger(clazz.simpleName)

  fun createInMemoryLogger(clazz: Class<*>): Logger = AppInMemoryLogger(clazz)
}
