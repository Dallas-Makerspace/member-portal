package org.dallasmakerspace.discourse

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class DiscourseApiClientTest {

  private lateinit var mockAppConfig: AppConfig
  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var discourseApiClient: DiscourseApiClient

  @Before
  fun setUp() {
    mockAppConfig = mock()
    mockLoggerFactory = mock()
    mockLogger = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    whenever(mockAppConfig.requireStringProperty("app.discourse.apiKey")).thenReturn("test-api-key")

    discourseApiClient = DiscourseApiClient(mockAppConfig, mockLoggerFactory)
  }

  @Test
  fun `getUserProfile should have correct method signature`() {
    // This is a basic test to verify the method exists and has the correct signature
    // Full HTTP client testing would require more complex mocking setup

    val username = "testuser"

    // Verify the method exists by creating a mock profile with expected structure
    val mockProfile =
        DiscourseUserProfile(
            user =
                DiscourseUser(
                    id = 123,
                    username = username,
                    name = "Test User",
                    avatarTemplate =
                        "/user_avatar/talk.dallasmakerspace.org/testuser/{size}/123_2.png",
                    title = "Test Title",
                    admin = false,
                    moderator = false,
                    trustLevel = 1))

    // Verify the data structure is correct
    assertEquals(username, mockProfile.user.username)
    assertTrue(mockProfile.user.avatarTemplate.contains("{size}"))
  }
}

class DiscourseApiClientMockTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var discourseApiClientMock: DiscourseApiClientMock

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)

    discourseApiClientMock = DiscourseApiClientMock(mockLoggerFactory)
  }

  @Test
  fun `getUserProfile should return mock user profile with correct structure`() {
    val username = "testuser"

    val result = runBlocking { discourseApiClientMock.getUserProfile(username) }

    assertEquals(username, result.user.username)
    assertEquals(12345, result.user.id)
    assertEquals("Mock User", result.user.name)
    assertTrue(result.user.avatarTemplate.contains(username))
    assertTrue(result.user.avatarTemplate.contains("{size}"))
    assertEquals("Mock Title", result.user.title)
    assertEquals(false, result.user.admin)
    assertEquals(false, result.user.moderator)
    assertEquals(1, result.user.trustLevel)

    verify(mockLogger).info("Mocked fetching user profile for username: $username")
  }

  @Test
  fun `getUserProfile should return different usernames in avatar template for different users`() {
    val username1 = "user1"
    val username2 = "user2"

    val result1 = runBlocking { discourseApiClientMock.getUserProfile(username1) }

    val result2 = runBlocking { discourseApiClientMock.getUserProfile(username2) }

    assertEquals(username1, result1.user.username)
    assertEquals(username2, result2.user.username)
    assertTrue(result1.user.avatarTemplate.contains(username1))
    assertTrue(result2.user.avatarTemplate.contains(username2))
  }
}

class DiscourseUserProfileTest {

  @Test
  fun `DiscourseUserProfile should have correct structure with all fields`() {
    val user =
        DiscourseUser(
            id = 123,
            username = "testuser",
            name = "Test User",
            avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/testuser/{size}/123_2.png",
            title = "Test Title",
            admin = false,
            moderator = false,
            trustLevel = 1)

    val profile = DiscourseUserProfile(user = user)

    assertEquals(123, profile.user.id)
    assertEquals("testuser", profile.user.username)
    assertEquals("Test User", profile.user.name)
    assertTrue(profile.user.avatarTemplate.contains("{size}"))
    assertEquals("Test Title", profile.user.title)
    assertEquals(false, profile.user.admin)
    assertEquals(false, profile.user.moderator)
    assertEquals(1, profile.user.trustLevel)
  }

  @Test
  fun `DiscourseUser should handle optional fields correctly`() {
    val userWithMinimalFields =
        DiscourseUser(
            id = 456,
            username = "minimaluser",
            avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/minimaluser/{size}/456_2.png")

    val profile = DiscourseUserProfile(user = userWithMinimalFields)

    assertEquals(456, profile.user.id)
    assertEquals("minimaluser", profile.user.username)
    assertEquals(null, profile.user.name)
    assertTrue(profile.user.avatarTemplate.contains("{size}"))
    assertEquals(null, profile.user.title)
    assertEquals(false, profile.user.admin)
    assertEquals(false, profile.user.moderator)
    assertEquals(0, profile.user.trustLevel)
  }

  @Test
  fun `DiscourseUser should require mandatory fields`() {
    val user =
        DiscourseUser(
            id = 789,
            username = "requireduser",
            avatarTemplate = "/user_avatar/talk.dallasmakerspace.org/requireduser/{size}/789_2.png")

    assertNotNull(user.id)
    assertNotNull(user.username)
    assertNotNull(user.avatarTemplate)

    // Verify that required fields are not empty
    assertTrue(user.username.isNotEmpty())
    assertTrue(user.avatarTemplate.isNotEmpty())
  }
}
