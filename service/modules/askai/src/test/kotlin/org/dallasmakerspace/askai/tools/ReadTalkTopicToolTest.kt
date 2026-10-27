package org.dallasmakerspace.askai.tools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.ToolContext
import org.junit.Test

class ReadTalkTopicToolTest {
  private class FakeFetcher(var status: Int = 200, var body: String = TOPIC) : TalkFetcher() {
    val urls = mutableListOf<String>()

    override suspend fun get(url: String): TalkResponse {
      urls += url
      return TalkResponse(status, body)
    }
  }

  private val context = ToolContext("jdoe", "c1", "ask-ai")

  private fun args(vararg pairs: Pair<String, String>): JsonObject =
      buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

  @Test
  fun `a pasted link is read and the posts come back in order as plain text`() = runBlocking<Unit> {
    val fetcher = FakeFetcher()

    val result =
        ReadTalkTopicTool(fetcher)
            .run(args("url" to "https://talk.dallasmakerspace.org/t/updated-online-bambu-training/129335/5"), context)

    assertFalse(result.isError, result.output)
    assertEquals(listOf("https://talk.dallasmakerspace.org/t/129335/5.json"), fetcher.urls)
    assertTrue(result.output.startsWith("Title: Updated training\n"), result.output)
    assertTrue(result.output.contains("Post 1 by alice (2026-09-01): Take the new class first & bring a badge."), result.output)
    assertFalse(result.output.contains("<p>"))
    assertEquals("Talk", result.sources.single().source)
    assertEquals("https://talk.dallasmakerspace.org/t/updated-training/129335/5", result.sources.single().url)
  }

  @Test
  fun `a topic id works and reports when more posts exist`() = runBlocking<Unit> {
    val fetcher = FakeFetcher(body = TOPIC.replace("\"posts_count\":9", "\"posts_count\":40"))

    val result = ReadTalkTopicTool(fetcher).run(buildJsonObject { put("topicId", 129335) }, context)

    assertEquals("https://talk.dallasmakerspace.org/t/129335.json", fetcher.urls.single())
    assertTrue(result.output.contains("Showing 2 of 40 posts"), result.output)
  }

  @Test
  fun `only Talk links are accepted`() {
    val parse = ReadTalkTopicTool.Companion::parseTarget
    assertEquals(ReadTalkTopicTool.Target(129335, 5), parse("https://talk.dallasmakerspace.org/t/x/129335/5", null, null))
    assertEquals(ReadTalkTopicTool.Target(129335, null), parse("https://talk.dallasmakerspace.org/t/129335", null, null))
    assertEquals(ReadTalkTopicTool.Target(7, 3), parse(null, "7", "3"))
    listOf(
            "http://talk.dallasmakerspace.org/t/x/1",
            "https://evil.example.com/t/x/1",
            "https://talk.dallasmakerspace.org.evil.example.com/t/x/1",
            "https://talk.dallasmakerspace.org/admin/users",
            "https://talk.dallasmakerspace.org/t/x/abc",
            "file:///etc/passwd")
        .forEach { assertNull(parse(it, null, null), it) }
    assertNull(parse(null, null, null))
  }

  @Test
  fun `private, missing and failing topics are reported without leaking content`() = runBlocking<Unit> {
    val fetcher = FakeFetcher(status = 403, body = "secret")
    val tool = ReadTalkTopicTool(fetcher)

    val denied = tool.run(args("url" to "https://talk.dallasmakerspace.org/t/x/1"), context)
    assertTrue(denied.isError)
    assertFalse(denied.output.contains("secret"))

    fetcher.status = 200
    fetcher.body = TOPIC.replace("\"archetype\":\"regular\"", "\"archetype\":\"private_message\"")
    assertTrue(tool.run(args("url" to "https://talk.dallasmakerspace.org/t/x/1"), context).isError)

    fetcher.status = 500
    assertTrue(tool.run(args("url" to "https://talk.dallasmakerspace.org/t/x/1"), context).isError)
    assertNotNull(tool.run(JsonObject(emptyMap()), context).output)
    assertTrue(tool.run(JsonObject(emptyMap()), context).isError)
  }

  private companion object {
    const val TOPIC =
        """{"id":129335,"title":"Updated training","slug":"updated-training","archetype":"regular","posts_count":9,""" +
            """"post_stream":{"posts":[""" +
            """{"post_number":1,"username":"alice","created_at":"2026-09-01T10:00:00.000Z","cooked":"<p>Take the new class first &amp; bring a badge.</p>"},""" +
            """{"post_number":2,"username":"bob","created_at":"2026-09-02T10:00:00.000Z","cooked":"<p>Thanks!</p>"}]}}"""
  }
}
