package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ArtifactRow

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun text(name: String, description: String) =
    ToolParameterDescriptor(name, description, ToolParameterType.String)

private fun change(artifact: ArtifactRow, version: Int): String =
    buildJsonObject {
          put("id", artifact.id)
          put("version", version)
          put("kind", artifact.kind)
          put("title", artifact.title)
        }
        .toString()

/** Tools that let a bot write documents the member can open beside the chat. */
object ArtifactToolInfo {
  const val MAX_PER_CONVERSATION = 10
  const val ASSISTANT = "assistant"

  const val FORMAT_HELP =
      "Formats: markdown (free text); course_outline (JSON {\"title\", \"sections\":[{\"title\", " +
          "\"activities\":[{\"title\", \"type\" (lecture|reading|video|discussion|assignment|quiz|lab|other), " +
          "\"summary\", \"minutes\"}]}]}); quiz (JSON {\"title\", \"questions\":[{\"type\" " +
          "(multiple_choice|true_false|short_answer|matching), \"text\", \"options\":[{\"text\", \"correct\"}], " +
          "\"answers\":[...] for short_answer, \"pairs\":[{\"item\", \"match\"}] for matching, \"feedback\"}]})."
}

@Singleton
class CreateArtifactTool @Inject constructor(private val repository: ArtifactRepository) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "create_artifact",
          "Create a new document the member sees beside the chat. ${ArtifactToolInfo.FORMAT_HELP} " +
              "Invalid content is rejected with a message; fix it and call again.",
          requiredParameters =
              listOf(
                  text("kind", "markdown, course_outline or quiz"),
                  text("title", "Short title"),
                  text("content", "The full document in the chosen format")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val kind = args.string("kind")?.trim().orEmpty()
    val title = args.string("title")?.trim().orEmpty().ifEmpty { return ToolResult("Missing title.", isError = true) }
    val content = args.string("content").orEmpty()
    ArtifactKinds.problem(kind, content)?.let { return ToolResult(it, isError = true) }
    if (repository.listFor(context.conversationId).size >= ArtifactToolInfo.MAX_PER_CONVERSATION) {
      return ToolResult("This chat already has ${ArtifactToolInfo.MAX_PER_CONVERSATION} documents; update one instead.", isError = true)
    }
    val artifact = repository.create(context.conversationId, kind, title, content, ArtifactToolInfo.ASSISTANT)
    return ToolResult(
        "Created $kind \"${artifact.title}\" with id ${artifact.id} (version 1).",
        artifactChange = change(artifact, 1))
  }
}

@Singleton
class UpdateArtifactTool @Inject constructor(private val repository: ArtifactRepository) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "update_artifact",
          "Change a document and save it as a new version. Either replace everything with `content`, " +
              "or make one targeted edit by giving `old` (text that appears exactly once) and `new`. " +
              "Prefer targeted edits for small changes. ${ArtifactToolInfo.FORMAT_HELP}",
          requiredParameters = listOf(text("id", "Artifact id")),
          optionalParameters =
              listOf(
                  text("content", "The full new document"),
                  text("old", "Text to replace; must appear exactly once"),
                  text("new", "Replacement text")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val artifact = owned(repository, args.string("id"), context) ?: return ToolResult("No such document in this chat.", isError = true)
    val latest = repository.version(artifact.id, artifact.currentVersion)?.content.orEmpty()
    val full = args.string("content")
    val old = args.string("old")
    val updated =
        when {
          full != null -> full
          old != null -> {
            val count = if (old.isEmpty()) 0 else latest.split(old).size - 1
            if (count != 1) return ToolResult("`old` must appear exactly once in the document; it appears $count times.", isError = true)
            latest.replace(old, args.string("new").orEmpty())
          }
          else -> return ToolResult("Give either content, or old and new.", isError = true)
        }
    ArtifactKinds.problem(artifact.kind, updated)?.let { return ToolResult(it, isError = true) }
    val version =
        repository.addVersion(artifact.id, updated, ArtifactToolInfo.ASSISTANT, basedOn = artifact.currentVersion)
            ?: return ToolResult("The document changed while you were editing it. Read it again and retry.", isError = true)
    return ToolResult("Saved version $version of \"${artifact.title}\".", artifactChange = change(artifact, version))
  }
}

@Singleton
class ReadArtifactTool @Inject constructor(private val repository: ArtifactRepository) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "read_artifact",
          "Read a document in this chat (latest version unless you pass one). Read it before editing " +
              "if the member may have changed it.",
          requiredParameters = listOf(text("id", "Artifact id")),
          optionalParameters = listOf(ToolParameterDescriptor("version", "Version number", ToolParameterType.Integer)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val artifact = owned(repository, args.string("id"), context) ?: return ToolResult("No such document in this chat.", isError = true)
    val number = args.string("version")?.trim()?.toIntOrNull() ?: artifact.currentVersion
    val version = repository.version(artifact.id, number) ?: return ToolResult("No version $number.", isError = true)
    return ToolResult(
        "${artifact.kind} \"${artifact.title}\" version ${version.version} of ${artifact.currentVersion}, " +
            "last saved by ${version.author}:\n${version.content}")
  }
}

@Singleton
class ListArtifactsTool @Inject constructor(private val repository: ArtifactRepository) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "list_artifacts",
          "List the documents in this chat with their ids and versions.",
          requiredParameters = emptyList())

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val all = repository.listFor(context.conversationId)
    if (all.isEmpty()) return ToolResult("No documents yet.")
    return ToolResult(all.joinToString("\n") { "${it.id} | ${it.kind} | ${it.title} | version ${it.currentVersion}" })
  }
}

private suspend fun owned(repository: ArtifactRepository, id: String?, context: ToolContext): ArtifactRow? =
    id?.trim()?.takeIf { it.isNotEmpty() }?.let { repository.get(it) }?.takeIf { it.conversationId == context.conversationId }
