package org.dallasmakerspace.agenttools

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.GroupService
import org.dallasmakerspace.models.DMSGroup
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class AuthorizationGroupDirectoryTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val hour = 60 * 60 * 1000L

  private fun group(name: String) =
      DMSGroup(name = name, distinguishedName = "CN=$name,OU=Test", objectGuid = null)

  @Test
  fun `group names are cached for an hour and then refreshed`() = runBlocking<Unit> {
    val service =
        mock<GroupService> {
          onBlocking { getAllGroups() } doReturn listOf(group("A"), group("A"), group("B"))
        }
    val directory = AuthorizationGroupDirectory(service, loggerFactory)
    var now = 1_000L
    directory.nowMs = { now }

    assertEquals(listOf("A", "B"), directory.groupNames())
    now += hour - 1_000L
    directory.groupNames()
    verify(service, times(1)).getAllGroups()

    now += 2_000L
    directory.groupNames()
    verify(service, times(2)).getAllGroups()
  }

  @Test
  fun `a first failure returns null and backs off`() = runBlocking<Unit> {
    val failing = mock<GroupService> { onBlocking { getAllGroups() } doThrow IllegalStateException("down") }
    val directory = AuthorizationGroupDirectory(failing, loggerFactory)
    directory.nowMs = { 10_000L }

    assertNull(directory.groupNames())
    assertNull(directory.groupNames())
    verify(failing, times(1)).getAllGroups()
  }

  @Test
  fun `a failed refresh keeps the last list`() = runBlocking<Unit> {
    val service = mock<GroupService>()
    whenever(service.getAllGroups())
        .thenReturn(listOf(group("A")))
        .thenThrow(IllegalStateException("down"))
    val directory = AuthorizationGroupDirectory(service, loggerFactory)
    var now = 10_000L
    directory.nowMs = { now }

    assertEquals(listOf("A"), directory.groupNames())
    now += 2 * hour
    assertEquals(listOf("A"), directory.groupNames())
  }
}
