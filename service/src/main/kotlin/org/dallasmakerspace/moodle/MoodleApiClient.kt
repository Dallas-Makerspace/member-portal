package org.dallasmakerspace.moodle

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

private const val CONNECT_TIMEOUT_MS = 3_000L
private const val REQUEST_TIMEOUT_MS = 8_000L

/**
 * Moodle REST web services client. The token goes in the POST body (never the URL) and is never
 * logged. Moodle reports most errors as HTTP 200 with an `exception` JSON object.
 */
@Singleton
class MoodleApiClient @Inject constructor(appConfig: AppConfig, loggerFactory: LoggerFactory) :
    IMoodleApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json { ignoreUnknownKeys = true }

  override val baseUrl: String by lazy {
    appConfig.requireStringProperty("app.moodle.base-url").trimEnd('/')
  }
  override val statsReportId: Int by lazy {
    appConfig.getStringProperty("app.moodle.stats-report-id", "0").toIntOrNull() ?: 0
  }
  private val token: String by lazy { appConfig.requireStringProperty("app.moodle.ws-token") }

  private val client by lazy {
    HttpClient(DmsHttpEngine) {
      install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
      }
    }
  }

  override suspend fun getCategories(): List<MoodleCategory> =
      call("core_course_get_categories", emptyMap(), ListSerializer(MoodleCategory.serializer()))

  override suspend fun getCourses(): List<MoodleCourse> =
      call(
              "core_course_get_courses_by_field",
              emptyMap(),
              MoodleCoursesResponse.serializer(),
          )
          .courses
          .filter { it.categoryid != 0 }

  override suspend fun getUserId(username: String): Int? =
      call(
              "core_user_get_users_by_field",
              mapOf("field" to "username", "values[0]" to username.lowercase()),
              ListSerializer(MoodleUser.serializer()),
          )
          .firstOrNull()
          ?.id

  override suspend fun getUsernames(userIds: Collection<Int>): Map<Int, String> {
    if (userIds.isEmpty()) return emptyMap()
    val params =
        mapOf("field" to "id") +
            userIds.distinct().mapIndexed { i, id -> "values[$i]" to id.toString() }
    return call("core_user_get_users_by_field", params, ListSerializer(MoodleUser.serializer()))
        .associate { it.id to it.username }
  }

  override suspend fun getUserCourses(userId: Int): List<MoodleUserCourse> =
      call(
          "core_enrol_get_users_courses",
          mapOf("userid" to "$userId", "returnusercount" to "0"),
          ListSerializer(MoodleUserCourse.serializer()),
      )

  override suspend fun getReportPage(reportId: Int, page: Int, perPage: Int): MoodleReportData =
      call(
              "core_reportbuilder_retrieve_report",
              mapOf("reportid" to "$reportId", "page" to "$page", "perpage" to "$perPage"),
              MoodleReportResponse.serializer(),
          )
          .data

  private suspend fun <T> call(
      function: String,
      params: Map<String, String>,
      deserializer: DeserializationStrategy<T>,
  ): T {
    val body =
        try {
          val response =
              client.submitForm(
                  url = "$baseUrl/webservice/rest/server.php",
                  formParameters =
                      parameters {
                        append("wstoken", token)
                        append("wsfunction", function)
                        append("moodlewsrestformat", "json")
                        params.forEach { (k, v) -> append(k, v) }
                      },
              )
          if (response.status != HttpStatusCode.OK) {
            throw MoodleApiException("Moodle $function returned ${response.status}")
          }
          response.bodyAsText()
        } catch (e: MoodleApiException) {
          throw e
        } catch (e: Exception) {
          throw MoodleApiException("Moodle $function request failed", e)
        }
    return decode(function, body, deserializer)
  }

  internal fun <T> decode(
      function: String,
      body: String,
      deserializer: DeserializationStrategy<T>,
  ): T {
    val element =
        try {
          json.parseToJsonElement(body)
        } catch (e: Exception) {
          // Cloudflare challenge/error pages and Moodle maintenance pages come back as HTML.
          throw MoodleApiException("Moodle $function returned a non-JSON response", e)
        }
    if (element is JsonObject && element.containsKey("exception")) {
      val code = element["errorcode"]?.jsonPrimitive?.content
      val message = element["message"]?.jsonPrimitive?.content
      log.warn("Moodle $function failed: $code - $message")
      throw MoodleApiException("Moodle $function failed: $code")
    }
    return try {
      json.decodeFromJsonElement(deserializer, element)
    } catch (e: Exception) {
      throw MoodleApiException("Moodle $function returned an unexpected response", e)
    }
  }
}
