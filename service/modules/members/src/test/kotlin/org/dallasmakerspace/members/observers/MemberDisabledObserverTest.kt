package org.dallasmakerspace.members.observers

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.TestUtils
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseService
import org.dallasmakerspace.members.ActivityLogService
import org.dallasmakerspace.models.ActivityLogEvent
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

@RunWith(JUnit4::class)
class MemberDisabledObserverTest {
  private val loggerFactory = LoggerFactory()
  private val discourseService: DiscourseService = mock()
  private val activityLogService: ActivityLogService = mock()
  private val tested =
      DiscourseMemberStatusObserver(loggerFactory, discourseService, activityLogService)

  @Test
  fun `should remove users from discourse group when member is disabled`() = runBlocking {
    // Act
    val result =
        tested.onMemberPropChange(
            "enabled", oldValue = true, newValue = false, affectedMembers = members)

    // Assert
    assert(result)
    verify(discourseService)
        .removeUserFromDmsMembersV2Group(listOf("discourseUser101", "discourseUser102"))
    verify(activityLogService)
        .insertBulkActivityLogEntry(
            listOf("user101", "user102"), ActivityLogEvent.REMOVE_FROM_DISCOURSE_MEMBERS_GROUP)
  }

  @Test
  fun `should add users to discourse group when member is enabled`() = runBlocking {
    // Act
    val result =
        tested.onMemberPropChange(
            "enabled", oldValue = false, newValue = true, affectedMembers = members)

    // Assert
    assert(result)
    verify(discourseService)
        .addUserToDmsMembersV2Group(listOf("discourseUser101", "discourseUser102"))
    verify(activityLogService)
        .insertBulkActivityLogEntry(
            listOf("user101", "user102"), ActivityLogEvent.ADD_TO_DISCOURSE_MEMBERS_GROUP)
  }

  @Test
  fun `should return true if no discourse username linked`() = runBlocking {
    // Arrange - list of members to be used in tests
    val members = TestUtils.generateTestDMSMembers(2)
    members[0].discourseUsername = null
    members[1].discourseUsername = null

    // Act
    val result =
        tested.onMemberPropChange(
            "enabled", oldValue = false, newValue = true, affectedMembers = members)

    // Assert
    assert(result)
    verifyNoInteractions(discourseService)
    verifyNoInteractions(activityLogService)
  }

  private companion object {
    // Arrange - list of members to be used in tests
    val members = TestUtils.generateTestDMSMembers(2)
  }
}
