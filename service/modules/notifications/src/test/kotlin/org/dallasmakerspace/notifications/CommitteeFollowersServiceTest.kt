package org.dallasmakerspace.notifications

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CommitteeFollowersServiceTest {

  private val repository = mock<CommitteeSubscriptionsRepository>()
  private val service = CommitteeFollowersService(repository)

  private fun givenFollowers(vararg names: String) = runBlocking {
    whenever(repository.getFollowers(9)).thenReturn(names.toList())
  }

  @Test
  fun `pages through followers and reports the total`() = runBlocking {
    givenFollowers("a", "b", "c", "d", "e")

    assertEquals(CommitteeFollowersPage(5, listOf("a", "b")), service.getPage(9, 2, 0))
    assertEquals(CommitteeFollowersPage(5, listOf("c", "d")), service.getPage(9, 2, 2))
    assertEquals(CommitteeFollowersPage(5, listOf("e")), service.getPage(9, 2, 4))
  }

  @Test
  fun `an offset past the end is an empty page`() = runBlocking {
    givenFollowers("a", "b")

    assertEquals(CommitteeFollowersPage(2, emptyList()), service.getPage(9, 5, 10))
  }
}
