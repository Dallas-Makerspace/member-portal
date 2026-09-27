package org.dallasmakerspace.members.routing

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.ProfilePhotoRateLimitedException
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.members.ProfilePhotoValidationException
import org.dallasmakerspace.plugins.ApiResponse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfilePhotoRoutesTest {

  private lateinit var mockService: ProfilePhotoService
  private lateinit var mockFeatureFlagService: FeatureFlagService
  private lateinit var mockMemberService: MemberService

  @Before
  fun setUp() {
    mockService = mock()
    mockFeatureFlagService = mock()
    mockMemberService = mock()
    runBlocking {
      whenever(
              mockFeatureFlagService.isEnabledForUsername(
                  ConfigRegistry.PROFILE_PHOTO_ENABLED, "jdoe"))
          .thenReturn(true)
    }
  }

  private fun appConfig(): AppConfig {
    val mockAppConfig = mock<AppConfig>()
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.name")).thenReturn("test-client")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.key")).thenReturn("test-key")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.role"))
        .thenReturn("MEMBER_READER")
    return mockAppConfig
  }

  private fun ApplicationTestBuilder.configureApp() {
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      install(Authentication) {
        apiKey(appConfig(), ApiKeyAuthProvider.X_API_KEY) { validate { null } }
      }
      routing { profilePhotoRoutes(mockService, mockFeatureFlagService, mockMemberService) }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  @Test
  fun `put should save the photo when acting as self`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.save("jdoe", byteArrayOf(1, 2, 3))).thenReturn("/avatar/@jdoe?v=1")
    }

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          contentType(ContentType.Image.JPEG)
          setBody(byteArrayOf(1, 2, 3))
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService).save("jdoe", byteArrayOf(1, 2, 3))
    Unit
  }

  @Test
  fun `put should reject uploading to another member's profile`() = testApplication {
    configureApp()

    val response =
        client.put("/members/jane/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          setBody(byteArrayOf(1))
        }

    assertEquals(HttpStatusCode.Forbidden, response.status)
    Unit
  }

  @Test
  fun `put should reject when the flag is disabled for the actor`() = testApplication {
    configureApp()
    runBlocking {
      whenever(
              mockFeatureFlagService.isEnabledForUsername(
                  ConfigRegistry.PROFILE_PHOTO_ENABLED, "jdoe"))
          .thenReturn(false)
    }

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          setBody(byteArrayOf(1))
        }

    assertEquals(HttpStatusCode.Forbidden, response.status)
    Unit
  }

  @Test
  fun `put should require X-Username header`() = testApplication {
    configureApp()

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          setBody(byteArrayOf(1))
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `put should reject an oversized body`() = testApplication {
    configureApp()

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          setBody(ByteArray(3 * 1024 * 1024))
        }

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    Unit
  }

  @Test
  fun `put should return 415 when the service rejects the image`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.save("jdoe", byteArrayOf(1)))
          .thenThrow(ProfilePhotoValidationException("Only JPEG and PNG photos are supported"))
    }

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          setBody(byteArrayOf(1))
        }

    assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    val apiResponse = response.body<ApiResponse<String?>>()
    assertEquals("Only JPEG and PNG photos are supported", apiResponse.message)
    Unit
  }

  @Test
  fun `put should return 429 when the actor is rate limited`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.save("jdoe", byteArrayOf(1)))
          .thenThrow(ProfilePhotoRateLimitedException("too many uploads"))
    }

    val response =
        client.put("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
          setBody(byteArrayOf(1))
        }

    assertEquals(HttpStatusCode.TooManyRequests, response.status)
    Unit
  }

  @Test
  fun `put should require authentication`() = testApplication {
    configureApp()

    val response = client.put("/members/jdoe/profile-photo") { header("X-Username", "jdoe") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    Unit
  }

  @Test
  fun `delete should remove own photo`() = testApplication {
    configureApp()

    val response =
        client.delete("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService).remove("jdoe")
    Unit
  }

  @Test
  fun `delete should reject a non-infra member removing someone else's photo`() = testApplication {
    configureApp()
    runBlocking { whenever(mockMemberService.isUserInInfraGroup("jdoe")).thenReturn(false) }

    val response =
        client.delete("/members/jane/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
        }

    assertEquals(HttpStatusCode.Forbidden, response.status)
    Unit
  }

  @Test
  fun `delete should allow an infra admin to remove someone else's photo`() = testApplication {
    configureApp()
    whenever(mockMemberService.isUserInInfraGroup("infraadmin")).thenReturn(true)

    val response =
        client.delete("/members/jane/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "infraadmin")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService).remove("jane")
    Unit
  }

  @Test
  fun `get should stream the photo bytes with a long-lived cache header`() = testApplication {
    configureApp()
    runBlocking { whenever(mockService.get("jdoe")).thenReturn(byteArrayOf(9, 9) to "image/jpeg") }

    val response =
        client.get("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
    assertEquals("private, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
    Unit
  }

  @Test
  fun `get should 404 when the member has no photo`() = testApplication {
    configureApp()
    runBlocking { whenever(mockService.get("jdoe")).thenReturn(null) }

    val response =
        client.get("/members/jdoe/profile-photo") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.NotFound, response.status)
    Unit
  }
}
