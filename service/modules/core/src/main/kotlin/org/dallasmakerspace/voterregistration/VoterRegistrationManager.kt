package org.dallasmakerspace.voterregistration

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grace period given to a user by CTO + Treasurer. If the user does not maintain active membership
 * during this period, they will still be considered to be in good standing and thus eligible to
 * vote.
 */
data class UserGracePeriod(
    val whmcsUserId: Int,
    val username: String,
    val startDate: LocalDate,
    val endDate: LocalDate
)

@Singleton
class VoterRegistrationManager @Inject constructor() {
  fun getUserGracePeriods(): List<UserGracePeriod> {
    // Example of hardcoding grace periods:
    /*
      return listOf(
        // Grace period for user @abc from 2024-01-01 to 2024-01-02
        UserGracePeriod(
          /* whmcsUserId */ 1234,
          /* username */ "abc",
          LocalDate.of(/* year */ 2024, /* month */ 1, /* dayOfMonth */ 1),
          LocalDate.of(/* year */ 2024, /* month */ 1, /* dayOfMonth */ 2)
        ),
        // Grace period for user @xyz from 2024-01-28 to 2024-02-01
        UserGracePeriod(
          /* whmcsUserId */5678,
          /* username */ "xyz",
          LocalDate.of(/* year */ 2024, /* month */ 1, /* dayOfMonth */ 28),
          LocalDate.of(/* year */ 2024, /* month */ 2, /* dayOfMonth */ 1)
        ),
      )
    */
    return emptyList()
  }

  fun getUsernamesWithActiveGracePeriods(): Set<String> {
    return getUserGracePeriods()
        .filter { it.endDate.isAfter(LocalDate.now()) }
        .map { it.username }
        .toSet()
  }

  fun getVotingMembersGroupName() =
      if (IS_VOTER_REGISTRATION_TEST_MODE_ENABLED) VOTING_MEMBERS_GROUP_TEST
      else VOTING_MEMBERS_GROUP

  companion object {
    const val IS_VOTER_REGISTRATION_TEST_MODE_ENABLED = false
    const val MEMBER_IN_GOOD_STANDING_DAYS = 90L
    const val VOTING_MEMBERS_GROUP_TEST = "Voting Members - Test"
    const val VOTING_MEMBERS_GROUP = "Voting Members"
  }
}
