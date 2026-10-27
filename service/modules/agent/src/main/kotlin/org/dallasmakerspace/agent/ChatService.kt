package org.dallasmakerspace.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import org.dallasmakerspace.agent.db.AgentRoles
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.agent.db.ConversationRow
import org.dallasmakerspace.agent.db.MessageRow
import org.dallasmakerspace.agent.db.NewMessage
import org.dallasmakerspace.agent.db.ToolCallRow
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.agent.AgentBotInfo
import org.dallasmakerspace.models.agent.AgentChatMessage
import org.dallasmakerspace.models.agent.AgentConversationDetail
import org.dallasmakerspace.models.agent.AgentConversationSummary
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.dallasmakerspace.models.agent.AgentStreamEvent
import org.dallasmakerspace.models.agent.AgentToolCallSummary
import org.dallasmakerspace.models.agent.AgentVisibility

sealed interface RenameOutcome {
  data object Renamed : RenameOutcome

  data object NotFound : RenameOutcome

  data class Invalid(val message: String) : RenameOutcome
}

/** Outcome of asking a question; routes map these to HTTP statuses. */
sealed interface TurnOutcome {
  data class Started(val conversationId: String, val turnSeq: Int) : TurnOutcome

  data class Refused(val message: String) : TurnOutcome

  data object NotFound : TurnOutcome

  data object Forbidden : TurnOutcome

  data object AlreadyRunning : TurnOutcome
}

/** Runs AI assistant chats: access checks, limits, the agent loop, streaming and storage. */
@Singleton
@Suppress("TooManyFunctions", "LongParameterList")
class ChatService
@Inject
constructor(
    bots: Set<@JvmSuppressWildcards BotDefinition>,
    private val repository: ConversationRepository,
    private val access: AgentAccess,
    private val spendCap: AgentSpendCap,
    modelClient: ChatModelClient,
    private val configOverrideService: ConfigOverrideService,
    private val piiMasker: PiiMasker,
    private val turnStreams: TurnStreams,
    loggerFactory: LoggerFactory,
    private val artifacts: ArtifactRepository,
) {
  private val log = loggerFactory.create(javaClass)
  private val botsByKey = bots.associateBy { it.key }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val concurrency = Semaphore(MAX_CONCURRENT_TURNS)
  private val runtime = AgentRuntime(modelClient)

  fun bot(key: String): BotDefinition? = botsByKey[key]

  suspend fun availableBots(username: String): List<AgentBotInfo> {
    val groups = access.groupsOf(username)
    return botsByKey.values
        .filter { access.canUse(it, username, groups) }
        .map {
          AgentBotInfo(
              it.key, it.displayName, it.starterPrompts, it.intro, it.placeholder, it.startsPrivate)
        }
  }

  suspend fun startConversation(botKey: String, username: String, text: String): TurnOutcome {
    val bot = botsByKey[botKey] ?: return TurnOutcome.NotFound
    if (!access.canUse(bot, username, access.groupsOf(username))) return TurnOutcome.Forbidden
    spendCap.refusal(username, now())?.let {
      return TurnOutcome.Refused(it)
    }
    val reason =
        when {
          bot.startsPrivate -> "This assistant's chats are always private"
          piiMasker.containsPii(text) -> PII_REASON
          else -> null
        }
    val now = now()
    val conversation =
        ConversationRow(
            id = UUID.randomUUID().toString(),
            botKey = botKey,
            ownerUsername = username,
            title = if (reason == PII_REASON) PRIVATE_TITLE else titleFrom(text),
            visibility = if (reason == null) AgentVisibility.PUBLIC else AgentVisibility.PRIVATE,
            privateReason = reason,
            totalCostUsd = 0.0,
            createdAt = now,
            lastMessageAt = now)
    repository.create(
        conversation.id,
        botKey,
        username,
        conversation.title,
        conversation.visibility,
        reason,
        now)
    return launchTurn(bot, conversation, username, text, emptyList())
  }

  suspend fun sendMessage(conversationId: String, username: String, text: String): TurnOutcome {
    val conversation = repository.get(conversationId) ?: return TurnOutcome.NotFound
    if (conversation.ownerUsername != username) return TurnOutcome.Forbidden
    val bot = botsByKey[conversation.botKey] ?: return TurnOutcome.NotFound
    if (!access.canUse(bot, username, access.groupsOf(username))) return TurnOutcome.Forbidden
    if (turnStreams.isRunning(conversationId)) return TurnOutcome.AlreadyRunning
    spendCap.refusal(username, now())?.let {
      return TurnOutcome.Refused(it)
    }
    if (piiMasker.containsPii(text)) repository.makePrivate(conversationId, PII_REASON)
    return launchTurn(bot, conversation, username, text, repository.messages(conversationId))
  }

  @Suppress("TooGenericExceptionCaught", "LongMethod")
  private suspend fun launchTurn(
      bot: BotDefinition,
      conversation: ConversationRow,
      username: String,
      text: String,
      history: List<MessageRow>,
  ): TurnOutcome {
    val turnSeq = (history.maxOfOrNull { it.turnSeq } ?: 0) + 1
    val turn = turnStreams.start(conversation.id, turnSeq) ?: return TurnOutcome.AlreadyRunning
    val modelId = configOverrideService.get(ConfigRegistry.AGENT_MODEL_DEFAULT)
    val state = TurnState(turnSeq, text, editNote(conversation.id, history))
    val sink = AgentEventSink { type, delta, tool, args, ok, sources ->
      turn.publish(
          AgentStreamEvent(
              type,
              turn.nextId(),
              text = delta,
              tool = tool,
              argsPreview = args,
              ok = ok,
              sources = sources))
    }
    turn.job =
        scope.launch {
          var acquired = false
          var failure: String? = null
          try {
            turn.publish(AgentStreamEvent("turn-start", turn.nextId()))
            acquired = concurrency.tryAcquire()
            if (!acquired) {
              turn.publish(AgentStreamEvent("busy", turn.nextId()))
              acquired = withTimeoutOrNull(QUEUE_TIMEOUT_MILLIS) { concurrency.acquire() } != null
            }
            if (!acquired) {
              failure = BUSY_MESSAGE
            } else {
              runtime.runTurn(
                  bot,
                  modelId,
                  ToolContext(username, conversation.id, bot.key),
                  history.map { TurnState.decode(it.content) },
                  state,
                  sink)
            }
          } catch (e: CancellationException) {
            failure = null
            log.info("Turn $turnSeq of ${conversation.id} was stopped")
          } catch (e: Exception) {
            log.error("Turn $turnSeq of ${conversation.id} failed", e)
            failure = ERROR_MESSAGE
          } finally {
            if (acquired) concurrency.release()
            withContext(NonCancellable) { finishTurn(conversation, turn, state, failure) }
          }
        }
    return TurnOutcome.Started(conversation.id, turnSeq)
  }

  /** Tells the model about documents the member changed since its last reply. */
  private suspend fun editNote(conversationId: String, history: List<MessageRow>): String? {
    val since = history.lastOrNull()?.createdAt ?: return null
    val edited = artifacts.editedByMembersSince(conversationId, since)
    if (edited.isEmpty()) return null
    val list = edited.joinToString { "\"${it.title}\" (id ${it.id}, now version ${it.currentVersion})" }
    return "[Note from the app: the member edited $list since your last reply. " +
        "Read it again before changing it; keep their edits.]"
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun finishTurn(
      conversation: ConversationRow,
      turn: ActiveTurn,
      state: TurnState,
      failure: String?,
  ) {
    var answerId: Long? = null
    try {
      val rows = completedRows(state, failure)
      val ids = repository.appendTurn(conversation.id, rows, state.toolCalls)
      answerId = ids.getOrNull(rows.indexOfLast { it.role == AgentRoles.ASSISTANT })
      spendCap.record(state.costUsd)
      state.privateReason?.let {
        if (conversation.visibility == AgentVisibility.PUBLIC) {
          repository.makePrivate(conversation.id, it)
          turn.publish(
              AgentStreamEvent("visibility", turn.nextId(), visibility = AgentVisibility.PRIVATE))
        }
      }
      if (state.sources.isNotEmpty()) {
        turn.publish(AgentStreamEvent("sources", turn.nextId(), sources = state.sources))
      }
      if (failure != null) turn.publish(AgentStreamEvent("error", turn.nextId(), text = failure))
    } catch (e: Exception) {
      log.error("Failed to save turn ${state.turnSeq} of ${conversation.id}", e)
      turn.publish(AgentStreamEvent("error", turn.nextId(), text = ERROR_MESSAGE))
    } finally {
      // Mark finished first so a client reacting to turn-end can ask its next question.
      turnStreams.finish(turn)
      turn.publish(
          AgentStreamEvent("turn-end", turn.nextId(), messageId = answerId, costUsd = state.costUsd))
    }
  }

  /** A stopped or failed turn still ends with an assistant message so history stays valid. */
  private fun completedRows(state: TurnState, failure: String?): List<NewMessage> {
    val rows = state.messages.map { it.second }.toMutableList()
    val endsWithAnswer = rows.last().role == AgentRoles.ASSISTANT && rows.last().displayText != null
    if (!endsWithAnswer) {
      val text =
          state.partialText.toString().ifBlank { null }?.let { "$it\n\n$STOPPED_SUFFIX" }
              ?: failure
              ?: STOPPED_MESSAGE
      val message = Message.Assistant(text, ResponseMetaInfo.create(KoogClock.System))
      rows += NewMessage(state.turnSeq, AgentRoles.ASSISTANT, TurnState.encode(message), text)
    }
    return rows
  }

  fun activeTurn(conversationId: String): ActiveTurn? = turnStreams.get(conversationId)

  /** Null when the conversation doesn't exist or [username] may not see it. */
  suspend fun viewable(conversationId: String, username: String): ConversationRow? =
      repository.get(conversationId)?.takeIf { canView(it, username) }

  private suspend fun canView(conversation: ConversationRow, username: String): Boolean {
    if (conversation.ownerUsername == username) return true
    if (conversation.visibility != AgentVisibility.PUBLIC) return false
    val bot = botsByKey[conversation.botKey] ?: return false
    return access.canUse(bot, username, access.groupsOf(username))
  }

  suspend fun detail(conversationId: String, username: String): AgentConversationDetail? {
    val bundle = repository.bundle(conversationId, username) ?: return null
    val conversation = bundle.conversation.takeIf { canView(it, username) } ?: return null
    val chat = toChatMessages(bundle.messages, bundle.toolCalls)
    val feedback = bundle.feedback
    val running = turnStreams.get(conversationId)?.takeIf { turnStreams.isRunning(conversationId) }
    return AgentConversationDetail(
        conversation = conversation.toSummary(),
        messages = chat.map { it.copy(feedback = feedback[it.id]) },
        privateReason = conversation.privateReason,
        activeTurn = running?.turnSeq,
        isOwner = conversation.ownerUsername == username,
        artifacts = bundle.artifacts.map(::toArtifactSummary),
    )
  }

  suspend fun list(botKey: String, username: String, mine: Boolean, limit: Int):
      List<AgentConversationSummary>? {
    val bot = botsByKey[botKey] ?: return null
    if (!access.canUse(bot, username, access.groupsOf(username))) return null
    val rows =
        if (mine) repository.listByOwner(botKey, username, limit)
        else repository.listPublic(botKey, limit)
    return rows.map { it.toSummary() }
  }

  suspend fun cancel(conversationId: String, username: String): Boolean {
    val conversation = repository.get(conversationId) ?: return false
    if (conversation.ownerUsername != username) return false
    turnStreams.get(conversationId)?.job?.cancel()
    return true
  }

  /** Why the title can't be used, or null once it is saved. NotFound when it is not the owner's. */
  suspend fun rename(conversationId: String, username: String, title: String): RenameOutcome {
    val conversation = repository.get(conversationId) ?: return RenameOutcome.NotFound
    if (conversation.ownerUsername != username) return RenameOutcome.NotFound
    val clean = title.trim().replace(Regex("""\s+"""), " ")
    if (clean.isEmpty() || clean.length > MAX_TITLE_CHARS) {
      return RenameOutcome.Invalid("Use 1 to $MAX_TITLE_CHARS characters.")
    }
    if (piiMasker.containsPii(clean)) return RenameOutcome.Invalid("Titles can't include personal details.")
    repository.rename(conversationId, clean)
    return RenameOutcome.Renamed
  }

  suspend fun delete(conversationId: String, username: String): Boolean {
    val conversation = repository.get(conversationId) ?: return false
    if (conversation.ownerUsername != username) return false
    turnStreams.get(conversationId)?.job?.cancel()
    repository.delete(conversationId)
    return true
  }

  suspend fun feedback(
      conversationId: String,
      username: String,
      messageId: Long,
      helpful: Boolean,
  ): Boolean {
    viewable(conversationId, username) ?: return false
    if (repository.messages(conversationId).none { it.id == messageId }) return false
    repository.setFeedback(messageId, username, helpful)
    return true
  }

  suspend fun deleteExpired(): Int = repository.deleteInactiveSince(now().minusYears(RETENTION_YEARS))

  /** Deletes chats past the retention period once a night (03:30 Central). */
  @Suppress("TooGenericExceptionCaught")
  fun startDailyCleanup() {
    if (!cleanupStarted.compareAndSet(false, true)) return
    scope.launch {
      while (true) {
        delay(millisUntilNextCleanup(now()))
        try {
          log.info("Deleted ${deleteExpired()} AI assistant chats past retention")
        } catch (e: Exception) {
          log.error("AI assistant chat cleanup failed", e)
        }
      }
    }
  }

  private val cleanupStarted = AtomicBoolean(false)

  companion object {
    const val MAX_CONCURRENT_TURNS = 5
    private const val QUEUE_TIMEOUT_MILLIS = 60_000L
    private const val TITLE_CHARS = 120
    private const val MAX_TITLE_CHARS = 120
    private const val RETENTION_YEARS = 2L
    const val PII_REASON = "The question contained personal details"
    const val PRIVATE_TITLE = "Private question"
    const val BUSY_MESSAGE = "The assistant is busy right now. Please try again in a minute."
    const val ERROR_MESSAGE = "Something went wrong while answering. Please try again."
    const val STOPPED_MESSAGE = "(Stopped before answering.)"
    const val STOPPED_SUFFIX = "(Stopped.)"
    private val sourcesSerializer = ListSerializer(AgentSourceLink.serializer())

    fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    private const val CLEANUP_HOUR = 3
    private const val CLEANUP_MINUTE = 30

    fun millisUntilNextCleanup(nowUtc: LocalDateTime): Long {
      val central = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("America/Chicago"))
      var next = central.withHour(CLEANUP_HOUR).withMinute(CLEANUP_MINUTE).withSecond(0).withNano(0)
      if (!next.isAfter(central)) next = next.plusDays(1)
      return Duration.between(central, next).toMillis()
    }

    fun titleFrom(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            .let { if (it.length > TITLE_CHARS) it.take(TITLE_CHARS - 1) + "…" else it }
            .ifEmpty { "New chat" }

    private fun LocalDateTime.iso(): String = atOffset(ZoneOffset.UTC).toString()

    fun ConversationRow.toSummary() =
        AgentConversationSummary(
            id, botKey, title, ownerUsername, visibility, createdAt.iso(), lastMessageAt.iso())

    /** One chat message per question and one per answer; tool calls and sources fold into it. */
    fun toChatMessages(messages: List<MessageRow>, toolCalls: List<ToolCallRow>): List<AgentChatMessage> {
      val callsByMessage = toolCalls.groupBy { it.messageId }
      return messages.groupBy { it.turnSeq }.toSortedMap().flatMap { (_, turn) ->
        val question = turn.firstOrNull { it.role == AgentRoles.USER }
        val answers = turn.filter { it.role == AgentRoles.ASSISTANT }
        val calls = answers.flatMap { callsByMessage[it.id].orEmpty() }
        val sources =
            calls.flatMap { call ->
                  call.sourcesJson?.let { TurnState.json.decodeFromString(sourcesSerializer, it) }
                      .orEmpty()
                }
                .distinctBy { it.url }
        listOfNotNull(
            question?.let {
              AgentChatMessage(it.id, AgentRoles.USER, it.displayText.orEmpty(), createdAt = it.createdAt.iso())
            },
            answers.lastOrNull()?.let { last ->
              AgentChatMessage(
                  id = last.id,
                  role = AgentRoles.ASSISTANT,
                  text = answers.mapNotNull { it.displayText }.joinToString("\n\n"),
                  toolCalls =
                      calls.map { AgentToolCallSummary(it.toolName, it.args.take(ARGS_CHARS), it.ok) },
                  sources = sources,
                  createdAt = last.createdAt.iso())
            })
      }
    }

    private const val ARGS_CHARS = 200
  }
}
