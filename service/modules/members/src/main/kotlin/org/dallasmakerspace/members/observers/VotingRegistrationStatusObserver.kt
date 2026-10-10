package org.dallasmakerspace.members.observers

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.voterregistration.VoterRegistrationManager

/** Observer that removes disabled members from the Voting Members group */
class VotingRegistrationStatusObserver
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val memberService: MemberService,
    private val voterRegistrationManager: VoterRegistrationManager,
) : IMemberPropChangeObserver {
  val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught", "ReturnCount")
  override suspend fun onMemberPropChange(
      propName: String,
      oldValue: Any?,
      newValue: Any?,
      affectedMembers: List<DMSMember>
  ): Boolean {
    if (propName != "enabled" && oldValue != true && newValue != false) {
      log.info(
          "VotingRegistrationStatusObserver.onMemberPropChange: ignoring the change: propName=$propName, " +
              "oldValue=$oldValue, newValue=$newValue, affectedMembers=$affectedMembers")
      return false
    }
    log.info(
        "VotingRegistrationStatusObserver.onMemberPropChange: process change: propName=$propName, " +
            "oldValue=$oldValue, newValue=$newValue, affectedMembers=$affectedMembers")
    try { // Validate that we have affected members who are member of Voting Members group
      val membersInGracePeriod = voterRegistrationManager.getUsernamesWithActiveGracePeriods()
      val votingMembers =
          affectedMembers
              // Take members who are in Voting Members group
              .filter { it.groups.any { group -> group.name == "Voting Members" } }
              // Take only primary accounts
              .filter { it.accountInfo?.isPrimaryAccount ?: false }
              // Take only members who are not in grace period
              .filter { membersInGracePeriod.contains(it.username).not() }
              // Get usernames
              .map { it.username }

      if (votingMembers.isEmpty()) {
        log.debug("No voting members found in affected members: {}", affectedMembers)
        // No need to process further
      } else if (propName == "enabled" && oldValue == true && newValue == false) {
        /** Handle member disabled */
        val votingMembersGroup = voterRegistrationManager.getVotingMembersGroupName()
        log.info("Removing users from $votingMembersGroup group: $votingMembers")
        memberService.removeMembersToGroup(votingMembers, votingMembersGroup)
      }
      return true
    } catch (e: Exception) {
      log.error("Failed to process member prop change: $e")
      return false
    }
  }
}
