package org.dallasmakerspace.askai.bedrock

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dallasmakerspace.askai.AbstractLlmClient
import org.dallasmakerspace.askai.openrouter.ChatMessage
import org.dallasmakerspace.askai.openrouter.LlmResult
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.TokenUsage
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region.US_EAST_1
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock

private const val MAX_TOKENS = 2048
private const val TEMPERATURE = 0.7f

@Singleton
class BedrockClient
@Inject
constructor(
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) : AbstractLlmClient(loggerFactory) {

  private val modelId: String by lazy { appConfig.requireStringProperty("app.bedrock.model") }

  private val client: BedrockRuntimeClient by lazy {
    BedrockRuntimeClient.builder()
        .region(US_EAST_1)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()
  }

  private val asyncClient: BedrockRuntimeAsyncClient by lazy {
    BedrockRuntimeAsyncClient.builder()
        .region(US_EAST_1)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()
  }

  // Pricing: us.anthropic.claude-haiku-4-5 — $1.00/$5.00 per million input/output tokens
  override fun estimateCost(inputTokens: Int, outputTokens: Int): Double =
      inputTokens * 1.0 / 1_000_000.0 + outputTokens * 5.0 / 1_000_000.0

  override suspend fun chatCompletion(
      messages: List<ChatMessage>,
      jsonMode: Boolean,
  ): LlmResult<String> {
    val systemBlocks =
        messages
            .filter { it.role == "system" }
            .map { SystemContentBlock.builder().text(it.content).build() }

    val conversationMessages =
        messages
            .filter { it.role != "system" }
            .map { msg ->
              val role =
                  if (msg.role == "user") ConversationRole.USER else ConversationRole.ASSISTANT
              Message.builder().role(role).content(ContentBlock.fromText(msg.content)).build()
            }

    val requestBuilder =
        ConverseRequest.builder()
            .modelId(modelId)
            .messages(conversationMessages)
            .inferenceConfig(
                InferenceConfiguration.builder()
                    .maxTokens(MAX_TOKENS)
                    .temperature(TEMPERATURE)
                    .build())

    if (systemBlocks.isNotEmpty()) {
      requestBuilder.system(systemBlocks)
    }

    val response =
        withContext(Dispatchers.IO) {
          try {
            client.converse(requestBuilder.build())
          } catch (e: SdkException) {
            throw BedrockApiException("Bedrock API error: ${e.message}", e)
          }
        }

    val text =
        response
            .output()
            .message()
            .content()
            .firstOrNull { it.type() == ContentBlock.Type.TEXT }
            ?.text() ?: throw BedrockApiException("No text content in Bedrock response")

    val usage =
        response.usage()?.let {
          TokenUsage(
              promptTokens = it.inputTokens(),
              completionTokens = it.outputTokens(),
              totalTokens = it.inputTokens() + it.outputTokens(),
          )
        }

    return LlmResult(result = text, usage = usage, modelName = modelId)
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun generateAnswerStream(
      question: String,
      searchResults: List<SearchResult>,
      onToken: suspend (String) -> Unit,
  ) {
    val messages = buildAnswerMessages(question, searchResults)
    val systemBlocks =
        messages
            .filter { it.role == "system" }
            .map { SystemContentBlock.builder().text(it.content).build() }
    val conversationMessages =
        messages
            .filter { it.role != "system" }
            .map { msg ->
              val role =
                  if (msg.role == "user") ConversationRole.USER else ConversationRole.ASSISTANT
              Message.builder().role(role).content(ContentBlock.fromText(msg.content)).build()
            }

    val requestBuilder =
        ConverseStreamRequest.builder()
            .modelId(modelId)
            .messages(conversationMessages)
            .inferenceConfig(
                InferenceConfiguration.builder()
                    .maxTokens(MAX_TOKENS)
                    .temperature(TEMPERATURE)
                    .build())
    if (systemBlocks.isNotEmpty()) requestBuilder.system(systemBlocks)

    val tokenChannel = Channel<String>(Channel.UNLIMITED)

    coroutineScope {
      launch {
        try {
          asyncClient
              .converseStream(
                  requestBuilder.build(),
                  ConverseStreamResponseHandler.builder()
                      .subscriber(
                          ConverseStreamResponseHandler.Visitor.builder()
                              .onContentBlockDelta { event ->
                                val text =
                                    if (event.delta().type() == ContentBlockDelta.Type.TEXT)
                                        event.delta().text()
                                    else null
                                if (!text.isNullOrEmpty()) tokenChannel.trySend(text)
                              }
                              .build())
                      .build())
              .await()
          tokenChannel.close()
        } catch (e: SdkException) {
          tokenChannel.close(BedrockApiException("Bedrock streaming error: ${e.message}", e))
        } catch (e: Exception) {
          tokenChannel.close(BedrockApiException("Unexpected streaming error: ${e.message}", e))
        }
      }

      for (token in tokenChannel) {
        onToken(token)
      }
    }
  }
}

class BedrockApiException : Exception {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Throwable) : super(message, cause)
}
