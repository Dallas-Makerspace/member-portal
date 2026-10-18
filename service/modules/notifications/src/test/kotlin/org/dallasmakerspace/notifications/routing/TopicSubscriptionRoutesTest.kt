package org.dallasmakerspace.notifications.routing

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionHandler
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TopicSubscriptionRoutesTest {

  private class FakeHandler(override val featureFlag: ConfigKey<FeatureFlagValue>?) :
      TopicSubscriptionHandler {
    val subscribed = mutableSetOf<Pair<String, String>>()

    override val topic =
        object : NotificationTopic {
          override val key = "FAKE"
          override val displayName = "Fake"
        }
    override val argNames = setOf("target")

    override suspend fun isSubscribed(actor: String, args: Map<String, String>) =
        (actor to args.getValue("target")) in subscribed

    override suspend fun subscribe(actor: String, args: Map<String, String>) {
      require(actor != args.getValue("target")) { "Cannot subscribe to yourself" }
      subscribed += actor to args.getValue("target")
    }

    override suspend fun unsubscribe(actor: String, args: Map<String, String>) {
      subscribed -= actor to args.getValue("target")
    }

    override fun copy(args: Map<String, String>) =
        SubscriptionCopy(
            subscribeLabel = "Sub ${args.getValue("target")}",
            subscribedLabel = "Subbed",
            unsubscribeLabel = "Unsub",
            subscribeIcon = "add",
            subscribedIcon = "check",
            unsubscribeIcon = "remove",
            subscribeBusy = "Subscribing…",
            unsubscribeBusy = "Unsubscribing…",
            subscribedToast = "Subscribed",
            unsubscribedToast = "Unsubscribed")
  }

  private lateinit var featureFlagService: FeatureFlagService
  private lateinit var handler: FakeHandler

  @Before
  fun setUp() {
    featureFlagService = mock()
    handler = FakeHandler(ConfigRegistry.FOLLOW_FEATURE_ENABLED)
    runBlocking {
      // Unstubbed suspend Boolean mocks return null (NPE on unboxing), so default to off.
      whenever(featureFlagService.isEnabledForUsername(any(), any())).thenReturn(false)
      whenever(featureFlagService.isEnabledForUsername(ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
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
    val service = TopicSubscriptionService(mapOf("FAKE" to handler), featureFlagService)
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      install(Authentication) {
        apiKey(appConfig(), ApiKeyAuthProvider.X_API_KEY) { validate { null } }
      }
      routing { topicSubscriptionRoutes(service) }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  private suspend fun ApplicationTestBuilder.call(
      method: String,
      path: String,
      actor: String? = "jdoe",
  ): HttpResponse {
    val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
      header("X-Api-Key", "test-key")
      header("X-Api-Client", "test-client")
      actor?.let { header("X-Username", it) }
    }
    return when (method) {
      "POST" -> client.post(path, block)
      "DELETE" -> client.delete(path, block)
      else -> client.get(path, block)
    }
  }

  @Test
  fun `post subscribes the acting member and returns the new state with copy`() = testApplication {
    configureApp()

    val response = call("POST", "/subscriptions/FAKE?target=asmith")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.body<ApiResponse<SubscriptionStatus>>()
    assertEquals(Status.SUCCESS, body.status)
    assertTrue(body.data!!.subscribed)
    assertEquals("Sub asmith", body.data!!.copy.subscribeLabel)
    assertTrue(("jdoe" to "asmith") in handler.subscribed)
  }

  @Test
  fun `get reports the current state without changing it`() = testApplication {
    configureApp()
    handler.subscribed += "jdoe" to "asmith"

    val response = call("GET", "/subscriptions/FAKE?target=asmith")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.body<ApiResponse<SubscriptionStatus>>().data!!.subscribed)
  }

  @Test
  fun `delete unsubscribes and is idempotent`() = testApplication {
    configureApp()
    handler.subscribed += "jdoe" to "asmith"

    repeat(2) {
      val response = call("DELETE", "/subscriptions/FAKE?target=asmith")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(false, response.body<ApiResponse<SubscriptionStatus>>().data!!.subscribed)
    }
  }

  @Test
  fun `unknown topic is 404`() = testApplication {
    configureApp()

    assertEquals(HttpStatusCode.NotFound, call("POST", "/subscriptions/NOPE?target=asmith").status)
  }

  @Test
  fun `missing extra blank or repeated args are 400`() = testApplication {
    configureApp()

    listOf(
            "/subscriptions/FAKE",
            "/subscriptions/FAKE?other=x",
            "/subscriptions/FAKE?target=asmith&other=x",
            "/subscriptions/FAKE?target=",
            "/subscriptions/FAKE?target=a&target=b",
        )
        .forEach { assertEquals(HttpStatusCode.BadRequest, call("POST", it).status, it) }
  }

  @Test
  fun `requests without API credentials are 401 and change nothing`() = testApplication {
    configureApp()

    val response = client.post("/subscriptions/FAKE?target=asmith") { header("X-Username", "jdoe") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertTrue(handler.subscribed.isEmpty())
  }

  @Test
  fun `missing X-Username is 400`() = testApplication {
    configureApp()

    assertEquals(
        HttpStatusCode.BadRequest,
        call("POST", "/subscriptions/FAKE?target=asmith", actor = null).status)
  }

  @Test
  fun `feature flag off for the actor is 403 and nothing changes`() = testApplication {
    configureApp()

    val response = call("POST", "/subscriptions/FAKE?target=asmith", actor = "off-pilot")

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertTrue(handler.subscribed.isEmpty())
  }

  @Test
  fun `a handler without a feature flag skips the check`() = testApplication {
    handler = FakeHandler(featureFlag = null)
    configureApp()

    assertEquals(
        HttpStatusCode.OK,
        call("POST", "/subscriptions/FAKE?target=asmith", actor = "anyone").status)
  }

  @Test
  fun `handler argument errors are 400 with the message`() = testApplication {
    configureApp()

    val response = call("POST", "/subscriptions/FAKE?target=jdoe")

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals("Cannot subscribe to yourself", response.body<ApiResponse<String?>>().message)
  }
}
