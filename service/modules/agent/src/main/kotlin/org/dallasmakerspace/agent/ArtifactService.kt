package org.dallasmakerspace.agent

import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ArtifactRow
import org.dallasmakerspace.models.agent.AgentArtifactDetail
import org.dallasmakerspace.models.agent.AgentArtifactSummary
import org.dallasmakerspace.models.agent.AgentArtifactVersionInfo

sealed interface SaveOutcome {
  data class Saved(val version: Int) : SaveOutcome

  data class Invalid(val message: String) : SaveOutcome

  data object Stale : SaveOutcome

  data object NotFound : SaveOutcome

  data object Forbidden : SaveOutcome
}

/** Member-facing access to artifacts: whoever may see the chat may read them; only its owner edits. */
@Singleton
class ArtifactService
@Inject
constructor(
    private val chatService: ChatService,
    private val repository: ArtifactRepository,
) {

  /** Null when the conversation does not exist or [username] may not see it. */
  suspend fun list(conversationId: String, username: String): List<AgentArtifactSummary>? {
    chatService.viewable(conversationId, username) ?: return null
    return repository.listFor(conversationId).map { it.toSummary() }
  }

  suspend fun detail(id: String, username: String, version: Int?): AgentArtifactDetail? {
    val (artifact, owner) = visible(id, username) ?: return null
    val number = version ?: artifact.currentVersion
    val row = repository.version(id, number) ?: return null
    return AgentArtifactDetail(
        artifact = artifact.toSummary(),
        version = row.version,
        content = row.content,
        versions =
            repository.versions(id).map {
              AgentArtifactVersionInfo(it.version, it.author, it.createdAt.atOffset(ZoneOffset.UTC).toString())
            },
        exportFormats = ArtifactExport.formatsFor(artifact.kind),
        canEdit = owner,
    )
  }

  suspend fun save(id: String, username: String, content: String, basedOn: Int): SaveOutcome {
    val (artifact, owner) = visible(id, username) ?: return SaveOutcome.NotFound
    if (!owner) return SaveOutcome.Forbidden
    ArtifactKinds.problem(artifact.kind, content)?.let { return SaveOutcome.Invalid(it) }
    val version = repository.addVersion(id, content, username, basedOn = basedOn) ?: return SaveOutcome.Stale
    return SaveOutcome.Saved(version)
  }

  suspend fun export(id: String, username: String, format: String, version: Int?): ExportFile? {
    val (artifact, _) = visible(id, username) ?: return null
    val row = repository.version(id, version ?: artifact.currentVersion) ?: return null
    return ArtifactExport.render(artifact.kind, artifact.title, row.content, format)
  }

  private suspend fun visible(id: String, username: String): Pair<ArtifactRow, Boolean>? {
    val artifact = repository.get(id) ?: return null
    val conversation = chatService.viewable(artifact.conversationId, username) ?: return null
    return artifact to (conversation.ownerUsername == username)
  }

  private fun ArtifactRow.toSummary() =
      AgentArtifactSummary(id, kind, title, currentVersion, updatedAt.atOffset(ZoneOffset.UTC).toString())
}
