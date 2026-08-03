package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.TemplateRegistry

/**
 * Single entry point for creating member notifications, tracking their read state, and managing
 * member preferences. Resolution order at create time: template/topic validation, dedupe,
 * suppression list, active-membership check (unless the topic allows inactive members), preferences
 * (enabled + aggregation override), then template rendering.
 */
@Singleton
class NotificationService
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val templateRegistry: TemplateRegistry,
    private val activeDirectoryService: IActiveDirectoryService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /**
   * Effective preference for every topic/channel combination (stored rows merged with defaults).
   */
  suspend fun getPreferences(memberUsername: String): List<MemberPreferenceView> {
    val stored = repository.getPreferences(memberUsername).associateBy { it.topic to it.channel }
    return NotificationTopic.entries.flatMap { topic ->
      Channel.entries.map { channel ->
        val preference = stored[topic to channel]
        MemberPreferenceView(
            memberUsername = memberUsername,
            topic = topic,
            channel = channel,
            enabled = preference?.enabled ?: true,
            aggregationOverride = preference?.aggregation,
            effectiveAggregation = preference?.aggregation ?: channel.defaultAggregation,
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
        effectiveAggregation = aggregationOverride ?: channel.defaultAggregation,
    )
  }

  /** Most recent [limit] notifications, optionally filtered by member/status (newest first). */
  suspend fun list(
      limit: Int,
      memberUsername: String? = null,
      status: NotificationStatus? = null,
  ): List<Notification> = repository.list(limit, memberUsername, status)

  /**
   * Creates a notification for [request.memberUsername]. Returns the newly created notification, or
   * the pre-existing one when [CreateNotificationRequest.dedupeKey] matches an earlier create.
   *
   * Notifications that must not be sent are still stored (for the unified communication view) with
   * status [NotificationStatus.SUPPRESSED] or [NotificationStatus.SKIPPED].
   */
  suspend fun create(request: CreateNotificationRequest): Notification {
    require(request.memberUsername.isNotBlank()) { "memberUsername is required" }
    val template =
        templateRegistry.find(request.templateName)
            ?: throw IllegalArgumentException(
                "Unknown notification template: ${request.templateName}")
    require(template.topic == request.topic) {
      "Template '${request.templateName}' belongs to topic ${template.topic}, not ${request.topic}"
    }

    request.dedupeKey?.let { key ->
      repository.getByDedupeKey(key)?.let { existing ->
        log.info("Notification with dedupe_key '$key' already exists, returning existing")
        return existing
      }
    }

    val notification = buildNotification(request)
    val created = repository.create(notification)
    log.info(
        "Created ${notification.status} notification ${created.id} for ${request.memberUsername} " +
            "topic=${request.topic} channel=${request.channel}")
    return created
  }

  /**
   * Resolves suppression/preferences/aggregation for [request] and builds the row to store (not yet
   * persisted).
   */
  private suspend fun buildNotification(request: CreateNotificationRequest): Notification {
    val now = LocalDateTime.now(utcZone)
    val suppression = repository.getSuppression(request.memberUsername)
    val memberInactive =
        !request.topic.allowInactiveMembers && !isActiveMember(request.memberUsername)
    val preference =
        repository.getPreference(request.memberUsername, request.topic, request.channel)
    val enabled = preference?.enabled != false
    val aggregation = preference?.aggregation ?: request.channel.defaultAggregation

    val rendered = templateRegistry.render(request.templateName, request.payload)
    val status =
        when {
          suppression != null -> NotificationStatus.SUPPRESSED
          memberInactive -> NotificationStatus.SKIPPED
          !enabled -> NotificationStatus.SKIPPED
          aggregation != Aggregation.IMMEDIATE -> NotificationStatus.IN_DIGEST
          else -> NotificationStatus.QUEUED
        }
    val lastError =
        when {
          suppression != null -> "Member is suppressed (${suppression.reason})"
          memberInactive ->
              "Member is inactive; topic ${request.topic} does not notify inactive members"
          !enabled -> "Disabled in notification preferences"
          else -> null
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
        nextAttemptAt = null,
        dedupeKey = request.dedupeKey,
        providerId = null,
        lastError = lastError,
        sentAt = null,
        readAt = null,
        createdAt = now,
    )
  }

  /** Marks [id] read, but only if the notification belongs to [memberUsername]. */
  suspend fun markRead(id: Long, memberUsername: String): Boolean =
      repository.markRead(id, memberUsername, LocalDateTime.now(utcZone))

  /**
   * Whether [memberUsername] currently has an enabled Active Directory account (membership not
   * lapsed). Unknown usernames are treated as inactive (fail closed). LDAP is blocking, so this is
   * scoped to [Dispatchers.IO] - `create()` is reachable from shared request-handling routes (the
   * notifications API and webhook), not just the background worker.
   */
  private suspend fun isActiveMember(memberUsername: String): Boolean =
      withContext(Dispatchers.IO) {
        activeDirectoryService
            .getMembersByUsernameList(listOf(memberUsername))[memberUsername]
            ?.enabled == true
      }

  /**
   * Confirms provider-side delivery for the notification matching [providerId] (SparkPost
   * `transmission_id`). Driven by the `delivery` event on the provider event webhook.
   */
  suspend fun recordDelivery(providerId: String, deliveredAt: LocalDateTime) {
    val notification = repository.getByProviderId(providerId)
    if (notification == null) {
      log.info("Delivery event for unknown provider id '$providerId', ignoring")
      return
    }
    repository.markSent(notification.id, providerId, deliveredAt)
  }

  /**
   * Marks the notification matching [providerId] permanently failed and suppresses future
   * notifications to the member. Driven by the `bounce` event on the provider event webhook.
   */
  suspend fun recordBounce(providerId: String, email: String?, reason: String) {
    val notification = repository.getByProviderId(providerId)
    if (notification == null) {
      log.info("Bounce event for unknown provider id '$providerId', ignoring")
      return
    }
    repository.markDeadLetter(notification.id, "SparkPost bounce: $reason")
    repository.insertSuppression(notification.memberUsername, email, "BOUNCE")
    log.warn("Member ${notification.memberUsername} suppressed after bounce: $reason")
  }

  /**
   * Records an `open`/`click` engagement event for the unified communication log. Informational
   * only - does not change notification status.
   */
  suspend fun recordEngagement(providerId: String, eventType: String) {
    val notification = repository.getByProviderId(providerId)
    if (notification == null) {
      log.info("$eventType event for unknown provider id '$providerId', ignoring")
      return
    }
    log.info("Notification ${notification.id} $eventType by ${notification.memberUsername}")
  }
}
