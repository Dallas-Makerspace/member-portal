package org.dallasmakerspace.core

/**
 * Access to app-wide services for code that libraries (e.g. logback) instantiate, where Dagger
 * can't inject. The app module installs the providers at the start of `main()`.
 */
object CoreServices {
  @Volatile private lateinit var appConfigProvider: () -> AppConfig
  @Volatile private lateinit var loggerFactoryProvider: () -> LoggerFactory

  fun install(appConfig: () -> AppConfig, loggerFactory: () -> LoggerFactory) {
    appConfigProvider = appConfig
    loggerFactoryProvider = loggerFactory
  }

  val appConfig: AppConfig
    get() = appConfigProvider()

  val loggerFactory: LoggerFactory
    get() = loggerFactoryProvider()
}
