package org.dallasmakerspace.smartwaiver

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

@Module
class SmartwaiverModule {

  // SmartwaiverApiClient builds its own HttpClient(...) — without @Singleton here, Dagger built a
  // brand-new one for every injection site (same bug found and fixed for AD's LDAP pool).
  @Provides
  @Singleton
  fun provideSmartwaiverApiClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): ISmartwaiverApiClient =
      if (useMockServices(appConfig)) {
        SmartwaiverApiClientMock(loggerFactory)
      } else {
        SmartwaiverApiClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
