package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.BambuddyApiClient
import org.dallasmakerspace.bambuddy.BambuddyApiClientMock
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

@Module
class BambuddyModule {

  // BambuddyApiClient builds its own HttpClient(...) per call - without @Singleton here, Dagger
  // would build a brand-new one for every injection site (same bug found and fixed for AD's LDAP
  // pool and mirrored from GuacamoleModule).
  @Provides
  @Singleton
  fun provideBambuddyApiClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): IBambuddyApiClient =
      if (useMockServices(appConfig)) {
        BambuddyApiClientMock(loggerFactory)
      } else {
        BambuddyApiClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
