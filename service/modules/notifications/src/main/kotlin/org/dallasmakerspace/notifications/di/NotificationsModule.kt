package org.dallasmakerspace.notifications.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import io.ktor.client.HttpClient
import javax.inject.Named
import javax.inject.Singleton
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.channels.ChannelKey
import org.dallasmakerspace.notifications.channels.EmailNotifier
import org.dallasmakerspace.notifications.channels.MockNotifier
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.subscriptions.FollowMemberSubscriptionHandler
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionHandler
import org.dallasmakerspace.notifications.templates.NotificationTemplate
import org.dallasmakerspace.notifications.templates.SystemTestTemplate

/**
 * Notification pipeline wiring. To add a delivery channel, contribute another
 * `@Provides @Singleton @IntoMap @ChannelKey(Channel.X)` notifier; channels with no notifier are
 * rejected at create time and hidden from preferences.
 */
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

  /** Email notifier: mocked in development, SparkPost otherwise. */
  @Provides
  @Singleton
  @IntoMap
  @ChannelKey(Channel.EMAIL)
  fun provideEmailNotifier(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory,
      @Named("sparkpost") sparkPostClient: HttpClient,
      activeDirectoryService: IActiveDirectoryService,
  ): Notifier =
      if (useMockServices(appConfig)) {
        MockNotifier(loggerFactory, Channel.EMAIL)
      } else {
        EmailNotifier(appConfig, loggerFactory, sparkPostClient, activeDirectoryService)
      }

  /** "Follow a member" button handler, keyed by its topic. */
  @Provides
  @Singleton
  @IntoMap
  @StringKey(TopicKeys.FOLLOWING)
  fun provideFollowMemberSubscriptionHandler(
      handler: FollowMemberSubscriptionHandler
  ): TopicSubscriptionHandler = handler

  @Provides
  @Singleton
  @Named("sparkpost")
  fun provideSparkPostClient(): HttpClient = EmailNotifier.createClient()

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
