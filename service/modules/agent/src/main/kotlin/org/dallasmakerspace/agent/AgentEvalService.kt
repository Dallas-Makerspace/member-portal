package org.dallasmakerspace.agent

import ai.koog.prompt.message.Message
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.dallasmakerspace.agent.db.AgentRoles
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.models.agent.AgentEvalArtifact
import org.dallasmakerspace.models.agent.AgentEvalResponse
import org.dallasmakerspace.models.agent.AgentEvalToolCall
import org.dallasmakerspace.models.agent.AgentEvalTurn
import org.dallasmakerspace.models.agent.AgentVisibility

sealed interface EvalOutcome {
  data class Done(val response: AgentEvalResponse) : EvalOutcome

  data object Disabled : EvalOutcome

  data object UnknownBot : EvalOutcome

  data class BadRequest(val message: String) : EvalOutcome
}

/**
 * Runs test questions through a bot for the evaluation scripts. Nothing is saved: the chat lives
 * in memory for the call. Only active when the service runs with AGENT_EVAL_ENABLED=true; an
 * environment variable rather than a config flag, because dev and prod share a database.
 */
@Singleton
class AgentEvalService
@Inject
constructor(
    bots: Set<@JvmSuppressWildcards BotDefinition>,
    modelClient: ChatModelClient,
    private val configOverrideService: ConfigOverrideService,
    private val spendCap: AgentSpendCap,
    appConfig: AppConfig,
    private val conversations: ConversationRepository,
    private val artifactRepository: ArtifactRepository,
) {
  private val enabled = appConfig.getStringProperty("app.agent-eval-enabled", "false") == "true"
  private val botsByKey = bots.associateBy { it.key }
  private val runtime = AgentRuntime(modelClient)
  private val concurrency = Semaphore(MAX_CONCURRENT_RUNS)

  suspend fun run(botKey: String, messages: List<String>, model: String?): EvalOutcome {
    if (!enabled) return EvalOutcome.Disabled
    val bot = botsByKey[botKey] ?: return EvalOutcome.UnknownBot
    if (messages.isEmpty() || messages.size > MAX_MESSAGES || messages.any { it.isBlank() }) {
      return EvalOutcome.BadRequest("Send 1 to $MAX_MESSAGES non-empty messages")
    }
    if (model != null && model !in ALLOWED_MODELS) {
      return EvalOutcome.BadRequest("Model must be one of ${ALLOWED_MODELS.joinToString()}")
    }
    val modelId = model ?: configOverrideService.get(ConfigRegistry.AGENT_MODEL_DEFAULT)
    val conversationId = UUID.randomUUID().toString()
    val context = ToolContext(EVAL_USERNAME, conversationId, bot.key)
    // Artifact tools need a conversation row; it is deleted (with its documents) after the run.
    val usesArtifacts = bot.tools.any { it.descriptor.name == "create_artifact" }
    if (usesArtifacts) {
      conversations.create(conversationId, bot.key, EVAL_USERNAME, "Eval run", AgentVisibility.PRIVATE, "Eval run")
    }
    val noEvents = AgentEventSink { _, _, _, _, _, _ -> }
    val started = System.currentTimeMillis()
    var documents = emptyList<AgentEvalArtifact>()
    val turns =
        try {
          concurrency.withPermit {
            val history = mutableListOf<Message>()
            messages
                .mapIndexed { i, question ->
                  val state = TurnState(i + 1, question.trim())
                  val turnStarted = System.currentTimeMillis()
                  runtime.runTurn(bot, modelId, context, history.toList(), state, noEvents)
                  history += state.messages.map { it.first }
                  toTurn(question.trim(), state, System.currentTimeMillis() - turnStarted)
                }
                .also { if (usesArtifacts) documents = collectDocuments(conversationId) }
          }
        } finally {
          if (usesArtifacts) conversations.delete(conversationId)
        }
    val cost = turns.sumOf { it.costUsd }
    spendCap.record(cost)
    return EvalOutcome.Done(
        AgentEvalResponse(modelId, turns, cost, System.currentTimeMillis() - started, documents))
  }

  private suspend fun collectDocuments(conversationId: String): List<AgentEvalArtifact> =
      artifactRepository.listFor(conversationId).mapNotNull { a ->
        artifactRepository.version(a.id, a.currentVersion)?.let {
          AgentEvalArtifact(a.kind, a.title, a.currentVersion, it.content)
        }
      }

  private fun toTurn(question: String, state: TurnState, latencyMs: Long): AgentEvalTurn {
    val rows = state.messages.map { it.second }
    val answers = rows.filter { it.role == AgentRoles.ASSISTANT }
    val answer = answers.mapNotNull { it.displayText }.joinToString("\n\n")
    return AgentEvalTurn(
        question = question,
        answer = answer,
        toolCalls =
            state.toolCalls.map { AgentEvalToolCall(it.toolName, it.args, it.ok, it.durationMs) },
        sources = state.sources,
        inputTokens = answers.sumOf { it.usage.input },
        cacheReadTokens = answers.sumOf { it.usage.cacheRead },
        outputTokens = answers.sumOf { it.usage.output },
        costUsd = state.costUsd,
        latencyMs = latencyMs,
        gaveUp = answer.contains(AgentRuntime.GAVE_UP_ANSWER),
    )
  }

  companion object {
    const val EVAL_USERNAME = "eval-bot"
    private const val MAX_CONCURRENT_RUNS = 3
    private const val MAX_MESSAGES = 6
    val ALLOWED_MODELS =
        setOf(
            "us.anthropic.claude-haiku-5-5",
            "us.anthropic.claude-sonnet-5-5",
            "us.openai.gpt-6.1-sol",
        )
  }
}
