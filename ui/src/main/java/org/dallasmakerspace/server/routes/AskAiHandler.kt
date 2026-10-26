package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import io.ktor.utils.io.writeStringUtf8
import javax.inject.Inject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.getCachedFeatureFlag

/** Handler for the Ask DMS AI page. Renders the question form and handles AI responses. */
class AskAiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
    private val appConfig: AppConfig,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val path = call.request.path()

    // Check if this is an SSE streaming request
    if (path == "/ask-ai/stream") {
      handleStreamRequest(call)
      return
    }

    // Check if this is a feedback POST request
    if (path == "/ask-ai/feedback" && call.request.httpMethod.value == "POST") {
      handleFeedbackRequest(call)
      return
    }

    // Check if this is a slug URL (/ask-ai/q/{slug})
    val slugMatch = SLUG_PATTERN.find(path)

    if (slugMatch != null) {
      handleSlugRequest(call, slugMatch.groupValues[1])
    } else {
      handleMainPage(call)
    }
  }

  private suspend fun handleStreamRequest(call: ApplicationCall) {
    val question =
        call.request.queryParameters["question"]
            ?: run {
              call.respond(
                  HttpStatusCode.BadRequest, mapOf("error" to "Missing question parameter"))
              return
            }
    val refresh = call.request.queryParameters["refresh"]?.toBoolean() ?: false
    val username =
        userInfo["preferred_username"] as? String
            ?: run {
              call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No username in session"))
              return
            }

    call.response.header("Cache-Control", "no-cache")
    call.response.header("X-Accel-Buffering", "no")

    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
      try {
        memberService.streamAskAi(session.sessionId, username, question, refresh) { chunk ->
          writeStringUtf8(chunk)
          flush()
        }
      } catch (e: Exception) {
        log.error("SSE streaming error for question: $question", e)
        writeStringUtf8("event: error\ndata: An error occurred\n\n")
        flush()
      }
    }
  }

  private suspend fun handleFeedbackRequest(call: ApplicationCall) {
    try {
      val body = call.receiveText()
      val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
      val jsonObj = json.jsonObject

      val cacheId = jsonObj["cacheId"]?.jsonPrimitive?.intOrNull
      val isHelpful = jsonObj["isHelpful"]?.jsonPrimitive?.booleanOrNull

      if (cacheId == null || isHelpful == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing cacheId or isHelpful"))
        return
      }

      val success = memberService.submitAskAiFeedback(session.sessionId, cacheId, isHelpful)

      if (success) {
        call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
      } else {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Failed to submit feedback"),
        )
      }
    } catch (e: Exception) {
      log.error("Error processing feedback request", e)
      call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
    }
  }

  private suspend fun handleMainPage(call: ApplicationCall) {
    log.info("Ask AI page requested by ${userInfo["preferred_username"]}")

    val username = userInfo["preferred_username"] as? String
    if (call.request.httpMethod.value == "GET" &&
        getCachedFeatureFlag(memberServiceClient, AGENT_CHAT_FLAG, username, session.sessionId)) {
      call.respond(
          ThymeleafContent(
              "agent-chat",
              userInfo +
                  mapOf(
                      "profile_url" to "./profile/@$username",
                      "botKey" to "ask-ai",
                      "botTitle" to "Ask AI")))
      return
    }

    val jsonMap: MutableMap<String, Any> = userInfo.toMutableMap()
    jsonMap["profile_url"] = "./profile/@${jsonMap["preferred_username"]}"
    jsonMap["isDevelopmentMode"] = appConfig.isDevelopmentMode()

    // Always fetch top questions for the initial page
    try {
      val topQuestions = memberService.getAskAiTopQuestions(session.sessionId, limit = 10)
      jsonMap["topQuestions"] = topQuestions
    } catch (e: Exception) {
      log.warn("Failed to fetch top questions", e)
      jsonMap["topQuestions"] = emptyList<Map<String, Any>>()
    }

    // Check if this is a POST request with a question
    if (call.request.httpMethod.value == "POST") {
      val startTime = System.currentTimeMillis()
      try {
        val formParams = call.receiveParameters()
        val question = formParams["question"] ?: ""
        val refresh = formParams["refresh"]?.toBoolean() ?: false
        val username = userInfo["preferred_username"] as? String

        if (question.isNotBlank()) {
          log.info("Processing question: $question (refresh=$refresh)")
          val response = memberService.askAi(session.sessionId, question, username, refresh)
          val elapsedMs = System.currentTimeMillis() - startTime

          jsonMap["question"] = question
          jsonMap["answer"] = response["answer"] ?: "No answer generated"
          jsonMap["sources"] = response["sources"] ?: emptyList<Map<String, Any>>()
          jsonMap["additionalResources"] =
              response["additionalResources"] ?: emptyList<Map<String, Any>>()
          jsonMap["fromCache"] = response["fromCache"] ?: false
          jsonMap["slug"] = response["slug"] ?: ""
          response["cacheId"]?.let { jsonMap["cacheId"] = it }
          response["askedByUsername"]?.let { jsonMap["askedByUsername"] = it }

          // Extract metadata for display
          val metadata = response["metadata"] as? Map<*, *>
          if (metadata != null) {
            jsonMap["searchQueries"] = metadata["searchQueries"] ?: emptyList<String>()
            (metadata["sourceBreakdown"] as? Map<*, *>)?.let { breakdown ->
              jsonMap["sourceBreakdown"] = breakdown
            }
            (metadata["classificationTokens"] as? Map<*, *>)?.let { tokens ->
              jsonMap["classificationTokens"] = tokens
            }
            (metadata["answerTokens"] as? Map<*, *>)?.let { tokens ->
              jsonMap["answerTokens"] = tokens
            }
            metadata["estimatedCostUsd"]?.let { cost -> jsonMap["estimatedCostUsd"] = cost }
            metadata["modelName"]?.let { model -> jsonMap["modelName"] = model }
          }

          // Debug metadata
          jsonMap["responseTimeMs"] = elapsedMs
          jsonMap["sourceCount"] = (response["sources"] as? List<*>)?.size ?: 0
        }
      } catch (e: Exception) {
        log.error("Error processing AI question", e)
        jsonMap["error"] =
            "An error occurred while processing your question. Please try again later."
      }
    }

    call.respond(ThymeleafContent("ask-ai", jsonMap))
  }

  private suspend fun handleSlugRequest(call: ApplicationCall, slug: String) {
    log.info("Ask AI slug page requested: $slug by ${userInfo["preferred_username"]}")

    val jsonMap: MutableMap<String, Any> = userInfo.toMutableMap()
    jsonMap["profile_url"] = "./profile/@${jsonMap["preferred_username"]}"
    jsonMap["isDevelopmentMode"] = appConfig.isDevelopmentMode()

    // Fetch the cached answer by slug
    val startTime = System.currentTimeMillis()
    try {
      val response = memberService.getAskAiBySlug(session.sessionId, slug)
      val elapsedMs = System.currentTimeMillis() - startTime

      if (response != null) {
        jsonMap["answer"] = response["answer"] ?: "No answer"
        jsonMap["sources"] = response["sources"] ?: emptyList<Map<String, Any>>()
        jsonMap["fromCache"] = true
        jsonMap["slug"] = slug
        response["cacheId"]?.let { jsonMap["cacheId"] = it }
        response["questionText"]?.let { jsonMap["question"] = it }
        response["askedByUsername"]?.let { jsonMap["askedByUsername"] = it }

        // Extract metadata for display
        val metadata = response["metadata"] as? Map<*, *>
        if (metadata != null) {
          jsonMap["searchQueries"] = metadata["searchQueries"] ?: emptyList<String>()
          (metadata["sourceBreakdown"] as? Map<*, *>)?.let { breakdown ->
            jsonMap["sourceBreakdown"] = breakdown
          }
          (metadata["classificationTokens"] as? Map<*, *>)?.let { tokens ->
            jsonMap["classificationTokens"] = tokens
          }
          (metadata["answerTokens"] as? Map<*, *>)?.let { tokens ->
            jsonMap["answerTokens"] = tokens
          }
          metadata["estimatedCostUsd"]?.let { cost -> jsonMap["estimatedCostUsd"] = cost }
          metadata["modelName"]?.let { model -> jsonMap["modelName"] = model }
        }

        // Debug metadata
        jsonMap["responseTimeMs"] = elapsedMs
        jsonMap["sourceCount"] = (response["sources"] as? List<*>)?.size ?: 0
      } else {
        jsonMap["error"] = "Answer not found. It may have been removed from the cache."
      }
    } catch (e: Exception) {
      log.error("Error fetching cached answer for slug: $slug", e)
      jsonMap["error"] = "An error occurred while loading the answer."
    }

    // Fetch top questions for the page
    try {
      val topQuestions = memberService.getAskAiTopQuestions(session.sessionId, limit = 10)
      jsonMap["topQuestions"] = topQuestions
    } catch (e: Exception) {
      log.warn("Failed to fetch top questions", e)
      jsonMap["topQuestions"] = emptyList<Map<String, Any>>()
    }

    call.respond(ThymeleafContent("ask-ai", jsonMap))
  }

  companion object {
    private val SLUG_PATTERN = Regex("/ask-ai/q/([^/]+)")
    const val AGENT_CHAT_FLAG = "agent-chat.enabled"
  }
}
