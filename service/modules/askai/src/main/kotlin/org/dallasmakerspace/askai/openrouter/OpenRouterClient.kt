package org.dallasmakerspace.askai.openrouter

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okio.IOException
import org.dallasmakerspace.askai.AbstractLlmClient
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.TokenUsage

private const val REQUEST_TIMEOUT_MS = 60000L
private const val STREAM_SOCKET_TIMEOUT_MS = 300_000L

@Singleton
class OpenRouterClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) :
    AbstractLlmClient(loggerFactory) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val baseUrl: String by lazy { appConfig.requireStringProperty("app.openrouter.baseUrl") }

  private val apiKey: String by lazy { appConfig.requireStringProperty("app.openrouter.apiKey") }

  private val model: String by lazy { appConfig.requireStringProperty("app.openrouter.model") }

  private val client: HttpClient by lazy {
    HttpClient(DmsHttpEngine) {
      install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
      }
      install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.INFO
      }
    }
  }

  private val streamingClient: HttpClient by lazy {
    HttpClient(DmsHttpEngine) {
      install(HttpTimeout) {
        requestTimeoutMillis = Long.MAX_VALUE
        connectTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MS
      }
      install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.INFO
      }
    }
  }

  // Pricing: google/gemini-2.5-flash-lite — $0.10/$0.40 per million input/output tokens
  override fun estimateCost(inputTokens: Int, outputTokens: Int): Double =
      inputTokens * 0.1 / 1_000_000.0 + outputTokens * 0.4 / 1_000_000.0

  override suspend fun chatCompletion(
      messages: List<ChatMessage>,
      jsonMode: Boolean,
  ): LlmResult<String> {
    val request =
        OpenRouterChatRequest(
            model = model,
            messages = messages,
            responseFormat = if (jsonMode) ResponseFormat(type = "json_object") else null)

    val requestBody = json.encodeToString(OpenRouterChatRequest.serializer(), request)

    try {
      val response =
          client.request("$baseUrl/chat/completions") {
            method = HttpMethod.Post
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "https://portal.dallasmakerspace.org")
            header("X-Title", "Dallas Makerspace Member Portal")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          val responseBody = response.bodyAsText()
          val chatResponse = json.decodeFromString<OpenRouterChatResponse>(responseBody)
          val content =
              chatResponse.choices.firstOrNull()?.message?.content
                  ?: throw OpenRouterApiException("No response content from OpenRouter")
          val usage =
              chatResponse.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens)
              }
          return LlmResult(result = content, usage = usage, modelName = chatResponse.model)
        }
        HttpStatusCode.TooManyRequests -> {
          throw OpenRouterApiException("Rate limited by OpenRouter API")
        }
        HttpStatusCode.Unauthorized -> {
          throw OpenRouterApiException("Invalid OpenRouter API key")
        }
        else -> {
          throw OpenRouterApiException(
              "OpenRouter API error: ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: IOException) {
      throw OpenRouterApiException("Failed to connect to OpenRouter API", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw OpenRouterApiException("Failed to parse OpenRouter response", e)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun generateAnswerStream(
      question: String,
      searchResults: List<SearchResult>,
      onToken: suspend (String) -> Unit,
  ) {
    val messages = buildAnswerMessages(question, searchResults)
    val request = OpenRouterChatRequest(model = model, messages = messages, stream = true)
    val requestBody = json.encodeToString(OpenRouterChatRequest.serializer(), request)

    streamingClient
        .preparePost("$baseUrl/chat/completions") {
          header("Authorization", "Bearer $apiKey")
          header("HTTP-Referer", "https://portal.dallasmakerspace.org")
          header("X-Title", "Dallas Makerspace Member Portal")
          contentType(ContentType.Application.Json)
          setBody(requestBody)
        }
        .execute { response ->
          val channel = response.bodyAsChannel()
          while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ")
            if (data == "[DONE]") break
            try {
              val chunk = json.decodeFromString<OpenRouterStreamChunk>(data)
              chunk.choices
                  .firstOrNull()
                  ?.delta
                  ?.content
                  ?.takeIf { it.isNotEmpty() }
                  ?.let { onToken(it) }
            } catch (e: Exception) {
              log.debug("Skipped malformed stream chunk: ${e.message}")
            }
          }
        }
  }
}

/** Exception thrown when OpenRouter API calls fail. */
class OpenRouterApiException : IOException {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Throwable) : super(message, cause)
}
