package org.dallasmakerspace.webhook.handlers

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.slf4j.LoggerFactory

/**
 * Handler for group history events from Active Directory. Processes events related to users being
 * added to or removed from groups.
 */
class GroupHistoryWebhook
@Inject
constructor(
    private val activeDirectoryService: ActiveDirectoryService,
    private val groupHistoryRepository: GroupHistoryRepository,
    private val memberService: MemberService
) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(GroupHistoryWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "groups-audit"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    return try {
      // Parse the webhook payload
      val event = json.decodeFromString<GroupHistoryEvent>(body)

      // Extract member DN and get the username from Active Directory
      val memberDn = event.Member.Name
      // LDAP is blocking - scoped to Dispatchers.IO like other AD calls on this shared route.
      val adUsers = withContext(Dispatchers.IO) { activeDirectoryService.getMembersByDnList(listOf(memberDn)) }

      if (adUsers.isEmpty()) {
        return WebhookResult(false, "Could not find user with DN: $memberDn")
      }

      val memberUsername = adUsers[0].sAMAccountName
      val actorUsername = event.Subject.UserName
      val groupName = event.TargetGroup.Name

      // Get actor and member IDs from the memberService
      val actorProfile =
          try {
            memberService.getMemberByUsername(actorUsername)
          } catch (e: Exception) {
            logger.error("Actor not found: $actorUsername", e)
            return WebhookResult(false, "Actor not found: $actorUsername")
          }

      val memberProfile =
          try {
            memberService.getMemberByUsername(memberUsername)
          } catch (e: Exception) {
            logger.error("Member not found: $memberUsername", e)
            return WebhookResult(false, "Member not found: $memberUsername")
          }

      // Get or create the group record
      val groupId = groupHistoryRepository.getOrCreateGroup(groupName)

      // Parse the event timestamp early for the deduplication window
      val eventTimeParsed =
          LocalDateTime.parse(
              event.TimeCreated.substring(0, 19).replace('T', ' '),
              DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

      val deduplicationWindowMinutes = 15L
      val windowStart = eventTimeParsed.minusMinutes(deduplicationWindowMinutes)
      val windowEnd = eventTimeParsed.plusMinutes(2)

      val actionTypeParsed = ActionType.fromEventId(event.EventId)

      if (groupHistoryRepository.hasPortalInitiatedRecord(
          memberProfile.id, groupId, actionTypeParsed, windowStart, windowEnd)) {
        logger.info(
            "Suppressing AD webhook for $memberUsername→$groupName: matched portal-initiated record within ${deduplicationWindowMinutes}min window")
        return WebhookResult(true, "Deduplicated: matched portal-initiated record")
      }

      // Save to database with IDs instead of usernames
      groupHistoryRepository.insertGroupHistory(
          actorProfile.id, memberProfile.id, groupId, actionTypeParsed, eventTimeParsed)

      WebhookResult(true, "Successfully processed group history event")
    } catch (e: Exception) {
      logger.error("Error processing group history webhook", e)
      WebhookResult(false, "Error processing webhook: ${e.message}")
    }
  }
}

@Serializable
data class GroupHistoryEvent(
    val TimeCreated: String,
    val EventId: String,
    val Subject: Subject,
    val TargetGroup: TargetGroup,
    val Member: Member,
    val RecordId: String,
    val Computer: String
)

@Serializable data class Subject(val Domain: String, val UserName: String)

@Serializable data class TargetGroup(val Name: String, val Domain: String)

@Serializable data class Member(val Name: String)
