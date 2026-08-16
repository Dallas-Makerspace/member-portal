package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.winrm.IWinRmClient
import org.dallasmakerspace.winrm.WinRmClient
import org.dallasmakerspace.winrm.WinRmClientMock

@Module
class WinRmModule {

  // WinRmClient owns a bounded executor - without @Singleton here, Dagger would build a brand
  // new one (and thread pool) for every injection site, same class of bug already fixed for
  // Guacamole's HTTP client and AD's LDAP pool.
  @Provides
  @Singleton
  fun provideWinRmClient(appConfig: AppConfig, loggerFactory: LoggerFactory): IWinRmClient =
      if (useMockServices(appConfig)) {
        WinRmClientMock(loggerFactory)
      } else {
        WinRmClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
