package org.dallasmakerspace.askai.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for OpenRouter chat completions API. */
@Serializable
data class OpenRouterChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    val stream: Boolean = false,
)

/** A single chunk in a streaming chat completion response. */
@Serializable
data class OpenRouterStreamChunk(
    val choices: List<StreamChoice> = emptyList(),
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable data class StreamDelta(val content: String? = null)

@Serializable data class ChatMessage(val role: String, val content: String)

@Serializable data class ResponseFormat(val type: String)

/** Response from OpenRouter chat completions API. */
@Serializable
data class OpenRouterChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null,
)

@Serializable data class Choice(val index: Int, val message: ChatMessage)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)
