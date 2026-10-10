package org.dallasmakerspace.notifications

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.notifications.db.ProfileSubscriptionsRepository
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfileSubscriptionServiceTest {

  private lateinit var mockRepository: ProfileSubscriptionsRepository
  private lateinit var service: ProfileSubscriptionService

  @Before
  fun setUp() {
    mockRepository = mock()
    service = ProfileSubscriptionService(mockRepository)
  }

  @Test
  fun `follow should delegate to repository`() = runBlocking {
    service.follow("jdoe", "jane")

    verify(mockRepository).follow("jdoe", "jane")
    Unit
  }

  @Test
  fun `follow should reject following yourself`() = runBlocking {
    assertFailsWith<IllegalArgumentException> { service.follow("jdoe", "jdoe") }

    verify(mockRepository, never()).follow(eq("jdoe"), eq("jdoe"))
    Unit
  }

  @Test
  fun `unfollow should delegate to repository`() = runBlocking {
    service.unfollow("jdoe", "jane")

    verify(mockRepository).unfollow("jdoe", "jane")
    Unit
  }

  @Test
  fun `isFollowing should delegate to repository`() = runBlocking {
    whenever(mockRepository.isFollowing("jdoe", "jane")).thenReturn(true)

    val result = service.isFollowing("jdoe", "jane")

    assertEquals(true, result)
  }

  @Test
  fun `getFollowing should delegate to repository`() = runBlocking {
    whenever(mockRepository.getFollowing("jdoe")).thenReturn(listOf("jane", "bob"))

    val result = service.getFollowing("jdoe")

    assertEquals(listOf("jane", "bob"), result)
  }

  @Test
  fun `getFollowers should delegate to repository`() = runBlocking {
    whenever(mockRepository.getFollowers("jdoe")).thenReturn(listOf("alice"))

    val result = service.getFollowers("jdoe")

    assertEquals(listOf("alice"), result)
  }
}
