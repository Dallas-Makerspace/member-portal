package org.dallasmakerspace.config.di

import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.db.ConfigOverrideRepository
import org.dallasmakerspace.config.db.ConfigOverrideRepositoryMock
import org.dallasmakerspace.config.db.IConfigOverrideRepository
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

@Module
class ConfigModule {
  @Provides
  @Singleton
  fun provideConfigOverrideRepository(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory,
  ): IConfigOverrideRepository =
      if (useMockServices(appConfig)) {
        ConfigOverrideRepositoryMock()
      } else {
        ConfigOverrideRepository(loggerFactory)
      }

  @Provides
  @Singleton
  fun provideConfigOverrideService(
      repository: IConfigOverrideRepository,
      loggerFactory: LoggerFactory,
  ): ConfigOverrideService = ConfigOverrideService(repository, loggerFactory)

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
