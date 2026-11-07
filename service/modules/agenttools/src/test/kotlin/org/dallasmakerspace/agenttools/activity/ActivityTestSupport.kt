package org.dallasmakerspace.agenttools.activity

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.AgentAccess
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.doorcontroller.DoorSwipe
import org.dallasmakerspace.models.Tool
import org.dallasmakerspace.models.Tools
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

val LIFT: Tool = Tools.ALL.first { it.interlockTag == "auto-lift-1" }

fun at(hhmm: String, day: String = "2026-11-05"): Instant =
    java.time.LocalDateTime.parse("${day}T$hhmm").atZone(ActivityWindow.CENTRAL).toInstant()

fun swipe(hhmm: String, username: String?, granted: Boolean = true, tail: String = "1234") =
    DoorSwipe(at(hhmm), "Garage-In", granted, username, tail)

fun session(
    start: String,
    end: String?,
    username: String?,
    endedBy: String? = "MANUAL_BADGE_OUT",
    authorized: Boolean = true,
    tail: String = "1234",
) = ToolSession(LIFT, at(start), end?.let(::at), username, tail, authorized, endedBy)

/** Access for [username] given the AD groups [groups] and the configured all-areas groups. */
fun activityAccess(
    groups: List<String>,
    configured: String = "Infrastructure,President",
    enabled: Boolean = true,
): ActivityAccess {
  val config =
      mock<ConfigOverrideService> {
        onBlocking { get(ConfigRegistry.AGENT_ACTIVITY_TOOLS_AD_GROUPS, false) } doReturn configured
        onBlocking { isFeatureEnabledFor(eq(ConfigRegistry.AGENT_ACTIVITY_TOOLS_ENABLED), any(), any()) } doReturn enabled
      }
  val agentAccess = mock<AgentAccess> { onBlocking { groupsOf(any()) } doReturn groups }
  return ActivityAccess(config, agentAccess)
}

class FakeSwipes(
    private val rows: List<DoorSwipe> = emptyList(),
    private val latest: Instant? = Instant.now(),
) : SwipeSearch {
  var lastDoors: Collection<String>? = null
  var lastUsernames: Set<String>? = null
  var lastDeniedOnly: Boolean? = null

  override suspend fun search(
      doors: Collection<String>,
      from: Instant,
      to: Instant,
      usernames: Set<String>?,
      deniedOnly: Boolean,
      limit: Int,
  ): List<DoorSwipe> {
    lastDoors = doors
    lastUsernames = usernames
    lastDeniedOnly = deniedOnly
    return rows.filter { it.time in from..to && (usernames == null || it.username in usernames) }.take(limit)
  }

  override suspend fun latestEvent(doors: Collection<String>): Instant? = latest
}

class FakeSessions(private val rows: List<ToolSession> = emptyList()) : SessionSearch {
  var lastTools: List<Tool>? = null

  override suspend fun search(tools: List<Tool>, from: Instant, to: Instant): List<ToolSession> {
    lastTools = tools
    return rows.filter { it.start in from..to }
  }
}

fun names(vararg pairs: Pair<String, String>): MemberNames {
  val directory = mock<IActiveDirectoryService>()
  val users =
      pairs.associate { (username, display) ->
        username to ADUser(username, "$username@example.test", null, null, null, null, display, "g", null, true, emptyList())
      }
  org.mockito.kotlin.whenever(directory.getMembersByUsernameList(any())).thenReturn(users)
  return MemberNames(directory)
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
