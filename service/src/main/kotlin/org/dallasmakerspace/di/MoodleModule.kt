package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.moodle.IMoodleApiClient
import org.dallasmakerspace.moodle.MoodleApiClient
import org.dallasmakerspace.moodle.MoodleApiClientMock

@Module
class MoodleModule {

  // @Singleton on the method: MoodleApiClient holds a long-lived HttpClient.
  @Provides
  @Singleton
  fun provideMoodleApiClient(appConfig: AppConfig, loggerFactory: LoggerFactory): IMoodleApiClient =
      if (useMockServices(appConfig)) {
        MoodleApiClientMock(loggerFactory)
      } else {
        MoodleApiClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
