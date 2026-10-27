package org.dallasmakerspace.models.agent

import kotlinx.serialization.Serializable

/** A chat bot the current member may use. */
@Serializable
data class AgentBotInfo(
    val key: String,
    val displayName: String,
    val starterPrompts: List<String> = emptyList(),
)

@Serializable
enum class AgentVisibility {
  PUBLIC,
  PRIVATE,
}

/** Timestamps are ISO-8601 UTC strings. */
@Serializable
data class AgentConversationSummary(
    val id: String,
    val botKey: String,
    val title: String,
    val ownerUsername: String,
    val visibility: AgentVisibility,
    val createdAt: String,
    val lastMessageAt: String,
)

@Serializable
data class AgentSourceLink(val title: String, val url: String, val source: String)

@Serializable
data class AgentToolCallSummary(
    val name: String,
    val argsPreview: String,
    val ok: Boolean = true,
)

/** A user or assistant message as shown in the chat. Tool results are folded into [toolCalls]. */
@Serializable
data class AgentChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val toolCalls: List<AgentToolCallSummary> = emptyList(),
    val sources: List<AgentSourceLink> = emptyList(),
    val createdAt: String,
    val feedback: Boolean? = null,
)

@Serializable
data class AgentConversationDetail(
    val conversation: AgentConversationSummary,
    val messages: List<AgentChatMessage>,
    val privateReason: String? = null,
    val activeTurn: Int? = null,
    val isOwner: Boolean = false,
)

@Serializable data class AgentCreateConversationRequest(val firstMessage: String? = null)

@Serializable data class AgentSendMessageRequest(val text: String)

@Serializable data class AgentTurnStarted(val conversationId: String, val turnSeq: Int)

@Serializable data class AgentFeedbackRequest(val messageId: Long, val helpful: Boolean)

/**
 * One server-sent event of a running turn. SSE `event:` is [type] and `id:` is [id] (`turn:n`).
 * Types: turn-start, delta, tool-start, tool-end, sources, visibility, turn-end, busy, error.
 */
@Serializable
data class AgentStreamEvent(
    val type: String,
    val id: String,
    val text: String? = null,
    val tool: String? = null,
    val argsPreview: String? = null,
    val ok: Boolean? = null,
    val sources: List<AgentSourceLink>? = null,
    val visibility: AgentVisibility? = null,
    val messageId: Long? = null,
    val costUsd: Double? = null,
)

/** Eval run: [messages] are asked in order in one throwaway chat; [model] overrides the default. */
@Serializable
data class AgentEvalRequest(val messages: List<String>, val model: String? = null)

@Serializable
data class AgentEvalToolCall(
    val name: String,
    val args: String,
    val ok: Boolean,
    val durationMs: Int,
)

@Serializable
data class AgentEvalTurn(
    val question: String,
    val answer: String,
    val toolCalls: List<AgentEvalToolCall>,
    val sources: List<AgentSourceLink>,
    val inputTokens: Int,
    val cacheReadTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val latencyMs: Long,
    val gaveUp: Boolean,
)

@Serializable
data class AgentEvalResponse(
    val model: String,
    val turns: List<AgentEvalTurn>,
    val totalCostUsd: Double,
    val totalLatencyMs: Long,
)
