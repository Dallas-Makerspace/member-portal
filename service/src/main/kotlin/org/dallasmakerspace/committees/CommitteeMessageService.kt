package org.dallasmakerspace.committees

import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.discourse.DiscourseApiException
import org.dallasmakerspace.discourse.DiscourseTopicNotFoundException
import org.dallasmakerspace.discourse.DiscourseUserNotFoundException
import org.dallasmakerspace.discourse.IDiscourseApiClient
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.db.ProfileResolver
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeMessagesRepository
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.db.Reservation
import org.dallasmakerspace.notifications.db.SendLimits
import org.dallasmakerspace.notifications.templates.CommitteeMessageData
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.dallasmakerspace.shortlinks.ShortLinksService
import org.jetbrains.exposed.dao.id.EntityID

enum class CommitteeMessageError {
  UNKNOWN_COMMITTEE,
  NOT_ENABLED,
  NOT_CHAIR,
  INVALID,
  TALK_NOT_LINKED,
  BAD_THREAD,
  NO_CATEGORY,
  RATE_LIMITED,
  UNAVAILABLE,
}

class CommitteeMessageException(
    val error: CommitteeMessageError,
    message: String,
    /** Set for [CommitteeMessageError.RATE_LIMITED]: when sending is allowed again (UTC). */
    val nextAllowedAt: LocalDateTime? = null,
) : Exception(message)

/** [talkUrl] points at an existing Talk thread; otherwise [createThread] asks for a new one. */
data class CommitteeMessageRequest(
    val message: String = "",
    val talkUrl: String? = null,
    val createThread: Boolean = false,
)

data class CommitteeMessageResult(
    val id: Int,
    val shortUrl: String,
    val talkUrl: String,
    val recipients: Int,
    val threadCreated: Boolean,
)

/** What the committee page needs to offer (or hide) the chair's compose button. */
data class CommitteeMessageEligibility(
    /** Whether the viewer is a chair of the committee and the feature is on for them. */
    val canMessage: Boolean = false,
    val talkLinked: Boolean = false,
    val canCreateThread: Boolean = false,
    val followerCount: Int = 0,
    val maxLength: Int = MAX_MESSAGE_LENGTH,
    val perDayLimit: Int = LIMITS.perDay,
    val perWeekLimit: Int = LIMITS.perWeek,
    val sentToday: Int = 0,
    val sentThisWeek: Int = 0,
    /** ISO-8601 UTC instant, set while a limit is reached. */
    val nextAllowedAt: String? = null,
)

internal const val MAX_MESSAGE_LENGTH = 120
internal val LIMITS = SendLimits(perDay = 2, perWeek = 3)

/**
 * A committee chair's short message to the committee's followers, pointing at a Talk thread
 * through a portal short link. Infrastructure group members may also message any committee's
 * followers. The sender is checked here (never trusted from the UI), must have a linked Talk
 * account, and is held to [LIMITS] per committee.
 */
@Singleton
class CommitteeMessageService
@Inject
constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val featureFlagService: FeatureFlagService,
    private val configOverrideService: ConfigOverrideService,
    private val discourse: IDiscourseApiClient,
    private val shortLinks: ShortLinksService,
    private val followers: CommitteeSubscriptionsRepository,
    private val messages: CommitteeMessagesRepository,
    private val notificationService: NotificationService,
    private val memberService: MemberService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun eligibility(actor: String, committeeId: Int): CommitteeMessageEligibility {
    val committee = Committees.findById(committeeId) ?: return CommitteeMessageEligibility()
    if (!isAllowed(actor, committee)) return CommitteeMessageEligibility()
    val now = CommitteeMessagesRepository.nowUtc()
    val sends = messages.recentSends(committee.id, now)
    val next = CommitteeMessagesRepository.nextAllowedAt(sends, now, LIMITS)
    return CommitteeMessageEligibility(
        canMessage = true,
        talkLinked = discourseUsername(actor) != null,
        canCreateThread = categoryFor(committee) != null,
        followerCount = followers.getFollowers(committee.id).count { it != actor },
        sentToday = sends.count { it >= now.minusDays(1) },
        sentThisWeek = sends.size,
        nextAllowedAt = next?.toInstant(ZoneOffset.UTC)?.toString())
  }

  @Suppress("CyclomaticComplexMethod", "LongMethod")
  suspend fun send(
      actor: String,
      committeeId: Int,
      request: CommitteeMessageRequest,
  ): CommitteeMessageResult {
    val committee =
        Committees.findById(committeeId)?.takeIf { it.groupPrefixes.isNotEmpty() }
            ?: throw CommitteeMessageException(
                CommitteeMessageError.UNKNOWN_COMMITTEE, "Unknown committee")
    if (!featureFlagService.isEnabledForUsername(
        ConfigRegistry.COMMITTEE_BROADCAST_ENABLED, actor)) {
      throw CommitteeMessageException(
          CommitteeMessageError.NOT_ENABLED, "Committee messages aren't available to you yet")
    }
    if (!isChairOrInfra(actor, committee)) {
      throw CommitteeMessageException(
          CommitteeMessageError.NOT_CHAIR,
          "Only a chair of ${committee.name} or the Infrastructure team can send messages")
    }

    val text = validateMessage(request.message)
    val talkUsername =
        discourseUsername(actor)
            ?: throw CommitteeMessageException(
                CommitteeMessageError.TALK_NOT_LINKED,
                "Link your Talk account on your profile before sending a message")
    verifyTalkAccount(talkUsername)

    val recipients = followers.getFollowers(committee.id).filter { it != actor }
    if (recipients.isEmpty()) {
      throw CommitteeMessageException(
          CommitteeMessageError.INVALID, "No one follows ${committee.name} yet")
    }

    val existingTopicId =
        request.talkUrl?.takeIf { it.isNotBlank() }?.let { parseTalkTopicId(it) }
    if (existingTopicId == null && !request.createThread) {
      throw CommitteeMessageException(
          CommitteeMessageError.INVALID, "Give a Talk thread link, or ask us to create a thread")
    }
    val category = if (existingTopicId == null) categoryFor(committee) else null
    if (existingTopicId == null && category == null) {
      throw CommitteeMessageException(
          CommitteeMessageError.NO_CATEGORY,
          "Threads can't be created for ${committee.name} yet; paste a link to an existing one")
    }
    val existingTopic = existingTopicId?.let { checkTopic(it) }

    val messageId =
        when (val reservation = messages.reserve(committee.id, actor, text, LIMITS)) {
          is Reservation.Reserved -> reservation.id
          is Reservation.RateLimited ->
              throw CommitteeMessageException(
                  CommitteeMessageError.RATE_LIMITED,
                  "${committee.name} has reached its limit of ${LIMITS.perDay} messages a day and " +
                      "${LIMITS.perWeek} a week",
                  reservation.nextAllowedAt)
        }

    try {
      val talkUrl: String
      val threadCreated: Boolean
      if (existingTopic != null) {
        talkUrl = topicUrl(existingTopic.id, existingTopic.slug)
        threadCreated = false
        messages.attachThread(messageId, existingTopic.id, talkUrl, false)
      } else {
        val created = createThread(talkUsername, committee, text, category!!)
        talkUrl = topicUrl(created.topicId, created.topicSlug)
        threadCreated = true
        messages.attachThread(messageId, created.topicId, talkUrl, true)
      }

      val creatorId = suspendTransaction { ProfileResolver.findProfileId(actor) }
      val link =
          shortLinks
              .createShortLink(
                  null, null, talkUrl, "Message from ${committee.name} (#$messageId)", creatorId)
              .getOrElse {
                log.error("Short link for committee message $messageId failed", it)
                throw CommitteeMessageException(
                    CommitteeMessageError.UNAVAILABLE, "Couldn't create the short link, try again")
              }
      val shortUrl = "$SHORT_LINK_BASE/${link.slug}"

      val payload =
          json.encodeToString(
              CommitteeMessageData.serializer(),
              CommitteeMessageData(committee.name, text, shortUrl))
      val result =
          notificationService.createForMembers(
              memberUsernames = recipients,
              topic = CommitteeUpdatesTopic,
              templateName = "committee-message",
              payload = payload,
              channel = Channel.EMAIL,
              dedupeKey = { member -> "committee-msg-$messageId-$member" })
      messages.markSent(messageId, shortUrl, result.created)
      log.info(
          "Committee message $messageId from $actor to ${committee.name} followers: " +
              "recipients=${recipients.size}, notified=${result.created}, " +
              "duplicates=${result.duplicates}, failed=${result.failed}, threadCreated=$threadCreated")
      return CommitteeMessageResult(messageId, shortUrl, talkUrl, result.created, threadCreated)
    } catch (e: Exception) {
      messages.markFailed(messageId)
      throw e
    }
  }

  private suspend fun isAllowed(actor: String, committee: Committee): Boolean =
      committee.groupPrefixes.isNotEmpty() &&
          featureFlagService.isEnabledForUsername(
              ConfigRegistry.COMMITTEE_BROADCAST_ENABLED, actor) &&
          isChairOrInfra(actor, committee)

  private suspend fun isChairOrInfra(actor: String, committee: Committee): Boolean =
      isChair(actor, committee) || isInfra(actor)

  /** Fails closed (the member service already does). */
  private suspend fun isInfra(actor: String): Boolean =
      withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(actor) }

  /** Fails closed: any directory error means "not a chair". */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun isChair(actor: String, committee: Committee): Boolean {
    val chairGroup = committee.chairGroupName ?: return false
    return withContext(Dispatchers.IO) {
      try {
        activeDirectoryService.getMemberByUsername(actor).groups.any {
          it.cn.equals(chairGroup, ignoreCase = true)
        }
      } catch (e: Exception) {
        log.warn("Could not check chair status of $actor for ${committee.name}; denying", e)
        false
      }
    }
  }

  private suspend fun discourseUsername(actor: String): String? = suspendTransaction {
    ProfileDAO.findById(EntityID(actor, ProfileTable))?.discourseUsername?.takeIf {
      it.isNotBlank()
    }
  }

  /** The stored link can go stale (renamed or deleted in Talk), so confirm it still resolves. */
  private suspend fun verifyTalkAccount(talkUsername: String) {
    try {
      discourse.getUserProfile(talkUsername)
    } catch (e: DiscourseUserNotFoundException) {
      throw CommitteeMessageException(
          CommitteeMessageError.TALK_NOT_LINKED,
          "Your linked Talk account no longer exists; link it again on your profile")
    } catch (e: DiscourseApiException) {
      log.warn("Talk account check failed for $talkUsername", e)
      throw CommitteeMessageException(
          CommitteeMessageError.UNAVAILABLE, "Talk isn't responding, try again shortly")
    }
  }

  private suspend fun checkTopic(topicId: Int): TopicRef {
    val topic =
        try {
          discourse.getTopic(topicId)
        } catch (e: DiscourseTopicNotFoundException) {
          throw CommitteeMessageException(
              CommitteeMessageError.BAD_THREAD, "That Talk thread wasn't found or isn't public")
        } catch (e: DiscourseApiException) {
          log.warn("Talk topic check failed for $topicId", e)
          throw CommitteeMessageException(
              CommitteeMessageError.UNAVAILABLE, "Talk isn't responding, try again shortly")
        }
    if (topic.archetype != "regular" || !topic.visible) {
      throw CommitteeMessageException(
          CommitteeMessageError.BAD_THREAD, "That Talk thread isn't a public discussion")
    }
    return TopicRef(topicId, topic.slug)
  }

  private suspend fun createThread(
      talkUsername: String,
      committee: Committee,
      text: String,
      categoryId: Int,
  ) =
      try {
        discourse.createTopicAs(
            talkUsername,
            "${committee.name} committee: $text",
            "$text\n\nStarted from the DMS Member Portal for followers of the ${committee.name} " +
                "committee. Share details and questions below.",
            categoryId)
      } catch (e: DiscourseApiException) {
        log.warn("Creating a Talk thread for ${committee.name} failed", e)
        throw CommitteeMessageException(
            CommitteeMessageError.UNAVAILABLE,
            "Talk couldn't create the thread; try again, or paste a link to an existing one")
      }

  private suspend fun categoryFor(committee: Committee): Int? =
      parseCategories(configOverrideService.get(ConfigRegistry.COMMITTEE_TALK_CATEGORIES))[
          committee.id]

  private data class TopicRef(val id: Int, val slug: String)

  companion object {
    private const val TALK_BASE = "https://talk.dallasmakerspace.org"
    private const val SHORT_LINK_BASE = "https://dallas.ms"

    private val TALK_TOPIC_URL =
        Regex("^https://talk\\.dallasmakerspace\\.org/t/(?:(?!\\d+/)[^/\\s?#]+/)?(\\d+)(?:/\\d+)?/?$")

    // A scheme, "www.", or a bare domain with a common ending; the portal adds the only link.
    private val LINK_LIKE =
        Regex(
            "(?i)(://|\\bwww\\.|\\b[a-z0-9-]+\\.(com|org|net|io|ms|edu|gov|co|us|info|app|dev|me|ly|tv)\\b)")

    /** The chair's text, trimmed; throws [CommitteeMessageException] if it can't be sent. */
    fun validateMessage(raw: String): String {
      val text = raw.trim()
      fun invalid(why: String): Nothing =
          throw CommitteeMessageException(CommitteeMessageError.INVALID, why)
      if (text.isEmpty()) invalid("Write a message")
      if (text.codePointCount(0, text.length) > MAX_MESSAGE_LENGTH) {
        invalid("Keep the message to $MAX_MESSAGE_LENGTH characters or fewer")
      }
      if (text.codePoints().anyMatch { Character.isISOControl(it) }) {
        invalid("Use a single line of plain text")
      }
      if (LINK_LIKE.containsMatchIn(text)) {
        invalid("Leave links out of the message; the link to the Talk thread is added for you")
      }
      return text
    }

    /** The topic id of a Talk thread URL on the portal's Talk host; anything else is rejected. */
    fun parseTalkTopicId(url: String): Int =
        TALK_TOPIC_URL.matchEntire(url.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: throw CommitteeMessageException(
                CommitteeMessageError.BAD_THREAD,
                "That isn't a link to a Talk thread (https://talk.dallasmakerspace.org/t/...)")

    fun topicUrl(topicId: Int, slug: String): String =
        if (slug.isBlank()) "$TALK_BASE/t/$topicId" else "$TALK_BASE/t/$slug/$topicId"

    /** "1=7,2=18" -> {1: 7, 2: 18}; entries that aren't `id=id` are ignored. */
    fun parseCategories(raw: String): Map<Int, Int> =
        raw.split(",")
            .mapNotNull { pair ->
              val (committee, category) =
                  pair.split("=").map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
              (committee.toIntOrNull() ?: return@mapNotNull null) to
                  (category.toIntOrNull() ?: return@mapNotNull null)
            }
            .toMap()
  }
}
