package org.dallasmakerspace.askai.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.models.agent.AgentSourceLink

/** A fetched page: HTTP status and body. */
data class TalkResponse(val status: Int, val body: String)

/**
 * Reads public Talk pages. It sends no API key and no user, so it sees exactly what a signed-out
 * visitor sees: private categories and messages answer 403 or 404.
 */
@Singleton
open class TalkFetcher @Inject constructor() {
  private val http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build()

  open suspend fun get(url: String): TalkResponse =
      withContext(Dispatchers.IO) {
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(READ_SECONDS))
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        TalkResponse(response.statusCode(), response.body())
      }

  private companion object {
    const val CONNECT_SECONDS = 5L
    const val READ_SECONDS = 15L
  }
}

/** Reads one Talk topic from a link or id, optionally the part around one post. */
@Singleton
class ReadTalkTopicTool @Inject constructor(private val fetcher: TalkFetcher) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "read_talk_topic",
          "Read a topic on Talk, the Dallas Makerspace forum: title and the posts in order. Pass a " +
              "Talk link such as https://talk.dallasmakerspace.org/t/some-topic/123/5 (the number " +
              "after the id is a post to jump to) or a topic id. Only public topics can be read.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("url", "Link to a Talk topic or post", ToolParameterType.String),
                  ToolParameterDescriptor("topicId", "Topic id, when there is no link", ToolParameterType.Integer),
                  ToolParameterDescriptor("post", "Post number to start from", ToolParameterType.Integer)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val target =
        parseTarget(args["url"]?.jsonPrimitive?.contentOrNull, args["topicId"]?.jsonPrimitive?.contentOrNull, args["post"]?.jsonPrimitive?.contentOrNull)
            ?: return ToolResult(
                "Give a link to a topic on $HOST, like $BASE/t/some-topic/123, or a topic id.", isError = true)
    val path = if (target.post != null && target.post > 1) "t/${target.topicId}/${target.post}.json" else "t/${target.topicId}.json"
    val response =
        try {
          fetcher.get("$BASE/$path")
        } catch (e: java.io.IOException) {
          return ToolResult("Talk could not be reached right now.", isError = true)
        }
    when (response.status) {
      200 -> Unit
      403, 404, 410 -> return ToolResult("That topic is not available. It may be private or deleted.", isError = true)
      else -> return ToolResult("Talk answered with an error (${response.status}).", isError = true)
    }
    val topic =
        try {
          Json.parseToJsonElement(response.body).jsonObject
        } catch (e: IllegalArgumentException) {
          return ToolResult("Talk sent something unreadable.", isError = true)
        }
    if (topic["archetype"]?.jsonPrimitive?.contentOrNull == "private_message") {
      return ToolResult("That topic is not available. It may be private or deleted.", isError = true)
    }
    val title = topic["title"]?.jsonPrimitive?.contentOrNull ?: "Talk topic"
    val slug = topic["slug"]?.jsonPrimitive?.contentOrNull ?: "topic"
    val total = topic["posts_count"]?.jsonPrimitive?.intOrNull
    val posts = (topic["post_stream"]?.jsonObject?.get("posts") as? JsonArray).orEmpty()
    val rendered =
        posts.take(MAX_POSTS).mapNotNull { element ->
          val post = element as? JsonObject ?: return@mapNotNull null
          val body = HtmlText.plain(post["cooked"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(POST_CHARS)
          val number = post["post_number"]?.jsonPrimitive?.contentOrNull ?: "?"
          val who = post["username"]?.jsonPrimitive?.contentOrNull ?: "someone"
          val date = post["created_at"]?.jsonPrimitive?.contentOrNull?.take(DATE_CHARS).orEmpty()
          "Post $number by $who ($date): $body"
        }
    if (rendered.isEmpty()) return ToolResult("That topic has no readable posts.")
    val more =
        if (total != null && total > rendered.size) "\n\n(Showing ${rendered.size} of $total posts; pass a post number to read further.)" else ""
    val url = "$BASE/t/$slug/${target.topicId}" + (target.post?.let { "/$it" } ?: "")
    return ToolResult(
        "Title: $title\nLink: $url\n\n" + rendered.joinToString("\n\n") + more,
        listOf(AgentSourceLink(title, url, "Talk")))
  }

  /** The topic and post a link or the ids point at, or null for anything off Talk. */
  internal data class Target(val topicId: Int, val post: Int?)

  companion object {
    const val HOST = "talk.dallasmakerspace.org"
    const val BASE = "https://$HOST"
    private const val MAX_POSTS = 25
    private const val POST_CHARS = 1_500
    private const val DATE_CHARS = 10
    private val LINK = Regex("""^https://${Regex.escape(HOST)}/t/(?:[A-Za-z0-9_-]+/)?(\d{1,9})(?:/(\d{1,6}))?(?:[/?#].*)?$""")

    internal fun parseTarget(url: String?, topicId: String?, post: String?): Target? {
      val postNumber = post?.trim()?.toIntOrNull()?.takeIf { it > 0 }
      url?.trim()?.takeIf { it.isNotEmpty() }?.let { link ->
        val match = LINK.matchEntire(link) ?: return null
        return Target(match.groupValues[1].toInt(), match.groupValues[2].toIntOrNull() ?: postNumber)
      }
      val id = topicId?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
      return Target(id, postNumber)
    }
  }
}
