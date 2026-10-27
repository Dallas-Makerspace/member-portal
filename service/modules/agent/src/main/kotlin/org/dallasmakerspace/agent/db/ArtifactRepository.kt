package org.dallasmakerspace.agent.db

import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.db.suspendTransaction
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

data class ArtifactRow(
    val id: String,
    val conversationId: String,
    val kind: String,
    val title: String,
    val currentVersion: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class ArtifactVersionRow(
    val artifactId: String,
    val version: Int,
    val content: String,
    val author: String,
    val createdAt: LocalDateTime,
)

/** Storage for artifacts and their versions; every save adds a version, none is overwritten. */
@Singleton
class ArtifactRepository @Inject constructor() {

  suspend fun create(
      conversationId: String,
      kind: String,
      title: String,
      content: String,
      author: String,
      now: LocalDateTime = ConversationRepository.nowUtc(),
  ): ArtifactRow = suspendTransaction {
    val id = UUID.randomUUID().toString()
    AgentArtifactTable.insert {
      it[AgentArtifactTable.id] = id
      it[AgentArtifactTable.conversationId] = conversationId
      it[AgentArtifactTable.kind] = kind
      it[AgentArtifactTable.title] = title.take(TITLE_LENGTH)
      it[currentVersion] = 1
      it[createdAt] = now
      it[updatedAt] = now
    }
    insertVersion(id, 1, content, author, now)
    ArtifactRow(id, conversationId, kind, title.take(TITLE_LENGTH), 1, now, now)
  }

  /** Saves [content] as the next version, unless the artifact's latest is not [basedOn]. */
  suspend fun addVersion(
      artifactId: String,
      content: String,
      author: String,
      basedOn: Int? = null,
      now: LocalDateTime = ConversationRepository.nowUtc(),
  ): Int? = suspendTransaction {
    val current =
        AgentArtifactTable.select { AgentArtifactTable.id eq artifactId }
            .singleOrNull()
            ?.get(AgentArtifactTable.currentVersion) ?: return@suspendTransaction null
    if (basedOn != null && basedOn != current) return@suspendTransaction null
    val next = current + 1
    insertVersion(artifactId, next, content, author, now)
    AgentArtifactTable.update({ AgentArtifactTable.id eq artifactId }) {
      it[currentVersion] = next
      it[updatedAt] = now
    }
    next
  }

  suspend fun get(id: String): ArtifactRow? = suspendTransaction {
    AgentArtifactTable.select { AgentArtifactTable.id eq id }.singleOrNull()?.toArtifact()
  }

  suspend fun listFor(conversationId: String): List<ArtifactRow> = suspendTransaction {
    AgentArtifactTable.select { AgentArtifactTable.conversationId eq conversationId }
        .orderBy(AgentArtifactTable.createdAt, SortOrder.ASC)
        .map { it.toArtifact() }
  }

  suspend fun version(artifactId: String, version: Int): ArtifactVersionRow? = suspendTransaction {
    AgentArtifactVersionTable.select {
      (AgentArtifactVersionTable.artifactId eq artifactId) and
          (AgentArtifactVersionTable.version eq version)
    }
        .singleOrNull()
        ?.toVersion()
  }

  suspend fun versions(artifactId: String): List<ArtifactVersionRow> = suspendTransaction {
    AgentArtifactVersionTable.select { AgentArtifactVersionTable.artifactId eq artifactId }
        .orderBy(AgentArtifactVersionTable.version, SortOrder.ASC)
        .map { it.toVersion() }
  }

  private fun insertVersion(
      artifactId: String,
      version: Int,
      content: String,
      author: String,
      now: LocalDateTime,
  ) {
    AgentArtifactVersionTable.insert {
      it[AgentArtifactVersionTable.artifactId] = artifactId
      it[AgentArtifactVersionTable.version] = version
      it[AgentArtifactVersionTable.content] = content
      it[AgentArtifactVersionTable.author] = author.take(AUTHOR_LENGTH)
      it[createdAt] = now
    }
  }

  private fun ResultRow.toArtifact() =
      ArtifactRow(
          this[AgentArtifactTable.id].value,
          this[AgentArtifactTable.conversationId].value,
          this[AgentArtifactTable.kind],
          this[AgentArtifactTable.title],
          this[AgentArtifactTable.currentVersion],
          this[AgentArtifactTable.createdAt],
          this[AgentArtifactTable.updatedAt])

  private fun ResultRow.toVersion() =
      ArtifactVersionRow(
          this[AgentArtifactVersionTable.artifactId].value,
          this[AgentArtifactVersionTable.version],
          this[AgentArtifactVersionTable.content],
          this[AgentArtifactVersionTable.author],
          this[AgentArtifactVersionTable.createdAt])

  private companion object {
    const val TITLE_LENGTH = 200
    const val AUTHOR_LENGTH = 64
  }
}
