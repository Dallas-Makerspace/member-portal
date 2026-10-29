package org.dallasmakerspace.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.agent.routing.agentRoutes
import org.dallasmakerspace.askai.AskAiService
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.bambuddy.notifications.configuredBambuddyWebhookSecret
import org.dallasmakerspace.bambuddy.routing.bambuWatchRoutes
import org.dallasmakerspace.bambuddy.routing.bambuddyRoutes
import org.dallasmakerspace.bambuddy.routing.bambuddyWebhookRoute
import org.dallasmakerspace.committees.committeeMessageRoutes
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.routing.configRoutes
import org.dallasmakerspace.config.routing.featureFlagRoutes
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Groups
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.cron.AdReplicationHealthCronJob
import org.dallasmakerspace.cron.AdReplicationHealthCronJobParams
import org.dallasmakerspace.cron.DiscourseAutoLinkCronJobParams
import org.dallasmakerspace.cron.DoorSwipesCronJob
import org.dallasmakerspace.cron.DoorSwipesCronJobParams
import org.dallasmakerspace.cron.GroupGuidBackfillJobParams
import org.dallasmakerspace.cron.MemberRefreshCronJob
import org.dallasmakerspace.cron.MemberRefreshCronJobParams
import org.dallasmakerspace.cron.ShowAndTellCronJob
import org.dallasmakerspace.cron.ShowAndTellCronJobParams
import org.dallasmakerspace.cron.SmartWaiverProcessQueueJob
import org.dallasmakerspace.dataviz.DataVizRouter
import org.dallasmakerspace.di.AppComponentHolder
import org.dallasmakerspace.discourse.FeaturedProjectsService
import org.dallasmakerspace.members.ActivityLogService
import org.dallasmakerspace.members.ExternalAccountAlreadyLinkedException
import org.dallasmakerspace.members.GroupService
import org.dallasmakerspace.members.GroupSettingsService
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.MemberSettingsService
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.routing.archivedGroupsRoutes
import org.dallasmakerspace.members.routing.groupSelfMembershipRoutes
import org.dallasmakerspace.members.routing.profilePhotoRoutes
import org.dallasmakerspace.models.AskAiFeedbackRequest
import org.dallasmakerspace.models.AskAiRequest
import org.dallasmakerspace.models.AskAiStreamResult
import org.dallasmakerspace.models.NamespaceOwnerType
import org.dallasmakerspace.moodle.routing.moodleRoutes
import org.dallasmakerspace.notifications.routing.committeeFollowersRoutes
import org.dallasmakerspace.notifications.routing.notificationRoutes
import org.dallasmakerspace.notifications.routing.profileSubscriptionRoutes
import org.dallasmakerspace.notifications.routing.topicSubscriptionRoutes
import org.dallasmakerspace.remoteaccess.routing.remoteAccessRoutes
import org.dallasmakerspace.routing.*
import org.dallasmakerspace.routing.BadgeLookup
import org.dallasmakerspace.routing.Members
import org.dallasmakerspace.routing.ShortLinksResource
import org.dallasmakerspace.shortlinks.RedirectResult
import org.dallasmakerspace.shortlinks.ShortLinksService
import org.dallasmakerspace.smartwaiver.SmartWaiverBackfill
import org.dallasmakerspace.smartwaiver.SmartWaiverReconciler
import org.dallasmakerspace.webhook.WebhookRouter
import org.dallasmakerspace.workflows.groupLockMessage
import org.dallasmakerspace.workflows.routing.workflowRoutes

/** Dev-only default for `app.notifications.webhook-token`; rejected outside development. */
private const val DEV_NOTIFICATIONS_WEBHOOK_TOKEN = "dev-insecure-webhook-token-change-in-prod"

@Suppress("LongMethod")
fun Application.configureRouting() {
  val log = org.slf4j.LoggerFactory.getLogger("Routing")
  install(Resources)
  install(StatusPages) {
    exception<ExternalAccountAlreadyLinkedException> { call, cause ->
      log.warn(
          "Rejected request: ${call.request.httpMethod.value} ${call.request.path()}: ${cause.message}")
      call.respond(
          HttpStatusCode.Conflict, ApiResponse(Status.ERROR, cause.message ?: "Conflict", null))
    }
    exception { call: ApplicationCall, cause: Exception ->
      log.error(
          "Failed to process request: ${call.request.httpMethod.value} ${call.request.path()}",
          cause)
      call.respond(
          HttpStatusCode.InternalServerError,
          ApiResponse(Status.ERROR, cause.localizedMessage, null),
      )
    }
  }
  val apiKeyAuthProvider: ApiKeyAuthProvider.Configuration.() -> Unit = {
    // Actual validation is done in the provider, this can be refactored away
    validate { null }
  }
  val appConfig: AppConfig = AppComponentHolder.instance.getAppConfig()
  install(Authentication) { apiKey(appConfig, ApiKeyAuthProvider.X_API_KEY, apiKeyAuthProvider) }
  // Eagerly initialize memberService so we can launch the WHMCS ID cache population
  // before the first request arrives. All other services stay lazy (initialized on first use).
  val memberService: MemberService = AppComponentHolder.instance.getMemberService()
  launch { memberService.populateWhmcsAccountCache() }

  // Eagerly initialize featuredProjectsService to trigger background cache fill on startup.
  // Skip in development mode to avoid hammering the Discourse server on every hot-reload.
  val featuredProjectsService: FeaturedProjectsService =
      AppComponentHolder.instance.getFeaturedProjectsService()
  if (!appConfig.requireBooleanProperty("ktor.development")) {
    featuredProjectsService.getFeaturedProjects()
  }

  val configOverrideService: ConfigOverrideService =
      AppComponentHolder.instance.getConfigOverrideService()
  launch { configOverrideService.warmCache() }

  // Carry on a SmartWaiver backfill that was cut off by a restart or deploy.
  val smartWaiverBackfillOnStartup: SmartWaiverBackfill =
      AppComponentHolder.instance.getSmartWaiverBackfill()
  launch {
    runCatching {
      if (shouldAutoResumeBackfill(appConfig) &&
          smartWaiverBackfillRefusal(appConfig, configOverrideService) == null) {
        smartWaiverBackfillOnStartup.resumeIfInterrupted()
      }
    }
  }

  // Notification worker: polls the outbox and sends due notifications (mock notifier in dev).
  val notificationWorker: org.dallasmakerspace.notifications.NotificationWorker =
      AppComponentHolder.instance.getNotificationWorker()
  if (appConfig.requireBooleanProperty("app.notifications.worker.enabled")) {
    val pollIntervalMs = appConfig.requireLongProperty("app.notifications.worker.poll-interval-ms")
    launch {
      while (isActive) {
        try {
          notificationWorker.processPending()
        } catch (e: Exception) {
          log.error("Notification worker run failed: ${e.message}", e)
        }
        delay(pollIntervalMs)
      }
    }
  }

  // Digest processor: polls for IN_DIGEST rows and delivers one digest email per member.
  val digestProcessor: org.dallasmakerspace.notifications.DigestProcessor =
      AppComponentHolder.instance.getDigestProcessor()
  if (appConfig.requireBooleanProperty("app.notifications.digest.enabled")) {
    val pollIntervalMs = appConfig.requireLongProperty("app.notifications.digest.poll-interval-ms")
    launch {
      while (isActive) {
        try {
          digestProcessor.processDigests()
        } catch (e: Exception) {
          log.error("Digest processor run failed: ${e.message}", e)
        }
        delay(pollIntervalMs)
      }
    }
  }

  val notificationsWebhookToken =
      appConfig.requireStringProperty("app.notifications.webhook-token").takeIf {
        it.isNotBlank() &&
            (it != DEV_NOTIFICATIONS_WEBHOOK_TOKEN ||
                appConfig.requireBooleanProperty("ktor.development"))
      }
  if (notificationsWebhookToken == null) {
    log.warn("NOTIFICATIONS_WEBHOOK_TOKEN not set; /webhook/notifications will reject all calls")
  }

  // BamBuddy webhook secret: the handler resolves and enforces it per call (fail closed) via
  // the shared configuredBambuddyWebhookSecret(); this startup check only warns once at boot.
  if (configuredBambuddyWebhookSecret(appConfig) == null) {
    log.warn("BAMBUDDY_WEBHOOK_SECRET not set; /webhook/bambuddy will reject all calls")
  }

  // Bambu notification scheduler: sleeps until the next stored deadline or an in-process wake,
  // then runs expiry checks, offline-hold release and the daily purge. No fixed-interval polling.
  if (appConfig.requireBooleanProperty("app.bambuddy.notifications.sweeper.enabled")) {
    val bambuScheduler = AppComponentHolder.instance.getBambuNotificationScheduler()
    launch { bambuScheduler.run() }
  }

  routing {
    val groupsService: GroupService by lazy { AppComponentHolder.instance.getGroupService() }
    val activityLogService: ActivityLogService by lazy {
      AppComponentHolder.instance.getActivityLogService()
    }
    val calendarService by lazy { AppComponentHolder.instance.getCalendarService() }
    val memberRefreshCronJob: MemberRefreshCronJob by lazy {
      AppComponentHolder.instance.getMemberRefreshCronJob()
    }
    val showAndTellCronJob: ShowAndTellCronJob by lazy {
      AppComponentHolder.instance.getShowAndTellCronJob()
    }
    val doorSwipesCronJob: DoorSwipesCronJob by lazy {
      AppComponentHolder.instance.getDoorSwipesCronJob()
    }
    val adReplicationHealthCronJob: AdReplicationHealthCronJob by lazy {
      AppComponentHolder.instance.getAdReplicationHealthCronJob()
    }
    val smartWaiverBackfill: SmartWaiverBackfill by lazy {
      AppComponentHolder.instance.getSmartWaiverBackfill()
    }
    val smartWaiverReconciler: SmartWaiverReconciler by lazy {
      AppComponentHolder.instance.getSmartWaiverReconciler()
    }
    val processQueueJob: SmartWaiverProcessQueueJob by lazy {
      AppComponentHolder.instance.getSmartWaiverProcessQueueJob()
    }
    val groupGuidBackfillJob by lazy { AppComponentHolder.instance.getGroupGuidBackfillJob() }
    val discourseAutoLinkCronJob by lazy {
      AppComponentHolder.instance.getDiscourseAutoLinkCronJob()
    }
    val dataVizRouter: DataVizRouter by lazy { AppComponentHolder.instance.getDataVizRouter() }
    val webhookRouter: WebhookRouter by lazy { AppComponentHolder.instance.getWebhookRouter() }
    val shortLinksService: ShortLinksService by lazy {
      AppComponentHolder.instance.getShortLinksService()
    }
    val askAiService: AskAiService by lazy { AppComponentHolder.instance.getAskAiService() }
    val memberSettingsService: MemberSettingsService by lazy {
      AppComponentHolder.instance.getMemberSettingsService()
    }
    val groupSettingsService: GroupSettingsService by lazy {
      AppComponentHolder.instance.getGroupSettingsService()
    }
    val featureFlagService by lazy { AppComponentHolder.instance.getFeatureFlagService() }
    val workflowService by lazy { AppComponentHolder.instance.getWorkflowService() }
    val memberWaiverPhotosService by lazy {
      AppComponentHolder.instance.getMemberWaiverPhotosService()
    }

    get("/") {
      call.respondText(
          "<html><body style='font-family:Arial,sans-serif'><h2>API Documentation:</h2>" +
              "<ul><li>member-profile-service - <a href='openapi'>/openapi</a></li>" +
              "<li>badge-lookup-service - <a href='badge-lookup/openapi'>/badge-lookup/openai</a></li>" +
              "</ul></body></html>",
          ContentType.Text.Html,
      )
    }
    swaggerUI(path = "openapi")
    swaggerUI(
        path = "badge-lookup/openapi",
        swaggerFile = "openapi/documentation-badge-lookup.yaml",
    )

    authenticate(ApiKeyAuthProvider.X_API_KEY) {

      /** Member profile operations * */
      authorize(Permission.MANAGE_MEMBERS) {
        get<Members> { members ->
          val loggedInDays = members.loggedInDays
          // Use the new getAllMembers method instead of getMembersLoggedInDays
          val memberList = memberService.getAllMembers()
          call.respond(ApiResponse(Status.SUCCESS, "All members: ${memberList.size}", memberList))
        }

        get<Members.DMSMember> { memberRequested ->
          val member =
              memberService.getMemberByUsername(memberRequested.username, refreshAvatar = true)
          call.respond(ApiResponse(Status.SUCCESS, "Member ${member.username}", member))
        }

        post<Members.Batch> {
          @Suppress("UNCHECKED_CAST") val body = call.receive<Map<String, List<String>>>()
          val usernames = body["usernames"] ?: emptyList()
          val members = memberService.getMembersByUsernameList(usernames)
          call.respond(ApiResponse(Status.SUCCESS, "Fetched ${members.size} members", members))
        }

        /** Activity Log operations - requires member:read since it's member data * */
        get<Members.DMSMember.ActivityLog> { activityLogRequested ->
          // Get activity log ...
          val activityLog =
              activityLogService.getMemberActivityLog(activityLogRequested.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Activity log for ${activityLogRequested.parent.username}",
                  activityLog,
              ))
        }

        /** Calendar Events operations - requires member:read since it's member data * */
        get<Members.DMSMember.Events> { eventsRequested ->
          // Get events organized by member ...
          val events =
              calendarService.getEventsOrganizedByMember(
                  eventsRequested.parent.username,
                  eventsRequested.limit,
              )
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Events organized by ${eventsRequested.parent.username}",
                  events,
              ))
        }

        /** Attended event names - requires member:read since it's member data * */
        get<Members.DMSMember.AttendedEventNames> { resource ->
          val names =
              calendarService.getAttendedEventNames(resource.parent.username, resource.limit)
          call.respond(mapOf("data" to names))
        }

        /** Attended organizers ranked by frequency - requires member:read * */
        get<Members.DMSMember.AttendedOrganizers> { resource ->
          val organizers =
              calendarService.getAttendedOrganizers(resource.parent.username, resource.limit)
          call.respond(mapOf("data" to organizers))
        }

        /** Badge operations - get badge from MakerManager * */
        get<Members.DMSMember.BadgeMM> { badgeRequest ->
          val badge = memberService.getBadgeFromMakerManager(badgeRequest.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Badge from MakerManager for ${badgeRequest.parent.username}",
                  badge,
              ))
        }

        /** Badge operations - get badge from Active Directory * */
        get<Members.DMSMember.BadgeAD> { badgeRequest ->
          val badge =
              withContext(Dispatchers.IO) {
                memberService.getBadgeFromActiveDirectory(badgeRequest.parent.username)
              }
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Badge from Active Directory for ${badgeRequest.parent.username}",
                  badge,
              ))
        }

        get<Members.DMSMember.TotalActiveTime> { request ->
          val totalActiveDays = memberService.getTotalActiveDays(request.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Total active time for ${request.parent.username}",
                  totalActiveDays,
              ))
        }

        /** Debug info operations - get debug info for member * */
        get<Members.DMSMember.DebugInfo> { debugInfoRequest ->
          val debugInfo = memberService.getDebugInfo(debugInfoRequest.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Debug info for ${debugInfoRequest.parent.username}",
                  debugInfo,
              ))
        }

        /** Recent badge swipes for a member - infra only */
        get<Members.DMSMember.BadgeSwipes> { request ->
          val swipes = memberService.getRecentBadgeSwipes(request.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Recent badge swipes for ${request.parent.username}",
                  swipes,
              ))
        }

        /** Kiosk-captured Smartwaiver photos for every waiver tied to a member's email - infra only */
        get<Members.DMSMember.KioskWaiverPhotos> { request ->
          val waivers = memberWaiverPhotosService.getKioskPhotosForMember(request.parent.username)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Kiosk waiver photos for ${request.parent.username}",
                  waivers,
              ))
        }

        /** Scanner status — focused endpoint for bin QR scanner * */
        get<Members.DMSMember.ScannerStatus> { request ->
          val actorUsername = call.request.headers["X-Actor-Username"]
          val status =
              memberService.getScannerStatus(request.parent.username, actorUsername)
                  ?: return@get call.respond(HttpStatusCode.NotFound)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Scanner status for ${request.parent.username}",
                  status,
              ))
        }

        get("/featured-projects") {
          val username = call.request.queryParameters["username"]
          val projects =
              featuredProjectsService.getFeaturedProjects().let { all ->
                if (username != null) all.filter { it.memberUsername == username } else all
              }
          call.respond(ApiResponse(Status.SUCCESS, "Featured projects: ${projects.size}", projects))
        }

        get("/featured-projects/member/{username}") {
          val username =
              call.parameters["username"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null),
                  )
          val projects = featuredProjectsService.getFeaturedProjectsForMemberLive(username)
          call.respond(ApiResponse(Status.SUCCESS, "Featured projects: ${projects.size}", projects))
        }
      }

      /** Member profile write operations * */
      authorize(Permission.MANAGE_MEMBERS) {
        patch<Members.DMSMember.Update> { update ->
          // Update member ...
          val updatedMember = call.receive<Members.DMSMember>()
          memberService.updateMember(update.parent.username, routeObjectToModel(updatedMember))
          call.respond(
              ApiResponse(
                  Status.SUCCESS, "Member ${update.parent.username} updated", updatedMember))
        }

        post<Members.DMSMember.FixBadge> { fixBadge ->
          // Fix badge number by prepending zeros
          val username = fixBadge.parent.username

          // Get badge from member service
          val member = memberService.getMemberByUsername(username, false)
          val badgeFromMM = member.badgeNumber
          if (badgeFromMM.isNullOrEmpty() || badgeFromMM.length >= 10) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(
                    Status.ERROR,
                    "Badge number does not need fixing (either missing or already 10+ digits)",
                    null))
          }

          // Validate badge is numeric
          if (!badgeFromMM.all { it.isDigit() }) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(
                    Status.ERROR,
                    "Badge number must be numeric (contains non-digit characters: $badgeFromMM)",
                    null))
          }

          // Prepend zeros to make it 10 digits
          val paddedBadge = badgeFromMM.padStart(10, '0')

          // Update AD
          try {
            withContext(Dispatchers.IO) { memberService.updateBadgeInAD(username, paddedBadge) }
          } catch (e: Exception) {
            log.error("Failed to update badge for $username", e)
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse(
                    Status.ERROR, "Failed to update badge in Active Directory: ${e.message}", null))
          }

          // Get authenticated user who performed the fix
          val actorUsername = call.request.headers["X-Username"] ?: username

          // Log activity with actor and attributes
          val attributes = """{"oldBadge":"$badgeFromMM","newBadge":"$paddedBadge"}"""
          activityLogService.insertActivityLogEntry(
              actorUsername,
              username,
              org.dallasmakerspace.models.ActivityLogEvent.FIX_BADGE_LENGTH_AD,
              attributes)

          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Badge fixed: $badgeFromMM → $paddedBadge",
                  mapOf("old_badge" to badgeFromMM, "new_badge" to paddedBadge)))
        }

        get("/members/{username}/settings") {
          val username =
              call.parameters["username"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null))
          val settings = memberSettingsService.getSettings(username)
          call.respond(ApiResponse(Status.SUCCESS, "Member settings for $username", settings))
        }

        get("/members/{username}/settings/history") {
          val username =
              call.parameters["username"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null))
          val history = memberSettingsService.getSettingsHistory(username)
          call.respond(
              ApiResponse(Status.SUCCESS, "Member settings history for $username", history))
        }

        patch("/members/{username}/settings") {
          val username =
              call.parameters["username"]
                  ?: return@patch call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null))
          val actorUsername = call.request.headers["X-Actor-Username"]
          val updates = call.receive<Map<String, String?>>()
          memberSettingsService.updateSettings(username, updates, actorUsername)
          val settings = memberSettingsService.getSettings(username)
          call.respond(
              ApiResponse(Status.SUCCESS, "Member settings updated for $username", settings))
        }
      }

      /** Group operations * */
      authorize(Permission.MANAGE_GROUPS) {
        get<Groups> {
          val groupsList = groupsService.getAllGroups()
          call.respond(ApiResponse(Status.SUCCESS, "All groups: ${groupsList.size}", groupsList))
        }

        post<Groups.Batch> { batchRequest ->
          val groupSlugs = call.receive<List<String>>()
          val groups = memberService.getMultipleGroups(groupSlugs)
          call.respond(ApiResponse(Status.SUCCESS, "Fetched ${groups.size} groups", groups))
        }

        get<Groups.DMSGroup> { groupRequested ->
          try {
            val group = memberService.getGroup(groupRequested.groupslug)
            call.respond(ApiResponse(Status.SUCCESS, "Group ${group.name}", group))
          } catch (e: org.dallasmakerspace.activedirectory.ADException) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(Status.ERROR, e.message ?: "Group not found", null))
          }
        }

        get<Groups.DMSGroup.HasPrerequisiteClasses> { request ->
          try {
            val groupSlug = request.parent.groupslug
            val group = groupsService.getGroup(groupSlug)
            val hasClasses = calendarService.hasPrerequisiteEvents(group.name)
            call.respond(ApiResponse(Status.SUCCESS, "Has prerequisite classes", hasClasses))
          } catch (e: org.dallasmakerspace.activedirectory.ADException) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(Status.ERROR, e.message ?: "Group not found", null))
          }
        }

        get<Groups.DMSGroup.IsAdmin> { request ->
          val groupslug = request.parent.groupslug
          val actorUsername = call.request.headers["X-Actor-Username"]
          val isAdmin = actorUsername?.let { memberService.isGroupAdmin(it, groupslug) } ?: false
          call.respond(ApiResponse(Status.SUCCESS, "Group admin check", isAdmin))
        }

        get<Groups.DMSGroup.Settings> { request ->
          val groupName = Groups.getNameFromSlug(request.parent.groupslug)
          val settings = groupSettingsService.getSettings(groupName)
          call.respond(ApiResponse(Status.SUCCESS, "Group settings for $groupName", settings))
        }

        get<Groups.DMSGroup.Settings.History> { request ->
          val groupName = Groups.getNameFromSlug(request.parent.parent.groupslug)
          val history = groupSettingsService.getSettingsHistory(groupName)
          call.respond(
              ApiResponse(Status.SUCCESS, "Group settings history for $groupName", history))
        }

        post("/calendar/upcoming-events") {
          val groupNames = call.receive<List<String>>()
          val events = calendarService.getUpcomingPrerequisiteEvents(groupNames)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Upcoming prerequisite events for ${groupNames.size} groups",
                  events))
        }

        post("/calendar/upcoming-events-by-keywords") {
          val keywords = call.receive<List<String>>()
          if (keywords.size > 20) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(Status.ERROR, "Too many keywords (max 20)", null))
            return@post
          }
          val events = calendarService.getUpcomingEventsByKeywords(keywords, limit = 5)
          call.respond(mapOf("data" to events))
        }

        post("/calendar/upcoming-events-by-organizers") {
          val organizers = call.receive<List<String>>()
          if (organizers.size > 10) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(Status.ERROR, "Too many organizers (max 10)", null))
            return@post
          }
          val events = calendarService.getUpcomingEventsByOrganizers(organizers, limit = 2)
          call.respond(mapOf("data" to events))
        }
      }

      /** Group write operations * */
      authorize(Permission.MANAGE_GROUPS) {
        patch<Groups.DMSGroup.Add> { groupRequested ->
          workflowService
              .groupLockMessage(Groups.getNameFromSlug(groupRequested.parent.groupslug))
              ?.let {
                return@patch call.respond(
                    HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
              }
          val actorUsername = call.request.headers["X-Actor-Username"]
          val isInfra =
              actorUsername?.let { withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(it) } }
                  ?: false
          if (!configOverrideService.get(ConfigRegistry.GROUP_MEMBER_MANAGEMENT_ENABLED, isInfra)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiResponse(Status.ERROR, "Group member management feature is not enabled", null))
            return@patch
          }
          val memberUsername = call.receive<String>()
          val groupslug = groupRequested.parent.groupslug
          memberService.addMembersToGroup(listOf(memberUsername), groupslug, actorUsername)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Added member to $groupslug: $memberUsername",
                  null,
              ))
        }

        delete<Groups.DMSGroup.Add> { groupRequested ->
          workflowService
              .groupLockMessage(Groups.getNameFromSlug(groupRequested.parent.groupslug))
              ?.let {
                return@delete call.respond(
                    HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
              }
          val actorUsername = call.request.headers["X-Actor-Username"]
          val isInfra =
              actorUsername?.let { withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(it) } }
                  ?: false
          if (!configOverrideService.get(ConfigRegistry.GROUP_MEMBER_MANAGEMENT_ENABLED, isInfra)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiResponse(Status.ERROR, "Group member management feature is not enabled", null))
            return@delete
          }
          val memberUsername = call.receive<String>()
          val groupslug = groupRequested.parent.groupslug
          memberService.removeMembersToGroup(listOf(memberUsername), groupslug, actorUsername)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Removed member from $groupslug: $memberUsername",
                  null,
              ))
        }

        /**
         * Group settings — gated by [ConfigRegistry.GROUP_SETTINGS_ENABLED], not by
         * GROUP_MEMBER_MANAGEMENT_ENABLED (which gates admin add/remove above).
         */
        patch<Groups.DMSGroup.Settings> { request ->
          val groupslug = request.parent.groupslug
          val groupName = Groups.getNameFromSlug(groupslug)
          workflowService.groupLockMessage(groupName)?.let {
            return@patch call.respond(HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
          }
          val actorUsername = call.request.headers["X-Actor-Username"]
          // Can't verify pilot membership without an actor, so treat a missing actor as disabled.
          val featureEnabled =
              actorUsername?.let {
                featureFlagService.isEnabledForUsername(ConfigRegistry.GROUP_SETTINGS_ENABLED, it)
              } ?: false
          if (!featureEnabled) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiResponse(Status.ERROR, "Group settings feature is not enabled", null))
            return@patch
          }
          // No AD lookup here for the group's real DN: this is a framework-only, admin-editable
          // feature, and depending on a live AD call in the settings write path would make it
          // fail whenever AD is slow/unavailable for no real benefit. getOrCreateGroup already
          // falls back to a placeholder DN when none is supplied.
          val updates = call.receive<Map<String, String?>>()
          groupSettingsService.updateSettings(groupName, null, updates, actorUsername)
          val settings = groupSettingsService.getSettings(groupName)
          call.respond(
              ApiResponse(Status.SUCCESS, "Group settings updated for $groupName", settings))
        }

        /** Voter registration — not gated by GROUP_MEMBER_MANAGEMENT_ENABLED */
        patch("/voter-registration/{username}") {
          val username =
              call.parameters["username"]
                  ?: return@patch call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null),
                  )
          val actorUsername = call.request.headers["X-Actor-Username"]
          val groupname =
              org.dallasmakerspace.voterregistration
                  .VoterRegistrationManager()
                  .getVotingMembersGroupName()
          workflowService.groupLockMessage(groupname)?.let {
            return@patch call.respond(HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
          }
          memberService.addMembersToGroup(listOf(username), groupname, actorUsername)
          call.respond(ApiResponse(Status.SUCCESS, "Registered $username for voting", null))
        }

        delete("/voter-registration/{username}") {
          val username =
              call.parameters["username"]
                  ?: return@delete call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Username is required", null),
                  )
          val actorUsername = call.request.headers["X-Actor-Username"]
          val groupname =
              org.dallasmakerspace.voterregistration
                  .VoterRegistrationManager()
                  .getVotingMembersGroupName()
          workflowService.groupLockMessage(groupname)?.let {
            return@delete call.respond(HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
          }
          memberService.removeMembersToGroup(listOf(username), groupname, actorUsername)
          call.respond(ApiResponse(Status.SUCCESS, "Unregistered $username from voting", null))
        }
      }

      /** Badge lookup operations * */
      authorize(Permission.MANAGE_BADGES) {
        get<BadgeLookup> {
          val member = withContext(Dispatchers.IO) { memberService.getMemberByBadgeNumber(it.badgeNumber) }
          call.respond(ApiResponse(Status.SUCCESS, "Member ${member.username}", member))
        }
      }

      /** Administrative cron operations * */
      authorize(Permission.EXECUTE_CRON) {
        get("/cron/member-refresh") {
          // Get query string param for isRunningInShadowMode
          val isRunningInShadowMode =
              call.request.queryParameters["isRunningInShadowMode"]?.toBoolean() ?: true
          val params = MemberRefreshCronJobParams(isRunningInShadowMode)
          val result = memberRefreshCronJob.run(params)
          call.respond(result)
        }

        get("/cron/show-and-tell") {
          val isRunningInShadowMode =
              call.request.queryParameters["isRunningInShadowMode"]?.toBoolean() ?: true
          val params = ShowAndTellCronJobParams(isRunningInShadowMode)
          val result = showAndTellCronJob.run(params)
          call.respond(result)
        }

        get("/cron/door-swipes") {
          val params = DoorSwipesCronJobParams()
          val result = doorSwipesCronJob.run(params)
          call.respond(result)
        }

        get("/cron/ad-replication-health") {
          val params = AdReplicationHealthCronJobParams()
          val result = adReplicationHealthCronJob.run(params)
          call.respond(result)
        }

        // The SmartWaiver mirror backfill runs inside the service for days; these routes start,
        // steer and watch it. It needs smartwaiver.mirror.enabled (the migration must be applied).
        get("/cron/backfill-smartwaiver") {
          val restart = "true".equals(call.request.queryParameters["restart"], ignoreCase = true)
          val retryFailed = "true".equals(call.request.queryParameters["retry"], ignoreCase = true)
          val rawLimit = call.request.queryParameters["limit"]
          val limit = rawLimit?.toIntOrNull()?.takeIf { it > 0 }
          val refusal = smartWaiverBackfillRefusal(appConfig, configOverrideService)
          if (refusal != null) {
            call.respondText(refusal, ContentType.Text.Plain, HttpStatusCode.Conflict)
          } else if (rawLimit != null && limit == null) {
            // An unreadable or zero limit must not turn into a full run.
            call.respondText("Refusing: limit must be a whole number above 0", ContentType.Text.Plain, HttpStatusCode.BadRequest)
          } else {
            val started = smartWaiverBackfill.start(restart, limit, retryFailed)
            call.respondText(
                "$started\n" + Json.encodeToString(smartWaiverBackfill.status()),
                ContentType.Text.Plain)
          }
        }

        get("/cron/backfill-smartwaiver/status") {
          call.respondText(Json.encodeToString(smartWaiverBackfill.status()), ContentType.Application.Json)
        }

        get("/cron/backfill-smartwaiver/pause") {
          smartWaiverBackfill.pause()
          call.respondText("pausing at the next waiver", ContentType.Text.Plain)
        }

        get("/cron/backfill-smartwaiver/resume") {
          call.respondText(smartWaiverBackfill.resumeOrStart(), ContentType.Text.Plain)
        }

        get("/cron/backfill-smartwaiver/stop") {
          smartWaiverBackfill.stop()
          call.respondText("stopped; start again to continue from the saved cursors", ContentType.Text.Plain)
        }

        get("/cron/backfill-smartwaiver/errors") {
          call.respondText(smartWaiverBackfill.recentErrors().joinToString("\n"), ContentType.Text.Plain)
        }

        // Server-sent events: every progress change, plus a keep-alive comment every 15 s.
        get("/cron/backfill-smartwaiver/stream") {
          call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
          try {
            call.respondTextWriter(ContentType.Text.EventStream) {
              smartWaiverBackfill.frames().collect { frame ->
                write(frame)
                flush()
              }
            }
          } catch (e: io.ktor.utils.io.ClosedByteChannelException) {
            // The viewer closed its window; that is not an error.
          }
        }

        get("/cron/smartwaiver-reconcile") {
          call.respondText(smartWaiverReconciler.report(), ContentType.Text.Plain)
        }

        // Dry run unless dryRun=false is passed exactly; a typo must not trigger writes.
        get("/cron/group-guid-backfill") {
          val dryRun = !"false".equals(call.request.queryParameters["dryRun"], ignoreCase = true)
          val batchSize = call.request.queryParameters["batchSize"]?.toIntOrNull()
          val params =
              if (batchSize != null) GroupGuidBackfillJobParams(dryRun, batchSize)
              else GroupGuidBackfillJobParams(dryRun)
          call.respondText(groupGuidBackfillJob.run(params), ContentType.Text.Plain)
        }

        // Dry run unless dryRun=false is passed exactly; a typo must not trigger writes.
        get("/cron/discourse-auto-link") {
          val dryRun = !"false".equals(call.request.queryParameters["dryRun"], ignoreCase = true)
          val params = DiscourseAutoLinkCronJobParams(dryRun)
          call.respondText(discourseAutoLinkCronJob.run(params), ContentType.Text.Plain)
        }

        get("/cron/process-smartwaiver-queue") {
          val result = processQueueJob.run()
          call.respondText(result, ContentType.Text.Plain)
        }
      }

      /** Data visualization reports — accessible to all authenticated clients * */
      get("/data-viz/*") {
        val method = call.request.path().substringAfter("/data-viz/")

        // Check for cache override in query parameter (e.g., ?cache=0 to bypass cache)
        val bypassCache = call.request.queryParameters["cache"] == "0"

        if (bypassCache) {
          // Skip cache for this request
          call.response.headers.append("Cache-Control", "no-cache")
        }

        val params =
            call.request.queryParameters
                .names()
                .filter { it != "cache" } // Exclude cache parameter from report params
                .associateWith { paramName ->
                  call.request.queryParameters.getAll(paramName) ?: emptyList()
                }
        val actorUsername = call.request.headers["X-Username"]
        val respone =
            dataVizRouter.route(method, params + ("actorUsername" to listOfNotNull(actorUsername)))
        call.respond(ApiResponse(Status.SUCCESS, "Backend API $method", respone))
      }

      /** Short Links Routes * */
      authorize(Permission.MANAGE_SHORTLINKS) {
        get<ShortLinksResource.Namespaces> {
          val namespaces = shortLinksService.getAllNamespaces()
          call.respond(ApiResponse(Status.SUCCESS, "Namespaces retrieved", namespaces))
        }

        get<ShortLinksResource.Namespaces.ById> { request ->
          val namespace = shortLinksService.getNamespace(request.id)
          if (namespace != null) {
            call.respond(ApiResponse(Status.SUCCESS, "Namespace found", namespace))
          } else {
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(Status.ERROR, "Namespace not found", null),
            )
          }
        }

        get<ShortLinksResource.Links> {
          val links = shortLinksService.getAllShortLinks()
          call.respond(ApiResponse(Status.SUCCESS, "Short links retrieved", links))
        }

        get<ShortLinksResource.Links.ById> { request ->
          val link = shortLinksService.getShortLink(request.id)
          if (link != null) {
            call.respond(ApiResponse(Status.SUCCESS, "Short link found", link))
          } else {
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(Status.ERROR, "Short link not found", null),
            )
          }
        }

        get<ShortLinksResource.Links.ByNamespace> { request ->
          val links = shortLinksService.getAllShortLinks(namespaceId = request.namespaceId)
          call.respond(ApiResponse(Status.SUCCESS, "Namespace links retrieved", links))
        }

        get("/short-links/links/popular") {
          val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
          val popularLinks = shortLinksService.getPopularShortLinks(limit)
          call.respond(
              ApiResponse(
                  Status.SUCCESS,
                  "Popular short links retrieved",
                  popularLinks.map { (link, clicks) -> PopularShortLinkResponse(link, clicks) },
              ))
        }
      }

      authorize(Permission.MANAGE_SHORTLINKS) {
        post("/short-links/namespaces/create") {
          val request = call.receive<CreateNamespaceRequest>()
          val username = call.request.headers["X-Username"]
          val creatorProfileId =
              username?.let { suspendTransaction { ProfileDAO.findById(it)?.idColumn?.value } }

          val result =
              shortLinksService.createNamespace(
                  name = request.name,
                  ownerType =
                      when (request.ownerType) {
                        "committee" -> NamespaceOwnerType.COMMITTEE
                        "system" -> NamespaceOwnerType.SYSTEM
                        else -> throw IllegalArgumentException("Invalid owner type")
                      },
                  ownerGroupId = request.ownerGroupId,
                  description = request.description,
                  primaryAlias = request.primaryAlias,
                  additionalAliases = request.additionalAliases,
                  createdBy = creatorProfileId,
              )

          result.fold(
              onSuccess = { call.respond(ApiResponse(Status.SUCCESS, "Namespace created", it)) },
              onFailure = {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(Status.ERROR, it.message ?: "Failed to create namespace", null),
                )
              },
          )
        }

        patch("/short-links/namespaces/{id}/update") {
          val id =
              call.parameters["id"]?.toIntOrNull()
                  ?: return@patch call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Invalid namespace ID", null),
                  )

          val updates = call.receive<UpdateNamespaceRequest>()
          val existing =
              shortLinksService.getNamespace(id)
                  ?: return@patch call.respond(
                      HttpStatusCode.NotFound,
                      ApiResponse(Status.ERROR, "Namespace not found", null),
                  )

          val updated =
              existing.copy(
                  name = updates.name,
                  description = updates.description,
                  isActive = updates.isActive,
              )

          shortLinksService
              .updateNamespace(id, updated)
              .fold(
                  onSuccess = {
                    call.respond(ApiResponse(Status.SUCCESS, "Namespace updated", it))
                  },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Update failed", null),
                    )
                  },
              )
        }

        delete("/short-links/namespaces/{id}/delete") {
          val id =
              call.parameters["id"]?.toIntOrNull()
                  ?: return@delete call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Invalid namespace ID", null),
                  )

          shortLinksService
              .deleteNamespace(id)
              .fold(
                  onSuccess = {
                    call.respond(ApiResponse(Status.SUCCESS, "Namespace deleted", null))
                  },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Delete failed", null),
                    )
                  },
              )
        }

        post("/short-links/namespaces/{id}/aliases/add") {
          val id =
              call.parameters["id"]?.toIntOrNull()
                  ?: return@post call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Invalid namespace ID", null),
                  )

          val aliasRequest = call.receive<AddAliasRequest>()
          shortLinksService
              .addAlias(id, aliasRequest.alias, aliasRequest.isPrimary)
              .fold(
                  onSuccess = { call.respond(ApiResponse(Status.SUCCESS, "Alias added", it)) },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Failed to add alias", null),
                    )
                  },
              )
        }

        post("/short-links/links/create") {
          val request = call.receive<CreateShortLinkRequest>()
          val username = call.request.headers["X-Username"]
          val creatorProfileId =
              username?.let { suspendTransaction { ProfileDAO.findById(it)?.idColumn?.value } }

          shortLinksService
              .createShortLink(
                  namespaceId = request.namespaceId,
                  slug = request.slug,
                  destinationUrl = request.destinationUrl,
                  description = request.description,
                  creatorId = creatorProfileId,
              )
              .fold(
                  onSuccess = {
                    call.respond(ApiResponse(Status.SUCCESS, "Short link created", it))
                  },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Failed to create link", null),
                    )
                  },
              )
        }

        patch("/short-links/links/{id}/update") {
          val id =
              call.parameters["id"]?.toIntOrNull()
                  ?: return@patch call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Invalid link ID", null),
                  )

          val username = call.request.headers["X-Username"]
          val updaterProfileId =
              username?.let { suspendTransaction { ProfileDAO.findById(it)?.idColumn?.value } }

          val updates = call.receive<UpdateShortLinkRequest>()
          val existing =
              shortLinksService.getShortLink(id)
                  ?: return@patch call.respond(
                      HttpStatusCode.NotFound,
                      ApiResponse(Status.ERROR, "Short link not found", null),
                  )

          val updated =
              existing.copy(
                  slug = updates.slug,
                  destinationUrl = updates.destinationUrl,
                  description = updates.description,
                  isActive = updates.isActive,
                  updatedBy = updaterProfileId,
              )

          shortLinksService
              .updateShortLink(id, updated)
              .fold(
                  onSuccess = {
                    call.respond(ApiResponse(Status.SUCCESS, "Short link updated", it))
                  },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Update failed", null),
                    )
                  },
              )
        }

        delete("/short-links/links/{id}/delete") {
          val id =
              call.parameters["id"]?.toIntOrNull()
                  ?: return@delete call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Invalid link ID", null),
                  )

          shortLinksService
              .deleteShortLink(id)
              .fold(
                  onSuccess = {
                    call.respond(ApiResponse(Status.SUCCESS, "Short link deleted", null))
                  },
                  onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse(Status.ERROR, it.message ?: "Delete failed", null),
                    )
                  },
              )
        }
      }

      /** Ask AI - Question Answering */
      authorize(Permission.USE_ASKAI) {
        post("/ask-ai") {
          val request = call.receive<AskAiRequest>()
          val username = call.request.headers["X-Username"]
          val forceRefresh = call.request.queryParameters["refresh"]?.toBoolean() ?: false
          val response =
              askAiService.ask(request.question, username = username, forceRefresh = forceRefresh)
          call.respond(ApiResponse(Status.SUCCESS, "Question answered", response))
        }

        get("/ask-ai/stream") {
          val question =
              call.request.queryParameters["question"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Missing question parameter", null),
                  )
          val username =
              call.request.headers["X-Username"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Missing X-Username header", null),
                  )

          val forceRefresh = call.request.queryParameters["refresh"]?.toBoolean() ?: false

          call.response.header("Cache-Control", "no-cache")
          call.response.header("X-Accel-Buffering", "no")

          call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            try {
              val result =
                  askAiService.askStream(question, username, forceRefresh) { token ->
                    val escaped = token.replace("\n", "\\n")
                    writeStringUtf8("data: $escaped\n\n")
                    flush()
                  }
              val sourcesJson = Json.encodeToString(result.sources)
              writeStringUtf8("event: sources\ndata: $sourcesJson\n\n")
              flush()
              val doneJson = Json.encodeToString(AskAiStreamResult.serializer(), result)
              writeStringUtf8("event: done\ndata: $doneJson\n\n")
              flush()
            } catch (e: Exception) {
              log.error("Streaming error for question: $question", e)
              writeStringUtf8("event: error\ndata: An error occurred\n\n")
              flush()
            }
          }
        }

        get("/ask-ai/sources") {
          val sources = askAiService.getRegisteredSources()
          call.respond(ApiResponse(Status.SUCCESS, "Registered search sources", sources))
        }

        get("/ask-ai/top-questions") {
          val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
          val topQuestions = askAiService.getTopQuestions(limit)
          call.respond(ApiResponse(Status.SUCCESS, "Top cached questions", topQuestions))
        }

        get("/ask-ai/q/{slug}") {
          val slug =
              call.parameters["slug"]
                  ?: return@get call.respond(
                      HttpStatusCode.BadRequest,
                      ApiResponse(Status.ERROR, "Slug is required", null),
                  )
          val response = askAiService.getBySlug(slug)
          if (response != null) {
            call.respond(ApiResponse(Status.SUCCESS, "Cached answer retrieved", response))
          } else {
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse(Status.ERROR, "Answer not found for slug: $slug", null),
            )
          }
        }

        post("/ask-ai/feedback") {
          val request = call.receive<AskAiFeedbackRequest>()
          askAiService.recordFeedback(
              cacheId = request.cacheId,
              memberId = request.memberId ?: 0,
              isHelpful = request.isHelpful,
          )
          call.respond(ApiResponse(Status.SUCCESS, "Feedback recorded", null))
        }
      }

      /** Activity Log Webhook - Authenticated * */
      authorize(Permission.MANAGE_MEMBERS) {
        post("/webhook-auth/activity-log") {
          val body = call.receiveText()

          val result = webhookRouter.route("activity-log", body)

          if (result.success) {
            call.respond(ApiResponse(Status.SUCCESS, result.message, null))
          } else {
            call.respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, result.message, null))
          }
        }
      }

      /** Config override operations * */
      configRoutes(configOverrideService)

      /** Remote Access — Guacamole VM categories and occupancy * */
      val remoteAccessService by lazy { AppComponentHolder.instance.getRemoteAccessService() }
      remoteAccessRoutes(remoteAccessService)

      /** Bambu 3D printer status — Bambuddy summary/detail/camera proxy * */
      val bambuddyStatusService by lazy { AppComponentHolder.instance.getBambuddyStatusService() }
      bambuddyRoutes(bambuddyStatusService)

      /** Bambu print watches — arm/list/cancel one-shot notifications (actor = X-Username) * */
      val ephemeralNotificationService by lazy {
        AppComponentHolder.instance.getEphemeralNotificationService()
      }
      bambuWatchRoutes(ephemeralNotificationService, featureFlagService)

      /** Workflows — definitions, preflight, runs, operator actions, SSE streams * */
      workflowRoutes(
          workflowService,
          AppComponentHolder.instance.getWorkflowEventBus(),
          AppComponentHolder.instance.getWorkflowStreamLifecycle().streamsClosed,
          artifactLinks = AppComponentHolder.instance.getWorkflowArtifactLinks(),
      )

      /** DMS Learn (Moodle) — course catalog and a member's own course progress * */
      moodleRoutes(
          AppComponentHolder.instance.getMoodleCatalogService(),
          AppComponentHolder.instance.getMoodleMemberCoursesService(),
      )
    }

    /** Notification REST API (emit, outbox, preferences) * */
    val notificationService by lazy { AppComponentHolder.instance.getNotificationService() }
    notificationRoutes(notificationService)

    /** Follow/unfollow another member + who-follows-who listings * */
    val profileSubscriptionService by lazy {
      AppComponentHolder.instance.getProfileSubscriptionService()
    }
    val followSuggestionService by lazy {
      AppComponentHolder.instance.getFollowSuggestionService()
    }
    profileSubscriptionRoutes(
        profileSubscriptionService, featureFlagService, followSuggestionService)

    /** Subscribe/unsubscribe to a notification topic (generic subscribe button) * */
    val topicSubscriptionService by lazy {
      AppComponentHolder.instance.getTopicSubscriptionService()
    }
    topicSubscriptionRoutes(topicSubscriptionService)

    /** Who follows a committee (follower stack and page) * */
    committeeFollowersRoutes(AppComponentHolder.instance.getCommitteeFollowersService())

    /** A chair's short message to a committee's followers * */
    committeeMessageRoutes(AppComponentHolder.instance.getCommitteeMessageService())

    /** Upload/remove/serve a member's profile photo * */
    val profilePhotoService by lazy { AppComponentHolder.instance.getProfilePhotoService() }
    profilePhotoRoutes(profilePhotoService, featureFlagService, memberService)

    /** Member self-service join/leave, gated per group by allow_self_add_remove * */
    groupSelfMembershipRoutes(
        groupSettingsService, memberService, groupLockMessage = workflowService::groupLockMessage)

    /** Archived groups report data (GET /groups/archived) * */
    archivedGroupsRoutes(AppComponentHolder.instance.getGroupArchiveRepository())

    /** Runtime feature-flag checks (e.g. is the follow feature on for a given member) * */
    featureFlagRoutes(featureFlagService)

    /** AI assistant chats (multi-turn, tool-using) * */
    val chatService = AppComponentHolder.instance.getChatService()
    chatService.startDailyCleanup()
    agentRoutes(
        chatService,
        AppComponentHolder.instance.getAgentEvalService(),
        AppComponentHolder.instance.getArtifactService())

    // BamBuddy's outbound notification webhooks: a dedicated route, NOT the generic webhook
    // router below — BamBuddy authenticates with an Authorization bearer secret (which the
    // generic route cannot see and must never log; it logs every header). Registered before
    // post("/webhook/*") so it wins the match. Plain status response; BamBuddy only reads the
    // code.
    bambuddyWebhookRoute { AppComponentHolder.instance.getBambuddyWebhookHandler() }

    // The notifications webhook is protected by a shared secret token; calendar-event and other
    // externally-sourced webhooks stay public here the same way smartwaiver/groups-audit do - this
    // service is only reachable inside the network, so the UI's public-facing proxy handler (with
    // its own token, e.g. SparkPostWebhookHandler) is the real auth boundary for those.
    post("/webhook/*") {
      val path = call.request.path().substringAfter("/webhook/")
      val body = call.receiveText()

      if (path == "notifications" &&
          (notificationsWebhookToken == null ||
              call.request.headers["X-Webhook-Token"] != notificationsWebhookToken)) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ApiResponse(Status.ERROR, "Invalid or missing webhook token", null))
        return@post
      }

      // Log request details for debugging
      log.info(
          "Webhook request received: path=${call.request.path()}, " +
              "query params=${call.request.queryParameters.entries().map { "${it.key}:${it.value}" }
                .joinToString { ";" }}, " +
              "headers=${call.request.headers.entries().map{ "${it.key}:${it.value}" }.joinToString { ";" }}")

      // Route the webhook to appropriate handler
      val result = webhookRouter.route(path, body)

      if (result.success) {
        call.respond(ApiResponse(Status.SUCCESS, result.message, null))
      } else {
        call.respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, result.message, null))
      }
    }

    /** Short Links - Public Redirect Handler * */
    get("/go/{path...}") {
      val path = call.parameters.getAll("path")?.joinToString("/") ?: ""

      val username = call.request.headers["X-Username"]
      val updaterProfileId =
          username?.let { suspendTransaction { ProfileDAO.findById(it)?.idColumn?.value } }
      val isPreview = call.request.queryParameters["preview"] == "true"

      when (val result =
          shortLinksService.resolveRedirect(path, updaterProfileId, recordClick = !isPreview)) {
        is RedirectResult.Success -> {
          result.description?.let {
            call.response.headers.append(
                "X-Short-Link-Description",
                java.net.URLEncoder.encode(it, "UTF-8"),
            )
          }
          call.respondRedirect(result.destinationUrl, permanent = false)
        }
        is RedirectResult.NotFound -> {
          call.respond(HttpStatusCode.NotFound, ApiResponse(Status.ERROR, result.message, null))
        }
      }
    }
  }
}

fun routeObjectToModel(it: Members.DMSMember): org.dallasmakerspace.models.DMSMember {
  return org.dallasmakerspace.models.DMSMember(
      -1,
      it.username,
      discourseUsername = it.discourseUsername,
      discourseAvatarUrl = it.discourseAvatarUrl,
      discordUserId = it.discordUserId,
      discordUsername = it.discordUsername,
      discordAvatarUrl = it.discordAvatarUrl,
      discordWebhookId = it.discordWebhookId,
  )
}
