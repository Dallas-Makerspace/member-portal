package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseApiClient
import org.dallasmakerspace.discourse.DiscourseApiClientMock
import org.dallasmakerspace.discourse.IDiscourseApiClient

@Module
class DiscourseModule {

  // DiscourseApiClient builds its own HttpClient(CIO) — without @Singleton here, Dagger built a
  // brand-new one for every injection site (same bug found and fixed for AD's LDAP pool).
  @Provides
  @Singleton
  fun provideDiscourseApiClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): IDiscourseApiClient =
      if (useMockServices(appConfig)) {
        DiscourseApiClientMock(loggerFactory)
        // DiscourseApiClient(appConfig, log)
      } else {
        DiscourseApiClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
