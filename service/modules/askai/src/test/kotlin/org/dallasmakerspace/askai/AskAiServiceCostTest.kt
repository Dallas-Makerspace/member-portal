package org.dallasmakerspace.askai

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.askai.bedrock.IEmbeddingClient
import org.dallasmakerspace.askai.db.AskAiCacheRepository
import org.dallasmakerspace.askai.openrouter.ChatMessage
import org.dallasmakerspace.askai.openrouter.IOpenRouterClient
import org.dallasmakerspace.askai.openrouter.LlmResult
import org.dallasmakerspace.askai.search.SearchSource
import org.dallasmakerspace.askai.search.SearchSourceRouter
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.models.AskAiCacheEntry
import org.dallasmakerspace.models.ClassificationResult
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.TokenUsage
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class AskAiServiceCostTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  /** Prices each input token at $1 and each output token at $10 so sums are easy to check. */
  private class FakeLlmClient : IOpenRouterClient {
    override suspend fun classify(
        question: String,
        cachedQuestions: List<AskAiCacheEntry>,
    ) = LlmResult(ClassificationResult(searchQueries = listOf("laser cutter")), TokenUsage(100, 50, 150))

    override suspend fun generateAnswer(question: String, searchResults: List<SearchResult>) =
        LlmResult("Use the laser «1».", TokenUsage(500, 200, 700))

    override suspend fun chatCompletion(messages: List<ChatMessage>, jsonMode: Boolean) =
        LlmResult("", TokenUsage(0, 0, 0))

    override suspend fun generateHypotheticalAnswer(question: String) =
        LlmResult("Laser cutters need an authorization.", TokenUsage(20, 30, 50))

    override suspend fun generateAnswerStream(
        question: String,
        searchResults: List<SearchResult>,
        onToken: suspend (String) -> Unit,
    ): TokenUsage? {
      onToken("Use the laser «1».")
      return TokenUsage(500, 200, 700)
    }

    override fun estimateCost(inputTokens: Int, outputTokens: Int) =
        inputTokens * 1.0 + outputTokens * 10.0
  }

  private val wikiSource =
      object : SearchSource {
        override fun getName() = "Wiki"

        override suspend fun search(query: String, limit: Int) =
            listOf(
                SearchResult(
                    title = "Laser cutter",
                    snippet = "How to get authorized on the laser cutter",
                    url = "https://wiki.example.org/laser",
                    source = "Wiki"))
      }

  private fun service(): AskAiService {
    val member = mock<DMSMember> { on { id } doReturn 1 }
    val memberRepository =
        mock<MemberRepository> { onBlocking { getMemberOrInsert(any(), anyOrNull()) } doReturn member }
    val embeddingClient = mock<IEmbeddingClient> { onBlocking { embed(any()) } doReturn null }
    return AskAiService(
        openRouterClient = FakeLlmClient(),
        searchSourceRouter = SearchSourceRouter(setOf(wikiSource), loggerFactory),
        cacheRepository = mock<AskAiCacheRepository>(),
        memberRepository = memberRepository,
        piiMasker = PiiMasker(loggerFactory),
        config = AskAiConfig(monthlyBudgetUsd = 0.0),
        embeddingClient = embeddingClient,
        loggerFactory = loggerFactory,
    )
  }

  // classify (100 in, 50 out) + HyDE (20 in, 30 out) + answer (500 in, 200 out)
  private val expectedCost = 620 * 1.0 + 280 * 10.0

  @Test
  fun `streamed answer cost includes classify, HyDE and answer tokens`() = runBlocking {
    val result = service().askStream("How do I use the laser cutter?", "jdoe") {}

    val metadata = requireNotNull(result.metadata)
    assertEquals(expectedCost, metadata.estimatedCostUsd)
    assertEquals(TokenUsage(500, 200, 700), metadata.answerTokens)
  }

  @Test
  fun `non-streamed answer cost includes classify, HyDE and answer tokens`() = runBlocking {
    val response = service().ask("How do I use the laser cutter?", username = "jdoe")

    assertEquals(expectedCost, response.metadata?.estimatedCostUsd)
  }
}
