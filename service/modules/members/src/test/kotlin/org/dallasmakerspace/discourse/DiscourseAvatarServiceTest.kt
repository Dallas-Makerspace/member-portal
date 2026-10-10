package org.dallasmakerspace.discourse

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class DiscourseAvatarServiceTest {

  private lateinit var mockDiscourseApiClient: IDiscourseApiClient
  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var discourseAvatarService: DiscourseAvatarService

  @Before
  fun setUp() {
    mockDiscourseApiClient = mock()
    mockLoggerFactory = mock()
    mockLogger = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)

    discourseAvatarService = DiscourseAvatarService(mockDiscourseApiClient, mockLoggerFactory)
  }

  @Test
  fun `refreshAvatarUrl should return raw template when API call succeeds`() {
    val username = "testuser"
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/testuser/{size}/123_2.png"

    val mockUser = DiscourseUser(id = 123, username = username, avatarTemplate = avatarTemplate)
    val mockProfile = DiscourseUserProfile(user = mockUser)

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)

      val result = discourseAvatarService.refreshAvatarUrl(username)

      assertEquals(avatarTemplate, result)
      verify(mockDiscourseApiClient).getUserProfile(username)
      verify(mockLogger)
          .debug("Refreshing avatar template for username: $username (forceRefresh: false)")
      verify(mockLogger)
          .debug("Successfully refreshed avatar template for $username: $avatarTemplate")
    }
  }

  @Test
  fun `refreshAvatarUrl should return null when API call fails with unexpected exception`() {
    val username = "erroruser"
    val exception = RuntimeException("Network error")

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenThrow(exception)

      val result = discourseAvatarService.refreshAvatarUrl(username)

      assertNull(result)
      verify(mockDiscourseApiClient).getUserProfile(username)
      verify(mockLogger)
          .error("Unexpected error refreshing avatar template for $username", exception)
    }
  }

  @Test
  fun `refreshAvatarUrl should skip refresh when recently fetched and forceRefresh is false`() {
    val username = "recentuser"

    // First call should work
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/recentuser/{size}/456_2.png"
    val mockUser = DiscourseUser(id = 456, username = username, avatarTemplate = avatarTemplate)
    val mockProfile = DiscourseUserProfile(user = mockUser)

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)

      // First call
      val firstResult = discourseAvatarService.refreshAvatarUrl(username)
      assertNotNull(firstResult)

      // Second call immediately after should be skipped
      val secondResult = discourseAvatarService.refreshAvatarUrl(username)
      assertNull(secondResult)

      // Verify API was only called once
      verify(mockDiscourseApiClient, times(1)).getUserProfile(username)
      verify(mockLogger).debug("Skipping avatar refresh for $username due to recent fetch")
    }
  }

  @Test
  fun `refreshAvatarUrl should not skip refresh when forceRefresh is true`() {
    val username = "forceduser"
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/forceduser/{size}/789_2.png"
    val mockUser = DiscourseUser(id = 789, username = username, avatarTemplate = avatarTemplate)
    val mockProfile = DiscourseUserProfile(user = mockUser)

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)

      // First call
      val firstResult = discourseAvatarService.refreshAvatarUrl(username)
      assertNotNull(firstResult)

      // Second call with forceRefresh should work
      val secondResult = discourseAvatarService.refreshAvatarUrl(username, forceRefresh = true)
      assertNotNull(secondResult)

      // Verify API was called twice
      verify(mockDiscourseApiClient, times(2)).getUserProfile(username)
    }
  }

  @Test
  fun `clearCache should remove username from cache`() {
    val username = "cacheuser"
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/cacheuser/{size}/123_2.png"
    val mockUser = DiscourseUser(id = 123, username = username, avatarTemplate = avatarTemplate)
    val mockProfile = DiscourseUserProfile(user = mockUser)

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)

      // First call to populate cache
      discourseAvatarService.refreshAvatarUrl(username)
      assertTrue(discourseAvatarService.getCacheSize() > 0)

      // Clear cache for this user
      discourseAvatarService.clearCache(username)

      // Second call should work since cache is cleared
      val result = discourseAvatarService.refreshAvatarUrl(username)
      assertNotNull(result)

      // Verify API was called twice (once before clear, once after)
      verify(mockDiscourseApiClient, times(2)).getUserProfile(username)
      verify(mockLogger).debug("Cleared avatar cache for username: $username")
    }
  }

  @Test
  fun `clearAllCache should remove all usernames from cache`() {
    val usernames = listOf("user1", "user2", "user3")
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/{username}/{size}/123_2.png"

    runBlocking {
      // Populate cache with multiple users
      usernames.forEach { username ->
        val mockUser = DiscourseUser(id = 123, username = username, avatarTemplate = avatarTemplate)
        val mockProfile = DiscourseUserProfile(user = mockUser)
        whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)
        discourseAvatarService.refreshAvatarUrl(username)
      }

      assertTrue(discourseAvatarService.getCacheSize() >= usernames.size)

      // Clear all cache
      discourseAvatarService.clearAllCache()

      assertEquals(0, discourseAvatarService.getCacheSize())
      verify(mockLogger).info("Cleared all avatar cache entries (count: ${usernames.size})")
    }
  }

  @Test
  fun `getCacheSize should return correct cache size`() {
    assertEquals(0, discourseAvatarService.getCacheSize())

    val username = "sizeuser"
    val avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/sizeuser/{size}/123_2.png"
    val mockUser = DiscourseUser(id = 123, username = username, avatarTemplate = avatarTemplate)
    val mockProfile = DiscourseUserProfile(user = mockUser)

    runBlocking {
      whenever(mockDiscourseApiClient.getUserProfile(username)).thenReturn(mockProfile)

      discourseAvatarService.refreshAvatarUrl(username)
      assertEquals(1, discourseAvatarService.getCacheSize())
    }
  }
}
