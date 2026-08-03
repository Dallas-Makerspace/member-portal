package org.dallasmakerspace.notifications.di

import dagger.Module
import dagger.Provides
import io.ktor.client.HttpClient
import javax.inject.Named
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.EmailNotifier
import org.dallasmakerspace.notifications.channels.MockNotifier
import org.dallasmakerspace.notifications.channels.Notifier

@Module
object NotificationsModule {

  /** The single active notifier: mocked in development, real (SparkPost) otherwise. */
  @Provides
  @Singleton
  fun provideNotifier(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory,
      @Named("sparkpost") sparkPostClient: HttpClient,
  ): Notifier =
      if (useMockServices(appConfig)) {
        MockNotifier(loggerFactory)
      } else {
        EmailNotifier(appConfig, loggerFactory, sparkPostClient)
      }

  @Provides
  @Named("sparkpost")
  fun provideSparkPostClient(): HttpClient = EmailNotifier.createClient()

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
