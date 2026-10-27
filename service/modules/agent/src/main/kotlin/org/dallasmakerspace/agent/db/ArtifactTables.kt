package org.dallasmakerspace.agent.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Tables from `service/docs/sql/agent-artifacts-migration.sql`; all timestamps are UTC. */
@Suppress("MagicNumber")
object AgentArtifactTable : IdTable<String>("agent_artifact") {
  override val id: Column<EntityID<String>> = char("id", 36).entityId()
  override val primaryKey = PrimaryKey(id)
  val conversationId =
      reference("conversation_id", AgentConversationTable, onDelete = ReferenceOption.CASCADE)
  val kind = varchar("kind", 32)
  val title = varchar("title", 200)
  val currentVersion = integer("current_version").default(1)
  val createdAt = datetime("created_at")
  val updatedAt = datetime("updated_at")
}

@Suppress("MagicNumber")
object AgentArtifactVersionTable : Table("agent_artifact_version") {
  val artifactId = reference("artifact_id", AgentArtifactTable, onDelete = ReferenceOption.CASCADE)
  val version = integer("version")
  val content = text("content")
  val author = varchar("author", 64)
  val createdAt = datetime("created_at")
  override val primaryKey = PrimaryKey(artifactId, version)
}
