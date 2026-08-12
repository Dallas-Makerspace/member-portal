package org.dallasmakerspace.notifications.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import io.ktor.client.HttpClient
import javax.inject.Named
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.EmailNotifier
import org.dallasmakerspace.notifications.channels.MockNotifier
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.templates.NotificationTemplate
import org.dallasmakerspace.notifications.templates.SystemTestTemplate

@Module
object NotificationsModule {

  /**
   * SystemTestTemplate isn't owned by any specific feature (it's a manual pipeline-exercising
   * tool), so it's registered here rather than in a feature module. Return type must stay the
   * explicit `NotificationTemplate<*>` - inferring the concrete `SystemTestTemplate` type would
   * contribute to the wrong Dagger multibinding set.
   */
  @Provides
  @IntoSet
  fun provideSystemTestTemplate(): NotificationTemplate<*> = SystemTestTemplate()

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
