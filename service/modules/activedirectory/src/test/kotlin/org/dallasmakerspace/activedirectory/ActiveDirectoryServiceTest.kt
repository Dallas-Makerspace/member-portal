package org.dallasmakerspace.activedirectory

import junit.framework.TestCase.assertEquals
import kotlin.test.Ignore
import kotlin.test.Test
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock

@RunWith(JUnit4::class)
class ActiveDirectoryServiceTest {

  private val loggerFactory: LoggerFactory = mock()

  @Ignore("Integration test - needs to be run against a real AD manually for now")
  @Test
  fun removeUsersFromGroup() {
    val activeDirectoryClient = ActiveDirectoryClient(AppConfig(), loggerFactory)
    val activeDirectoryService = ActiveDirectoryService(activeDirectoryClient)
    val dmsUsernames = listOf("test")
    val group = "Voting Members - Test"
    activeDirectoryService.removeUsersFromGroup(dmsUsernames, group)
    val adGroup = activeDirectoryService.getGroup(group)
    assertEquals(0, adGroup.members.size)
  }

  @Ignore("Integration test - needs to be run against a real AD manually for now")
  @Test
  fun addUsersToGroup() {
    val activeDirectoryClient = ActiveDirectoryClient(AppConfig(), loggerFactory)
    val activeDirectoryService = ActiveDirectoryService(activeDirectoryClient)
    val dmsUsernames = listOf("test")
    val group = "Voting Members - Test"
    activeDirectoryService.addUsersToGroup(dmsUsernames, group)
    val adGroup = activeDirectoryService.getGroup(group)
    assertEquals(1, adGroup.members.size)
  }
}
