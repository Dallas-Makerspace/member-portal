package org.dallasmakerspace.discourse

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.IOException
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

private const val DISCOURSE_BASE_URL = "https://talk.dallasmakerspace.org"
private const val DEFAULT_RETRY_AFTER_SECONDS = 60L

@Singleton
class DiscourseApiClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) : IDiscourseApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  private fun getClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = 10_000
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = 10_000
        }
        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.BODY
        }
      }

  override suspend fun addUsersToGroup(
      memberUsernamesList: List<String>,
      groupId: DiscourseService.GroupId
  ) {
    performGroupOperation(memberUsernamesList, groupId, HttpMethod.Put) { response ->
      when (response.status) {
        HttpStatusCode.OK -> return@performGroupOperation
        HttpStatusCode.UnprocessableEntity -> {
          log.info("Member(s) $memberUsernamesList already exists in the group ${groupId.name}")
          return@performGroupOperation
        }
        else ->
            throw DiscourseApiException(
                "Failed to add members $memberUsernamesList to $groupId: ${response.status} - ${response.bodyAsText()}")
      }
    }
  }

  override suspend fun removeMembersFromGroup(
      memberUsernamesList: List<String>,
      groupId: DiscourseService.GroupId
  ) {
    performGroupOperation(memberUsernamesList, groupId, HttpMethod.Delete) { response ->
      if (response.status != HttpStatusCode.OK) {
        throw DiscourseApiException(
            "Failed to remove $memberUsernamesList from $groupId: ${response.status} - ${response.bodyAsText()}")
      }
    }
  }

  override suspend fun getUserProfile(username: String): DiscourseUserProfile {
    val baseUrl = DISCOURSE_BASE_URL
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$baseUrl/u/$username.json"

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully fetched user profile for username: $username")
          val responseBody = response.bodyAsText()
          return json.decodeFromString<DiscourseUserProfile>(responseBody)
        }
        HttpStatusCode.NotFound -> {
          throw DiscourseUserNotFoundException(username)
        }
        HttpStatusCode.Forbidden -> {
          throw DiscourseApiException("Access denied when fetching user profile for: $username")
        }
        else -> {
          throw DiscourseApiException(
              "Failed to fetch user profile for $username: ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: DiscourseApiException) {
      throw e
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch user profile for $username", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse user profile response for $username", e)
    }
  }

  // No Logging plugin: these URLs and bodies carry member emails.
  private fun getAdminClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = 30_000
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = 30_000
        }
      }

  override suspend fun findUsersByEmail(email: String): List<DiscourseAdminUser> =
      fetchAdminUsers(mapOf("email" to email))

  override suspend fun listUsersPage(page: Int): List<DiscourseAdminUser> =
      fetchAdminUsers(
          mapOf("show_emails" to "true", "order" to "username", "asc" to "true", "page" to "$page"))

  // Errors never carry the URL, body or cause: all of them can contain an email.
  private suspend fun fetchAdminUsers(params: Map<String, String>): List<DiscourseAdminUser> {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    try {
      val response =
          getAdminClient().use {
            it.request("$DISCOURSE_BASE_URL/admin/users/list.json") {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
              params.forEach { (name, value) -> parameter(name, value) }
            }
          }
      return when (response.status) {
        HttpStatusCode.OK -> parseAdminUsers(response.bodyAsText())
        HttpStatusCode.TooManyRequests ->
            throw DiscourseRateLimitException(
                response.headers["Retry-After"]?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
        else -> throw DiscourseApiException("Talk admin user lookup failed: ${response.status.value}")
      }
    } catch (e: DiscourseApiException) {
      throw e
    } catch (e: IOException) {
      throw DiscourseApiException("Talk admin user lookup failed: ${e.javaClass.simpleName}")
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Talk admin user lookup returned an unreadable response")
    }
  }

  private suspend fun performDiscourseApiOperation(
      url: String,
      method: HttpMethod,
      jsonBody: String,
      apiKey: String,
      onResponse: suspend (HttpResponse) -> Unit
  ) {
    try {
      val response =
          getClient().use {
            it.request(url) {
              this.method = method
              header("Api-Key", apiKey)
              header("Api-Username", "system")
              contentType(ContentType.Application.Json)
              setBody(jsonBody)
            }
          }
      onResponse(response)
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to perform Discourse API operation", e)
    }
  }

  private suspend fun performGroupOperation(
      memberUsernames: List<String>,
      groupId: DiscourseService.GroupId,
      method: HttpMethod,
      onResponse: suspend (HttpResponse) -> Unit
  ) {
    val baseUrl = DISCOURSE_BASE_URL
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$baseUrl/groups/${groupId.id}/members.json"
    // Generate a string from the list of usernames by joining with a comma
    val memberList = memberUsernames.joinToString(separator = ",")
    val json = """{"usernames": "$memberList"}"""

    performDiscourseApiOperation(url, method, json, apiKey, onResponse)
  }

  override suspend fun createPost(
      title: String,
      raw: String,
      categoryId: Int
  ): DiscoursePostResponse = postTopic("system", title, raw, categoryId)

  override suspend fun createTopicAs(
      username: String,
      title: String,
      raw: String,
      categoryId: Int
  ): DiscoursePostResponse = postTopic(username, title, raw, categoryId)

  private suspend fun postTopic(
      apiUsername: String,
      title: String,
      raw: String,
      categoryId: Int
  ): DiscoursePostResponse {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$DISCOURSE_BASE_URL/posts.json"
    val jsonBody =
        buildJsonObject {
              put("title", title)
              put("raw", raw)
              put("category", categoryId)
            }
            .toString()

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Post
              header("Api-Key", apiKey)
              header("Api-Username", apiUsername)
              contentType(ContentType.Application.Json)
              setBody(jsonBody)
            }
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully created post: $title")
          return json.decodeFromString<DiscoursePostResponse>(response.bodyAsText())
        }
        else -> {
          throw DiscourseApiException(
              "Failed to create post '$title': ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to create post '$title'", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse create post response for '$title'", e)
    }
  }

  override suspend fun getTopic(topicId: Int): DiscourseTopicDetails {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$DISCOURSE_BASE_URL/t/$topicId.json"
    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }
      when (response.status) {
        HttpStatusCode.OK ->
            return json.decodeFromString<DiscourseTopicDetails>(response.bodyAsText())
        HttpStatusCode.NotFound,
        HttpStatusCode.Forbidden,
        HttpStatusCode.Gone -> throw DiscourseTopicNotFoundException(topicId)
        else ->
            throw DiscourseApiException(
                "Failed to fetch topic $topicId: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch topic $topicId", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse topic response for $topicId", e)
    }
  }

  override suspend fun updateTopicStatus(topicId: Int, status: String, enabled: Boolean) {
    val baseUrl = DISCOURSE_BASE_URL
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$baseUrl/t/$topicId/status"

    val jsonBody = """{"status": "$status", "enabled": "$enabled"}"""

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Put
              header("Api-Key", apiKey)
              header("Api-Username", "system")
              contentType(ContentType.Application.Json)
              setBody(jsonBody)
            }
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully updated topic $topicId status: $status = $enabled")
        }
        else -> {
          throw DiscourseApiException(
              "Failed to update topic $topicId status: ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to update topic $topicId status", e)
    }
  }

  override suspend fun pinTopic(topicId: Int, pinned: Boolean, pinGlobally: Boolean) {
    val status = if (pinGlobally) "pinned_globally" else "pinned"
    updateTopicStatus(topicId, status, pinned)
  }

  override suspend fun getCategoryTopics(
      categorySlug: String,
      categoryId: Int,
      page: Int
  ): DiscourseCategoryResponse {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$DISCOURSE_BASE_URL/c/$categorySlug/$categoryId.json?page=$page"
    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }
      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully fetched category topics: $categorySlug ($categoryId)")
          return json.decodeFromString<DiscourseCategoryResponse>(response.bodyAsText())
        }
        else ->
            throw DiscourseApiException(
                "Failed to fetch category topics for $categorySlug: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch category topics for $categorySlug", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse category topics response for $categorySlug", e)
    }
  }

  override suspend fun getTopicPosts(topicId: Int): DiscourseTopicDetails {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url = "$DISCOURSE_BASE_URL/t/$topicId.json"
    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }
      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully fetched topic posts for topic: $topicId")
          return json.decodeFromString<DiscourseTopicDetails>(response.bodyAsText())
        }
        else ->
            throw DiscourseApiException(
                "Failed to fetch topic posts for $topicId: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch topic posts for $topicId", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse topic posts response for $topicId", e)
    }
  }

  override suspend fun getTopicPostsByIds(topicId: Int, postIds: List<Int>): DiscourseTopicDetails {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val postIdsParam = postIds.joinToString("&") { "post_ids[]=$it" }
    val url = "$DISCOURSE_BASE_URL/t/$topicId/posts.json?$postIdsParam"
    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }
      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully fetched specific posts for topic: $topicId, postIds: $postIds")
          return json.decodeFromString<DiscourseTopicDetails>(response.bodyAsText())
        }
        else ->
            throw DiscourseApiException(
                "Failed to fetch posts by ID for topic $topicId: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch posts by ID for topic $topicId", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse posts-by-ID response for topic $topicId", e)
    }
  }

  override suspend fun getUserActions(
      username: String,
      filter: Int,
      offset: Int
  ): DiscourseUserActionsResponse {
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")
    val url =
        "$DISCOURSE_BASE_URL/user_actions.json?username=$username&filter=$filter&offset=$offset"
    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }
      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug(
              "Successfully fetched user actions for $username (filter=$filter, offset=$offset)")
          return json.decodeFromString<DiscourseUserActionsResponse>(response.bodyAsText())
        }
        else ->
            throw DiscourseApiException(
                "Failed to fetch user actions for $username: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to fetch user actions for $username", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse user actions response for $username", e)
    }
  }

  override suspend fun searchTopics(query: String, categoryId: Int?): DiscourseSearchResponse {
    val baseUrl = DISCOURSE_BASE_URL
    val apiKey = appConfig.requireStringProperty("app.discourse.apiKey")

    val categoryParam = if (categoryId != null) "&category=$categoryId" else ""
    val url = "$baseUrl/search.json?q=${java.net.URLEncoder.encode(query, "UTF-8")}$categoryParam"

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Api-Key", apiKey)
              header("Api-Username", "system")
            }
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          log.debug("Successfully searched topics with query: $query")
          val responseBody = response.bodyAsText()
          return json.decodeFromString<DiscourseSearchResponse>(responseBody)
        }
        else -> {
          throw DiscourseApiException(
              "Failed to search topics: ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: IOException) {
      throw DiscourseApiException("Failed to search topics with query: $query", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw DiscourseApiException("Failed to parse search response for query: $query", e)
    }
  }
}
