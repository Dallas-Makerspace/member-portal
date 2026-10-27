package org.dallasmakerspace.di

import dagger.Component
import io.ktor.server.application.*
import javax.inject.Singleton
import org.dallasmakerspace.agent.AgentEvalService
import org.dallasmakerspace.agent.ArtifactService
import org.dallasmakerspace.agent.ChatService
import org.dallasmakerspace.agent.di.AgentModule
import org.dallasmakerspace.askai.AskAiService
import org.dallasmakerspace.askai.di.AskAiModule
import org.dallasmakerspace.itbot.RepoMirrorService
import org.dallasmakerspace.itbot.di.ItBotModule
import org.dallasmakerspace.bambuddy.BambuddyStatusService
import org.dallasmakerspace.bambuddy.notifications.BambuNotificationScheduler
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookHandler
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.committees.CommitteeMessageService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.config.di.ConfigModule
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.cron.AdReplicationHealthCronJob
import org.dallasmakerspace.cron.DoorSwipesCronJob
import org.dallasmakerspace.cron.MemberRefreshCronJob
import org.dallasmakerspace.cron.ShowAndTellCronJob
import org.dallasmakerspace.cron.SmartWaiverProcessQueueJob
import org.dallasmakerspace.dataviz.DataVizRouter
import org.dallasmakerspace.dataviz.di.DataVizModule
import org.dallasmakerspace.discourse.FeaturedProjectsService
import org.dallasmakerspace.doorcontroller.DoorControllerService
import org.dallasmakerspace.keyvalue.di.KeyValueModule
import org.dallasmakerspace.members.ActivityLogService
import org.dallasmakerspace.members.GroupService
import org.dallasmakerspace.members.GroupSettingsService
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.MemberSettingsService
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.moodle.MoodleMemberCoursesService
import org.dallasmakerspace.notifications.CommitteeFollowersService
import org.dallasmakerspace.notifications.DigestProcessor
import org.dallasmakerspace.notifications.FollowSuggestionService
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationWorker
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.di.NotificationsModule
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionService
import org.dallasmakerspace.remoteaccess.RemoteAccessService
import org.dallasmakerspace.shortlinks.ShortLinksService
import org.dallasmakerspace.smartwaiver.SmartWaiverBackfill
import org.dallasmakerspace.smartwaiver.SmartWaiverReconciler
import org.dallasmakerspace.smartwaiver.SmartwaiverModule
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.webhook.WebhookRouter
import org.dallasmakerspace.webhook.di.WebhookModule
import org.dallasmakerspace.workflows.ArtifactLinks
import org.dallasmakerspace.workflows.EngineSettings
import org.dallasmakerspace.workflows.WorkflowEventBus
import org.dallasmakerspace.workflows.WorkflowExecutor
import org.dallasmakerspace.workflows.WorkflowService
import org.dallasmakerspace.workflows.WorkflowStreamLifecycle
import org.dallasmakerspace.workflows.groups.references.GroupReferenceChecker

@Singleton
@Component(
    modules =
        [
            AppModule::class,
            ActiveDirectoryModule::class,
            AgentModule::class,
            AskAiModule::class,
            ItBotModule::class,
            CalendarModule::class,
            ConfigModule::class,
            DiscourseModule::class,
            BambuddyModule::class,
            MoodleModule::class,
            GuacamoleModule::class,
            WinRmModule::class,
            DoorControllerModule::class,
            MasterDbModule::class,
            MembersModule::class,
            DataVizModule::class,
            ShortLinksModule::class,
            SmartwaiverModule::class,
            NotificationsModule::class,
            KeyValueModule::class,
            StorageModule::class,
            WebhookModule::class,
            WorkflowsModule::class,
            GroupReferencesModule::class])
@Suppress("TooManyFunctions")
interface AppComponent {
  fun inject(application: Application)

  fun getAppConfig(): AppConfig

  fun getMemberService(): MemberService

  fun getMemberWaiverPhotosService(): org.dallasmakerspace.smartwaiver.MemberWaiverPhotosService

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

  fun getAdReplicationHealthCronJob(): AdReplicationHealthCronJob

  fun getSmartWaiverBackfill(): SmartWaiverBackfill

  fun getSmartWaiverReconciler(): SmartWaiverReconciler

  fun getSmartWaiverProcessQueueJob(): SmartWaiverProcessQueueJob

  fun getGroupGuidBackfillJob(): org.dallasmakerspace.cron.GroupGuidBackfillJob

  fun getDiscourseAutoLinkCronJob(): org.dallasmakerspace.cron.DiscourseAutoLinkCronJob

  fun getCalendarService(): CalendarService

  fun getShortLinksService(): ShortLinksService

  fun getAskAiService(): AskAiService

  fun getChatService(): ChatService

  fun getAgentEvalService(): AgentEvalService

  fun getArtifactService(): ArtifactService

  fun getRepoMirrorService(): RepoMirrorService

  fun getFeaturedProjectsService(): FeaturedProjectsService

  fun getConfigOverrideService(): ConfigOverrideService

  fun getFeatureFlagService(): FeatureFlagService

  fun getRemoteAccessService(): RemoteAccessService

  fun getBambuddyStatusService(): BambuddyStatusService

  fun getBambuddyWebhookHandler(): BambuddyWebhookHandler

  fun getEphemeralNotificationService(): EphemeralNotificationService

  fun getBambuNotificationScheduler(): BambuNotificationScheduler

  fun getMoodleCatalogService(): MoodleCatalogService

  fun getMoodleMemberCoursesService(): MoodleMemberCoursesService

  fun getMemberSettingsService(): MemberSettingsService

  fun getGroupSettingsService(): GroupSettingsService

  fun getNotificationService(): NotificationService

  fun getNotificationWorker(): NotificationWorker

  fun getDigestProcessor(): DigestProcessor

  fun getProfileSubscriptionService(): ProfileSubscriptionService

  fun getFollowSuggestionService(): FollowSuggestionService

  fun getTopicSubscriptionService(): TopicSubscriptionService

  fun getCommitteeFollowersService(): CommitteeFollowersService

  fun getCommitteeMessageService(): CommitteeMessageService

  fun getObjectStoreFactory(): ObjectStoreFactory

  fun getProfilePhotoService(): ProfilePhotoService

  fun getWorkflowService(): WorkflowService

  fun getWorkflowEventBus(): WorkflowEventBus

  fun getWorkflowExecutor(): WorkflowExecutor

  fun getWorkflowEngineSettings(): EngineSettings

  fun getWorkflowStreamLifecycle(): WorkflowStreamLifecycle

  fun getWorkflowArtifactLinks(): ArtifactLinks

  fun getGroupReferenceChecker(): GroupReferenceChecker

  fun getGroupArchiveRepository(): IGroupArchiveRepository
}
