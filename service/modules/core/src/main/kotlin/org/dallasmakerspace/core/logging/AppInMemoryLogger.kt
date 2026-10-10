package org.dallasmakerspace.core.logging

import io.ktor.util.logging.*
import org.dallasmakerspace.core.appLogger
import org.slf4j.Marker

/** A simple logger that logs to memory as well as an slf4j logger */
@Suppress("TooManyFunctions")
class AppInMemoryLogger(clazz: Class<*>) : Logger {
  private val logger = appLogger(clazz.simpleName)
  private val logBuffer = StringBuilder()

  fun getLog(): String {
    return logBuffer.toString()
  }

  fun clear() {
    logBuffer.setLength(0)
  }

  override fun getName(): String = logger.name

  override fun isTraceEnabled(): Boolean = logger.isTraceEnabled

  override fun debug(message: String?) {
    logger.debug(message)
    logBuffer.append("DEBUG: $message\n")
  }

  override fun info(message: String?) {
    logger.info(message)
    logBuffer.append("$message\n")
  }

  override fun error(message: String?, throwable: Throwable?) {
    logger.error(message, throwable)
    logBuffer.append("$message; ${throwable?.message}\n")
  }

  override fun isTraceEnabled(p0: Marker?): Boolean = logger.isTraceEnabled(p0)

  override fun trace(p0: String?) = throw NotImplementedError()

  override fun trace(p0: String?, p1: Any?) = throw NotImplementedError()

  override fun trace(p0: String?, p1: Any?, p2: Any?) = throw NotImplementedError()

  override fun trace(p0: String?, vararg p1: Any?) = throw NotImplementedError()

  override fun trace(p0: String?, p1: Throwable?) = throw NotImplementedError()

  override fun trace(p0: Marker?, p1: String?) = throw NotImplementedError()

  override fun trace(p0: Marker?, p1: String?, p2: Any?) = throw NotImplementedError()

  override fun trace(p0: Marker?, p1: String?, p2: Any?, p3: Any?) = throw NotImplementedError()

  override fun trace(p0: Marker?, p1: String?, vararg p2: Any?) = throw NotImplementedError()

  override fun trace(p0: Marker?, p1: String?, p2: Throwable?) = throw NotImplementedError()

  override fun isDebugEnabled(): Boolean = logger.isDebugEnabled

  override fun isDebugEnabled(p0: Marker?): Boolean = logger.isDebugEnabled(p0)

  override fun debug(p0: String?, p1: Any?) {
    logger.debug(p0, p1)
    logBuffer.append("DEBUG: $p0\n")
  }

  override fun debug(p0: String?, p1: Any?, p2: Any?) {
    logger.debug(p0, p1, p2)
    logBuffer.append("DEBUG: $p0\n")
  }

  override fun debug(p0: String?, vararg p1: Any?) {
    logger.debug(p0, *p1)
    logBuffer.append("DEBUG: $p0\n")
  }

  override fun debug(p0: String?, p1: Throwable?) {
    logger.debug(p0, p1)
    logBuffer.append("DEBUG: $p0; ${p1?.message}\n")
  }

  override fun debug(p0: Marker?, p1: String?) = throw NotImplementedError()

  override fun debug(p0: Marker?, p1: String?, p2: Any?) = throw NotImplementedError()

  override fun debug(p0: Marker?, p1: String?, p2: Any?, p3: Any?) = throw NotImplementedError()

  override fun debug(p0: Marker?, p1: String?, vararg p2: Any?) = throw NotImplementedError()

  override fun debug(p0: Marker?, p1: String?, p2: Throwable?) = throw NotImplementedError()

  override fun isInfoEnabled(): Boolean = throw NotImplementedError()

  override fun isInfoEnabled(p0: Marker?): Boolean = throw NotImplementedError()

  override fun info(p0: String?, p1: Any?) = throw NotImplementedError()

  override fun info(p0: String?, p1: Any?, p2: Any?) = throw NotImplementedError()

  override fun info(p0: String?, vararg p1: Any?) = throw NotImplementedError()

  override fun info(p0: String?, p1: Throwable?) = throw NotImplementedError()

  override fun info(p0: Marker?, p1: String?) = throw NotImplementedError()

  override fun info(p0: Marker?, p1: String?, p2: Any?) = throw NotImplementedError()

  override fun info(p0: Marker?, p1: String?, p2: Any?, p3: Any?) = throw NotImplementedError()

  override fun info(p0: Marker?, p1: String?, vararg p2: Any?) = throw NotImplementedError()

  override fun info(p0: Marker?, p1: String?, p2: Throwable?) = throw NotImplementedError()

  override fun isWarnEnabled(): Boolean = logger.isWarnEnabled

  override fun isWarnEnabled(p0: Marker?): Boolean = logger.isWarnEnabled(p0)

  override fun warn(p0: String?) {
    logger.warn(p0)
    logBuffer.append("WARN: $p0\n")
  }

  override fun warn(p0: String?, p1: Any?) {
    logger.warn(p0, p1)
    logBuffer.append("WARN: $p0\n")
  }

  override fun warn(p0: String?, vararg p1: Any?) {
    logger.warn(p0, *p1)
    logBuffer.append("WARN: $p0\n")
  }

  override fun warn(p0: String?, p1: Any?, p2: Any?) {
    logger.warn(p0, p1, p2)
    logBuffer.append("WARN: $p0\n")
  }

  override fun warn(p0: String?, p1: Throwable?) {
    logger.warn(p0, p1)
    logBuffer.append("WARN: $p0; ${p1?.message}\n")
  }

  override fun warn(p0: Marker?, p1: String?) = throw NotImplementedError()

  override fun warn(p0: Marker?, p1: String?, p2: Any?) = throw NotImplementedError()

  override fun warn(p0: Marker?, p1: String?, p2: Any?, p3: Any?) = throw NotImplementedError()

  override fun warn(p0: Marker?, p1: String?, vararg p2: Any?) = throw NotImplementedError()

  override fun warn(p0: Marker?, p1: String?, p2: Throwable?) = throw NotImplementedError()

  override fun isErrorEnabled(): Boolean = logger.isErrorEnabled

  override fun isErrorEnabled(p0: Marker?): Boolean = logger.isErrorEnabled(p0)

  override fun error(p0: String?) {
    logger.error(p0)
    logBuffer.append("$p0\n")
  }

  override fun error(p0: String?, p1: Any?) {
    logger.error(p0, p1)
    logBuffer.append("$p0\n")
  }

  override fun error(p0: String?, p1: Any?, p2: Any?) {
    logger.error(p0, p1, p2)
    logBuffer.append("$p0\n")
  }

  override fun error(p0: String?, vararg p1: Any?) {
    logger.error(p0, *p1)
    logBuffer.append("$p0\n")
  }

  override fun error(p0: Marker?, p1: String?) = throw NotImplementedError()

  override fun error(p0: Marker?, p1: String?, p2: Any?) = throw NotImplementedError()

  override fun error(p0: Marker?, p1: String?, p2: Any?, p3: Any?) = throw NotImplementedError()

  override fun error(p0: Marker?, p1: String?, vararg p2: Any?) = throw NotImplementedError()

  override fun error(p0: Marker?, p1: String?, p2: Throwable?) = throw NotImplementedError()
}
