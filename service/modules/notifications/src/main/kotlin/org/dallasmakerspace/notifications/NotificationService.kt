package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.NotifierRegistry
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.RenderedNotification
import org.dallasmakerspace.notifications.templates.TemplateRegistry

/** Outcome of [NotificationService.createForMembers]. */
data class FanOutResult(val created: Int, val duplicates: Int, val failed: Int)

/**
 * Single entry point for creating member notifications, tracking their read state, and managing
 * member preferences. Immediate notifications are held briefly (`worker.hold-seconds`) so a burst
 * - e.g. an organizer posting several sessions at once - goes out as one email. Resolution order
 * at create time: template/topic/channel validation, dedupe,
 * suppression list, active-membership check (unless the topic allows inactive members), preferences
 * (enabled + aggregation override), then template rendering.
 */
@Singleton
@Suppress("TooManyFunctions") // Single entry point for create/preferences/tracking; kept together for cohesion.
class NotificationService
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val templateRegistry: TemplateRegistry,
    private val notifierRegistry: NotifierRegistry,
    private val activeDirectoryService: IActiveDirectoryService,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  /** How long an immediate notification waits so others arriving meanwhile share its email. */
  private val holdSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.worker.hold-seconds")

  private val utcZone = ZoneId.of("UTC")

  /**
   * Effective preference for every topic and every channel that has a notifier (stored rows merged
   * with defaults).
   */
  suspend fun getPreferences(memberUsername: String): List<MemberPreferenceView> {
    val stored = repository.getPreferences(memberUsername).associateBy { it.topic to it.channel }
    return templateRegistry.topics.flatMap { topic ->
      notifierRegistry.supportedChannels.map { channel ->
        val preference = stored[topic to channel]
        MemberPreferenceView(
            memberUsername = memberUsername,
            topic = topic,
            channel = channel,
            enabled = preference?.enabled ?: true,
            aggregationOverride = preference?.aggregation,
            effectiveAggregation = effectiveAggregation(channel, preference?.aggregation),
            digestSupported = notifierRegistry.supportsDigest(channel),
        )
      }
    }
  }

  /** Persists a member's preference for [topic]/[channel] and returns the effective view. */
  suspend fun setPreference(
      memberUsername: String,
      topic: NotificationTopic,
      channel: Channel,
      enabled: Boolean,
      aggregationOverride: Aggregation?,
  ): MemberPreferenceView {
    requireSupportedChannel(channel)
    require(
        aggregationOverride == null ||
            aggregationOverride == Aggregation.IMMEDIATE ||
            notifierRegistry.supportsDigest(channel)) {
          "Channel $channel does not support digests"
        }
    repository.setPreference(
        NotificationPreference(
            memberUsername = memberUsername,
            topic = topic,
            channel = channel,
            enabled = enabled,
            aggregation = aggregationOverride,
        ))
    return MemberPreferenceView(
        memberUsername = memberUsername,
        topic = topic,
        channel = channel,
        enabled = enabled,
        aggregationOverride = aggregationOverride,
        effectiveAggregation = effectiveAggregation(channel, aggregationOverride),
        digestSupported = notifierRegistry.supportsDigest(channel),
    )
  }

  /**
   * Resolves a topic by its persisted/serialized key; null if unknown. Keeps the routing layer
   * decoupled from [TemplateRegistry] directly, consistent with existing layering (routes only
   * ever talk to this service).
   */
  fun findTopic(key: String): NotificationTopic? = templateRegistry.findTopic(key)

  /** Most recent [limit] notifications, optionally filtered by member/status (newest first). */
  suspend fun list(
      limit: Int,
      memberUsername: String? = null,
      status: NotificationStatus? = null,
  ): List<Notification> = repository.list(limit, memberUsername, status)

  /** The member's active suppression (e.g. their address hard-bounced), if any. */
  suspend fun getSuppression(memberUsername: String): NotificationSuppression? =
      repository.getSuppression(memberUsername)

  /**
   * Creates a notification for [request.memberUsername]. Returns the newly created notification, or
   * the pre-existing one when [CreateNotificationRequest.dedupeKey] matches an earlier create.
   *
   * Notifications that must not be sent are still stored (for the unified communication view) with
   * status [NotificationStatus.SUPPRESSED] or [NotificationStatus.SKIPPED].
   */
  suspend fun create(request: CreateNotificationRequest): Notification {
    require(request.memberUsername.isNotBlank()) { "memberUsername is required" }
    validate(request.topic, request.templateName, request.channel)
    request.dedupeKey?.let { key ->
      repository.getByDedupeKey(key)?.let { existing ->
        log.info("Notification with dedupe_key '$key' already exists, returning existing")
        return existing
      }
    }
    val active =
        request.topic.allowInactiveMembers || activeMembers(listOf(request.memberUsername)).isNotEmpty()
    val rendered = templateRegistry.render(request.templateName, request.payload)
    return insert(request, rendered, active)
  }

  /**
   * Fans one rendered notification out to many members (e.g. every follower of an organizer). The
   * template is rendered once and membership is checked with a single directory lookup; a failure
   * for one member is logged and doesn't stop the rest. [dedupeKey] derives each member's key.
   */
  @Suppress("LongParameterList")
  suspend fun createForMembers(
      memberUsernames: Collection<String>,
      topic: NotificationTopic,
      templateName: String,
      payload: String?,
      channel: Channel = Channel.EMAIL,
      dedupeKey: (String) -> String? = { null },
  ): FanOutResult {
    validate(topic, templateName, channel)
    val recipients = memberUsernames.filter { it.isNotBlank() }.distinct()
    if (recipients.isEmpty()) return FanOutResult(0, 0, 0)
    val rendered = templateRegistry.render(templateName, payload)
    val active = if (topic.allowInactiveMembers) recipients.toSet() else activeMembers(recipients)

    var created = 0
    var duplicates = 0
    var failed = 0
    for (username in recipients) {
      val outcome =
          createOne(
              username,
              topic,
              templateName,
              payload,
              channel,
              dedupeKey(username),
              rendered,
              memberActive = username in active)
      when (outcome) {
        FanOutOutcome.CREATED -> created++
        FanOutOutcome.DUPLICATE -> duplicates++
        FanOutOutcome.FAILED -> failed++
      }
    }
    return FanOutResult(created, duplicates, failed)
  }

  private enum class FanOutOutcome {
    CREATED,
    DUPLICATE,
    FAILED,
  }

  @Suppress("LongParameterList", "TooGenericExceptionCaught")
  private suspend fun createOne(
      username: String,
      topic: NotificationTopic,
      templateName: String,
      payload: String?,
      channel: Channel,
      dedupeKey: String?,
      rendered: RenderedNotification,
      memberActive: Boolean,
  ): FanOutOutcome =
      try {
        if (dedupeKey?.let { repository.getByDedupeKey(it) } != null) {
          FanOutOutcome.DUPLICATE
        } else {
          insert(
              CreateNotificationRequest(username, topic, templateName, payload, channel, dedupeKey),
              rendered,
              memberActive)
          FanOutOutcome.CREATED
        }
      } catch (e: Exception) {
        log.error("Failed to create $templateName notification for $username", e)
        FanOutOutcome.FAILED
      }

  private fun validate(topic: NotificationTopic, templateName: String, channel: Channel) {
    val template =
        templateRegistry.find(templateName)
            ?: throw IllegalArgumentException("Unknown notification template: $templateName")
    require(template.topic == topic) {
      "Template '$templateName' belongs to topic ${template.topic.key}, not ${topic.key}"
    }
    requireSupportedChannel(channel)
  }

  private fun requireSupportedChannel(channel: Channel) =
      require(notifierRegistry.find(channel) != null) {
        "Channel $channel has no notifier configured"
      }

  private suspend fun insert(
      request: CreateNotificationRequest,
      rendered: RenderedNotification,
      memberActive: Boolean,
  ): Notification {
    val notification = buildNotification(request, rendered, memberActive)
    val created = repository.create(notification)
    log.info(
        "Created ${notification.status} notification ${created.id} for ${request.memberUsername} " +
            "topic=${request.topic.key} channel=${request.channel}")
    return created
  }

  /** Resolves suppression/preferences/aggregation for [request] and builds the row to store. */
  private suspend fun buildNotification(
      request: CreateNotificationRequest,
      rendered: RenderedNotification,
      memberActive: Boolean,
  ): Notification {
    val now = LocalDateTime.now(utcZone)
    val suppression = repository.getSuppression(request.memberUsername)
    val preference =
        repository.getPreference(request.memberUsername, request.topic, request.channel)
    val enabled = preference?.enabled != false
    val aggregation = effectiveAggregation(request.channel, preference?.aggregation)

    val (status, reason) =
        when {
          suppression != null ->
              NotificationStatus.SUPPRESSED to "Member is suppressed (${suppression.reason})"
          !memberActive ->
              NotificationStatus.SKIPPED to
                  "Member is inactive; topic ${request.topic.key} does not notify inactive members"
          !enabled -> NotificationStatus.SKIPPED to "Disabled in notification preferences"
          aggregation != Aggregation.IMMEDIATE -> NotificationStatus.IN_DIGEST to null
          else -> NotificationStatus.QUEUED to null
        }

    return Notification(
        id = 0,
        memberUsername = request.memberUsername,
        topic = request.topic,
        channel = request.channel,
        aggregation = aggregation,
        templateName = request.templateName,
        payload = request.payload,
        subject = rendered.subject,
        body = rendered.html,
        status = status,
        attempts = 0,
        nextAttemptAt =
            when (status) {
              NotificationStatus.IN_DIGEST -> DigestSchedule.nextDelivery(aggregation, now)
              NotificationStatus.QUEUED -> now.plusSeconds(holdSeconds)
              else -> null
            },
        dedupeKey = request.dedupeKey,
        providerId = null,
        lastError = reason,
        sentAt = null,
        readAt = null,
        createdAt = now,
    )
  }

  /** Member override, else the channel default; digests fall back to immediate if unsupported. */
  private fun effectiveAggregation(channel: Channel, override: Aggregation?): Aggregation {
    val requested = override ?: channel.defaultAggregation
    return if (requested == Aggregation.IMMEDIATE || notifierRegistry.supportsDigest(channel)) {
      requested
    } else {
      Aggregation.IMMEDIATE
    }
  }

  /** Marks [id] read, but only if the notification belongs to [memberUsername]. */
  suspend fun markRead(id: Long, memberUsername: String): Boolean =
      repository.markRead(id, memberUsername, LocalDateTime.now(utcZone))

  /** Delivered notifications [memberUsername] hasn't seen in the portal inbox yet. */
  suspend fun countUnread(memberUsername: String): Int = repository.countUnread(memberUsername)

  /** Marks all of [memberUsername]'s delivered notifications read (they viewed the inbox). */
  suspend fun markAllRead(memberUsername: String): Int =
      repository.markAllRead(memberUsername, LocalDateTime.now(utcZone))

  /**
   * The subset of [usernames] with an enabled Active Directory account (membership not lapsed), in
   * one directory lookup. Unknown usernames are treated as inactive (fail closed). LDAP is
   * blocking, so this is scoped to [Dispatchers.IO] - creates are reachable from request-handling
   * routes (the notifications API and webhooks), not just background work.
   */
  private suspend fun activeMembers(usernames: List<String>): Set<String> =
      withContext(Dispatchers.IO) {
        activeDirectoryService
            .getMembersByUsernameList(usernames)
            .filterValues { it?.enabled == true }
            .keys
      }

  /**
   * Confirms provider-side delivery for every notification sent in the transmission [providerId]
   * (one row, or every row of a digest). Driven by the `delivery` event on the provider webhook.
   */
  suspend fun recordDelivery(providerId: String, deliveredAt: LocalDateTime) {
    val notifications = repository.getAllByProviderId(providerId)
    if (notifications.isEmpty()) {
      log.info("Delivery event for unknown provider id '$providerId', ignoring")
      return
    }
    notifications
        .filter { it.status == NotificationStatus.SENT || it.status == NotificationStatus.SENDING }
        .forEach { repository.markSent(it.id, providerId, deliveredAt) }
  }

  /**
   * Marks every notification in the transmission [providerId] permanently failed and suppresses
   * future notifications to the member. Driven by the `bounce` event on the provider webhook.
   */
  suspend fun recordBounce(providerId: String, email: String?, reason: String) {
    val notifications = repository.getAllByProviderId(providerId)
    if (notifications.isEmpty()) {
      log.info("Bounce event for unknown provider id '$providerId', ignoring")
      return
    }
    notifications.forEach { repository.markDeadLetter(it.id, "Email bounced: $reason") }
    notifications
        .map { it.memberUsername }
        .distinct()
        .forEach { username ->
          repository.insertSuppression(username, email, "BOUNCE")
          log.warn("Member $username suppressed after bounce: $reason")
        }
  }

  /**
   * Records an `open`/`click` engagement event for the unified communication log. Informational
   * only - does not change notification status.
   */
  suspend fun recordEngagement(providerId: String, eventType: String) {
    val notifications = repository.getAllByProviderId(providerId)
    if (notifications.isEmpty()) {
      log.info("$eventType event for unknown provider id '$providerId', ignoring")
      return
    }
    log.info(
        "Notification(s) ${notifications.map { it.id }} $eventType by " +
            notifications.first().memberUsername)
  }
}
