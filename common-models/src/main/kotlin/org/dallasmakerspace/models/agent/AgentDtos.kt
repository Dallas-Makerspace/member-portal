package org.dallasmakerspace.models.agent

import kotlinx.serialization.Serializable

/** A chat bot the current member may use. */
@Serializable
data class AgentBotInfo(
    val key: String,
    val displayName: String,
    val starterPrompts: List<String> = emptyList(),
    val intro: String = "",
    val placeholder: String = "",
    /** True when every chat is private, so the page has nothing shared to show. */
    val alwaysPrivate: Boolean = false,
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
    val artifacts: List<AgentArtifactSummary> = emptyList(),
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
    val artifacts: List<AgentEvalArtifact> = emptyList(),
)

@Serializable
data class AgentArtifactSummary(
    val id: String,
    val kind: String,
    val title: String,
    val currentVersion: Int,
    val updatedAt: String,
)

@Serializable
data class AgentArtifactVersionInfo(val version: Int, val author: String, val createdAt: String)

/** One version of an artifact, with the list of all versions and the export formats on offer. */
@Serializable
data class AgentArtifactDetail(
    val artifact: AgentArtifactSummary,
    val version: Int,
    val content: String,
    val versions: List<AgentArtifactVersionInfo>,
    val exportFormats: List<String>,
    val canEdit: Boolean,
)

/** [basedOn] is the version the member was looking at; a newer one makes the save fail. */
@Serializable data class AgentArtifactSaveRequest(val content: String, val basedOn: Int)

/** A rendered export; the browser turns it into a download. */
@Serializable data class AgentArtifactExport(val fileName: String, val contentType: String, val body: String)

/** A document a bot wrote during an eval run. */
@Serializable
data class AgentEvalArtifact(val kind: String, val title: String, val version: Int, val content: String)

@Serializable data class AgentRenameRequest(val title: String)
