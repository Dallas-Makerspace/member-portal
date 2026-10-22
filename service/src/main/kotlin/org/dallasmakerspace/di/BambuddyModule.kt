package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.BambuddyApiClient
import org.dallasmakerspace.bambuddy.BambuddyApiClientMock
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.subscriptions.BambuCommitteeAlertsSubscriptionHandler
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionHandler

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

  /**
   * "Notify me when a 3D printer goes offline" subscribe-button handler, keyed by its topic.
   * Registered here (the feature module that owns it) — Dagger merges @IntoMap contributions
   * across all modules in AppComponent; precedent: WebhookModule registering notification
   * templates owned elsewhere.
   */
  @Provides
  @Singleton
  @IntoMap
  @StringKey(TopicKeys.BAMBU_COMMITTEE_ALERTS)
  fun provideBambuCommitteeAlertsSubscriptionHandler(
      handler: BambuCommitteeAlertsSubscriptionHandler
  ): TopicSubscriptionHandler = handler

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
