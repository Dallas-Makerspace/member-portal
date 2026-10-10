package org.dallasmakerspace.askai.bedrock

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region.US_EAST_1
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

private const val TITAN_EMBED_MODEL_ID = "amazon.titan-embed-text-v2:0"
private const val EMBED_DIMENSIONS = 1024

@Singleton
class BedrockEmbeddingClient @Inject constructor(loggerFactory: LoggerFactory) : IEmbeddingClient {

  private val log = loggerFactory.create(javaClass)

  private val json = Json { ignoreUnknownKeys = true }

  private val client: BedrockRuntimeClient by lazy {
    BedrockRuntimeClient.builder()
        .region(US_EAST_1)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()
  }

  override suspend fun embed(text: String): FloatArray? {
    return withContext(Dispatchers.IO) {
      try {
        val requestBody =
            json.encodeToString(
                TitanEmbedRequest(
                    inputText = text, dimensions = EMBED_DIMENSIONS, normalize = true))

        val response =
            client.invokeModel(
                InvokeModelRequest.builder()
                    .modelId(TITAN_EMBED_MODEL_ID)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build())

        val responseJson = Json.parseToJsonElement(response.body().asUtf8String())
        val embeddingArray =
            responseJson.jsonObject["embedding"]?.jsonArray
                ?: run {
                  log.warn("No embedding field in Bedrock Titan response")
                  return@withContext null
                }

        embeddingArray.map { it.jsonPrimitive.floatOrNull ?: 0f }.toFloatArray()
      } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        log.warn("Failed to generate embedding (non-fatal)", e)
        null
      }
    }
  }

  @Serializable
  private data class TitanEmbedRequest(
      val inputText: String,
      val dimensions: Int,
      val normalize: Boolean,
  )
}
