package org.dallasmakerspace.core

/**
 * Access to app-wide services for code that libraries (e.g. logback) instantiate, where Dagger
 * can't inject. The app module installs the providers at the start of `main()`.
 */
object CoreServices {
  @Volatile private var appConfigProvider: (() -> AppConfig)? = null
  @Volatile private var loggerFactoryProvider: (() -> LoggerFactory)? = null

  fun install(appConfig: () -> AppConfig, loggerFactory: () -> LoggerFactory) {
    appConfigProvider = appConfig
    loggerFactoryProvider = loggerFactory
  }

  val appConfig: AppConfig
    get() = checkNotNull(appConfigProvider) { NOT_INSTALLED }()

  val loggerFactory: LoggerFactory
    get() = checkNotNull(loggerFactoryProvider) { NOT_INSTALLED }()

  private const val NOT_INSTALLED = "CoreServices.install() must run first (see Application.main)"
}
