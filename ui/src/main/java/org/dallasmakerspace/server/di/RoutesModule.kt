package org.dallasmakerspace.server.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.chronoring.ChronoRingClient
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.MemberSettingsHandler
import org.dallasmakerspace.server.remoteaccess.RemoteAccessConnectHandler
import org.dallasmakerspace.server.remoteaccess.RemoteAccessDisconnectHandler
import org.dallasmakerspace.server.remoteaccess.RemoteAccessHandler
import org.dallasmakerspace.server.remoteaccess.RemoteAccessRefreshHandler
import org.dallasmakerspace.server.routes.ActionTrackingHandler
import org.dallasmakerspace.server.routes.AskAiHandler
import org.dallasmakerspace.server.routes.BackendApiHandler
import org.dallasmakerspace.server.routes.CabinetsHandler
import org.dallasmakerspace.server.routes.CamerasApiHandler
import org.dallasmakerspace.server.routes.CamerasHandler
import org.dallasmakerspace.server.routes.CommitteeDetailHandler
import org.dallasmakerspace.server.routes.CommitteesHandler
import org.dallasmakerspace.server.routes.ConfigAdminHandler
import org.dallasmakerspace.server.routes.GroupMemberManagementHandler
import org.dallasmakerspace.server.routes.GroupSettingsHandler
import org.dallasmakerspace.server.routes.GroupsHandler
import org.dallasmakerspace.server.routes.IRouteHandler
import org.dallasmakerspace.server.routes.IndexHandler
import org.dallasmakerspace.server.routes.InterlockToolsHandler
import org.dallasmakerspace.server.routes.LoginHandler
import org.dallasmakerspace.server.routes.ManifestHandler
import org.dallasmakerspace.server.routes.NotificationsInboxHandler
import org.dallasmakerspace.server.routes.OfflineHandler
import org.dallasmakerspace.server.routes.OidcCallbackHandler
import org.dallasmakerspace.server.routes.PingHandler
import org.dallasmakerspace.server.routes.ProfileDebugInfoHandler
import org.dallasmakerspace.server.routes.ProfileFeaturedProjectsHandler
import org.dallasmakerspace.server.routes.ProfileFollowsHandler
import org.dallasmakerspace.server.routes.ProfileHandler
import org.dallasmakerspace.server.routes.ProfileKioskWaiverPhotosHandler
import org.dallasmakerspace.server.routes.ProfileMeHandler
import org.dallasmakerspace.server.routes.ProfileTotalActiveTimeHandler
import org.dallasmakerspace.server.routes.ReportHandler
import org.dallasmakerspace.server.routes.ScannerHandler
import org.dallasmakerspace.server.routes.ScannerStatusApiHandler
import org.dallasmakerspace.server.routes.SearchPreloadHandler
import org.dallasmakerspace.server.routes.SettingsBannerHandler
import org.dallasmakerspace.server.routes.ShortLinkDetailsHandler
import org.dallasmakerspace.server.routes.ShortLinkRedirectHandler
import org.dallasmakerspace.server.routes.ShortLinksAdminHandler
import org.dallasmakerspace.server.routes.ShortLinksHandler
import org.dallasmakerspace.server.routes.SmartWaiverWebhookHandler
import org.dallasmakerspace.server.routes.SparkPostWebhookHandler
import org.dallasmakerspace.server.routes.SuggestedEventsHandler
import org.dallasmakerspace.server.voterregistration.VoterRegistrationManager

@Module
@Suppress("TooManyFunctions")
class RoutesModule {

  @IntoMap
  @Provides
  @StringKey("/ping")
  fun providesPingHandler(
      loggerFactory: LoggerFactory,
      memberService: MemberService
  ): IRouteHandler = PingHandler(loggerFactory, memberService)

  @IntoMap
  @Provides
  @StringKey("/search-preload")
  fun providesSearchPreloadHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = SearchPreloadHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/")
  fun providesIndexHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = IndexHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap @Provides @StringKey("/login") fun providesLoginHandler(): IRouteHandler = LoginHandler()

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}")
  fun providesProfileHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      voterRegistrationManager: VoterRegistrationManager,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      ProfileHandler(
          loggerFactory,
          userInfoProvider,
          memberService,
          voterRegistrationManager,
          memberServiceClient,
      )

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/featured-projects")
  fun providesProfileFeaturedProjectsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      ProfileFeaturedProjectsHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/total-active-time")
  fun providesProfileTotalActiveTimeHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = ProfileTotalActiveTimeHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/debug-info")
  fun providesProfileDebugInfoHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = ProfileDebugInfoHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/kiosk-waiver-photos")
  fun providesProfileKioskWaiverPhotosHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = ProfileKioskWaiverPhotosHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/profile-me")
  fun providesProfileMeHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
  ): IRouteHandler = ProfileMeHandler(loggerFactory, userInfoProvider)

  @IntoMap
  @Provides
  @StringKey("/oidc-callback")
  fun providesOidcCallbackHandler(): IRouteHandler = OidcCallbackHandler()

  @IntoMap
  @Provides
  @StringKey("/groups/{group_slug}")
  fun providesGroupHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      GroupsHandler(loggerFactory, userInfoProvider, memberService, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/groups/{group_slug}/members")
  fun providesGroupMemberManagementHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = GroupMemberManagementHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/groups/{group_slug}/members/{username}")
  fun providesGroupMemberRemoveHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = GroupMemberManagementHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/groups/{group_slug}/settings")
  fun providesGroupSettingsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      GroupSettingsHandler(loggerFactory, userInfoProvider, memberService, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/reports/{path...}")
  fun providesReportHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = ReportHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/backend-api/{path...}")
  fun providesBackendApiHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService
  ): IRouteHandler = BackendApiHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/settings")
  fun providesMemberSettingsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = MemberSettingsHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/follows")
  fun providesProfileFollowsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = ProfileFollowsHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/short-links")
  fun providesShortLinksHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      appConfig: AppConfig
  ): IRouteHandler = ShortLinksHandler(loggerFactory, userInfoProvider, memberService, appConfig)

  @IntoMap
  @Provides
  @StringKey("/short-links/admin")
  fun providesShortLinksAdminHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      appConfig: AppConfig
  ): IRouteHandler =
      ShortLinksAdminHandler(loggerFactory, userInfoProvider, memberService, appConfig)

  @IntoMap
  @Provides
  @StringKey("/short-links/{id}")
  fun providesShortLinkDetailsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService
  ): IRouteHandler = ShortLinkDetailsHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/go/{path...}")
  fun providesShortLinkRedirectHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
      appConfig: AppConfig
  ): IRouteHandler =
      ShortLinkRedirectHandler(loggerFactory, userInfoProvider, memberServiceClient, appConfig)

  @IntoMap
  @Provides
  @StringKey("/committees")
  fun providesCommitteesHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService
  ): IRouteHandler = CommitteesHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/committees/{committee_slug}")
  fun providesCommitteeDetailHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService
  ): IRouteHandler = CommitteeDetailHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/interlock-tools")
  fun providesInterlockToolsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
  ): IRouteHandler = InterlockToolsHandler(loggerFactory, userInfoProvider)

  @IntoMap
  @Provides
  @StringKey("/interlock-cabinets")
  fun providesCabinetsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
  ): IRouteHandler = CabinetsHandler(loggerFactory, userInfoProvider)

  @IntoMap
  @Provides
  @StringKey("/api/track")
  fun providesActionTrackingHandler(
      loggerFactory: LoggerFactory,
  ): IRouteHandler = ActionTrackingHandler(loggerFactory)

  @IntoMap
  @Provides
  @StringKey("/api/settings-banner")
  fun providesSettingsBannerHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = SettingsBannerHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/api/suggested-events")
  fun providesSuggestedEventsHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
  ): IRouteHandler = SuggestedEventsHandler(loggerFactory, userInfoProvider, memberService)

  @IntoMap
  @Provides
  @StringKey("/ask-ai")
  fun providesAskAiHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberService: MemberService,
      appConfig: AppConfig
  ): IRouteHandler = AskAiHandler(loggerFactory, userInfoProvider, memberService, appConfig)

  @IntoMap
  @Provides
  @StringKey("/manifest.json")
  fun providesManifestHandler(loggerFactory: LoggerFactory, appConfig: AppConfig): IRouteHandler =
      ManifestHandler(loggerFactory, appConfig)

  @IntoMap
  @Provides
  @StringKey("/offline")
  fun providesOfflineHandler(loggerFactory: LoggerFactory): IRouteHandler =
      OfflineHandler(loggerFactory)

  @IntoMap
  @Provides
  @StringKey("/admin/config")
  fun providesConfigAdminHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = ConfigAdminHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/remote-access")
  fun providesRemoteAccessHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = RemoteAccessHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/remote-access/refresh")
  fun providesRemoteAccessRefreshHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = RemoteAccessRefreshHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/remote-access/connect/{connectionId}")
  fun providesRemoteAccessConnectHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      RemoteAccessConnectHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/remote-access/disconnect/{connectionId}")
  fun providesRemoteAccessDisconnectHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler =
      RemoteAccessDisconnectHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/cameras")
  fun providesCamerasHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
  ): IRouteHandler = CamerasHandler(loggerFactory, userInfoProvider)

  @IntoMap
  @Provides
  @StringKey("/cameras-api/{path...}")
  fun providesCamerasApiHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      chronoRingClient: ChronoRingClient,
  ): IRouteHandler = CamerasApiHandler(loggerFactory, userInfoProvider, chronoRingClient)

  @IntoMap
  @Provides
  @StringKey("/personal-bin-scanner")
  fun providesScannerHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
  ): IRouteHandler = ScannerHandler(loggerFactory, userInfoProvider)

  @IntoMap
  @Provides
  @StringKey("/scanner-api/status/{username}")
  fun providesScannerStatusApiHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = ScannerStatusApiHandler(loggerFactory, userInfoProvider, memberServiceClient)

  @IntoMap
  @Provides
  @StringKey("/webhook/smartwaiver")
  fun providesSmartWaiverWebhookHandler(
      loggerFactory: LoggerFactory,
      appConfig: AppConfig,
  ): IRouteHandler = SmartWaiverWebhookHandler(loggerFactory, appConfig)

  @IntoMap
  @Provides
  @StringKey("/webhook/sparkpost")
  fun providesSparkPostWebhookHandler(
      loggerFactory: LoggerFactory,
      appConfig: AppConfig,
  ): IRouteHandler = SparkPostWebhookHandler(loggerFactory, appConfig)

  @IntoMap
  @Provides
  @StringKey("/profile/@{preferred_username}/notifications")
  fun providesNotificationsInboxHandler(
      loggerFactory: LoggerFactory,
      userInfoProvider: UserInfoProvider,
      memberServiceClient: MemberServiceClient,
  ): IRouteHandler = NotificationsInboxHandler(loggerFactory, userInfoProvider, memberServiceClient)
}
