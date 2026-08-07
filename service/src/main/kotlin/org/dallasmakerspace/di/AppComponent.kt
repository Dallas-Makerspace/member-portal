package org.dallasmakerspace.di

import dagger.Component
import io.ktor.server.application.*
import javax.inject.Singleton
import org.dallasmakerspace.askai.AskAiService
import org.dallasmakerspace.askai.di.AskAiModule
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.config.di.ConfigModule
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.cron.DoorSwipesCronJob
import org.dallasmakerspace.cron.MemberRefreshCronJob
import org.dallasmakerspace.cron.ShowAndTellCronJob
import org.dallasmakerspace.cron.SmartWaiverBackfillJob
import org.dallasmakerspace.cron.SmartWaiverProcessQueueJob
import org.dallasmakerspace.dataviz.DataVizRouter
import org.dallasmakerspace.dataviz.di.DataVizModule
import org.dallasmakerspace.discourse.FeaturedProjectsService
import org.dallasmakerspace.doorcontroller.DoorControllerService
import org.dallasmakerspace.members.ActivityLogService
import org.dallasmakerspace.members.GroupService
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.MemberSettingsService
import org.dallasmakerspace.notifications.DigestProcessor
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationWorker
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.di.NotificationsModule
import org.dallasmakerspace.remoteaccess.RemoteAccessService
import org.dallasmakerspace.shortlinks.ShortLinksService
import org.dallasmakerspace.smartwaiver.SmartwaiverModule
import org.dallasmakerspace.webhook.WebhookRouter
import org.dallasmakerspace.webhook.di.WebhookModule

@Singleton
@Component(
    modules =
        [
            AppModule::class,
            ActiveDirectoryModule::class,
            AskAiModule::class,
            CalendarModule::class,
            ConfigModule::class,
            DiscourseModule::class,
            GuacamoleModule::class,
            DoorControllerModule::class,
            MasterDbModule::class,
            MembersModule::class,
            DataVizModule::class,
            ShortLinksModule::class,
            SmartwaiverModule::class,
            NotificationsModule::class,
            WebhookModule::class])
@Suppress("TooManyFunctions")
interface AppComponent {
  fun inject(application: Application)

  fun getAppConfig(): AppConfig

  fun getMemberService(): MemberService

  fun getActiveDirectoryService(): org.dallasmakerspace.activedirectory.IActiveDirectoryService

  fun getGroupService(): GroupService

  fun getActivityLogService(): ActivityLogService

  fun getDBConnection(): DBMemberPortalConnection

  fun getDBMasterConnection(): DBMasterConnection

  fun getLoggerFactory(): LoggerFactory

  fun getMemberRefreshCronJob(): MemberRefreshCronJob

  fun getShowAndTellCronJob(): ShowAndTellCronJob

  fun getDataVizRouter(): DataVizRouter

  fun getWebhookRouter(): WebhookRouter

  fun getDoorControllerService(): DoorControllerService

  fun getDoorSwipesCronJob(): DoorSwipesCronJob

  fun getSmartWaiverBackfillJob(): SmartWaiverBackfillJob

  fun getSmartWaiverProcessQueueJob(): SmartWaiverProcessQueueJob

  fun getCalendarService(): CalendarService

  fun getShortLinksService(): ShortLinksService

  fun getAskAiService(): AskAiService

  fun getFeaturedProjectsService(): FeaturedProjectsService

  fun getConfigOverrideService(): ConfigOverrideService

  fun getFeatureFlagService(): FeatureFlagService

  fun getRemoteAccessService(): RemoteAccessService

  fun getMemberSettingsService(): MemberSettingsService

  fun getNotificationService(): NotificationService

  fun getNotificationWorker(): NotificationWorker

  fun getDigestProcessor(): DigestProcessor

  fun getProfileSubscriptionService(): ProfileSubscriptionService
}
