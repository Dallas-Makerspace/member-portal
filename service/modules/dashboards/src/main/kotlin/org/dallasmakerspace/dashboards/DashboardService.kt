package org.dallasmakerspace.dashboards

import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.dashboards.db.CastTargetRow
import org.dallasmakerspace.dashboards.db.DashboardRepository
import org.dallasmakerspace.models.Committee

/**
 * Editing, the TV token handshake and rendering. Every editor call is checked against
 * [CommitteeAccess] here (the UI's own check is only for showing or hiding buttons).
 */
@Suppress("TooManyFunctions")
@Singleton
class DashboardService
@Inject
constructor(
    private val repository: DashboardRepository,
    private val access: CommitteeAccess,
    private val tokens: DashboardTokenService,
    private val render: DashboardRenderService,
    private val events: DashboardEvents,
    private val images: DashboardImageService,
    private val members: DashboardMembers,
) {
  private val verifiedTargets = TtlCache<Int, CastTargetRow?>(TARGET_TTL)

  // ---- Editor ----

  suspend fun canEdit(actor: String, committeeId: Int): Boolean =
      access.committee(committeeId)?.let { access.canEditDashboard(actor, it) } ?: false

  suspend fun document(actor: String, committeeId: Int): DashboardDocument {
    val committee = editable(actor, committeeId)
    return DashboardDocument(repository.loadPages(committee.id))
  }

  suspend fun saveDocument(actor: String, committeeId: Int, document: DashboardDocument) {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateDocument(committee, document)
    repository.replacePages(committee.id, document.pages, actor)
    events.reload(committee.id)
  }

  /** Stores a picture for a text card and returns the file name to put in the widget. */
  suspend fun uploadImage(actor: String, committeeId: Int, bytes: ByteArray): String =
      images.save(editable(actor, committeeId).id, bytes)

  /** The enabled members of one of the committee's groups, for adding them to a member list. */
  suspend fun groupMembers(actor: String, committeeId: Int, group: String): List<String> =
      members.groupMembers(editable(actor, committeeId), group)

  /** Checks a pick for a member list and returns the ones that can be added. */
  suspend fun checkMembers(actor: String, committeeId: Int, usernames: List<String>): List<String> {
    editable(actor, committeeId)
    DashboardValidator.validateUsernames(usernames)
    return members.existing(usernames)
  }

  suspend fun editorImage(actor: String, committeeId: Int, file: String): Pair<ByteArray, String>? =
      images.get(editable(actor, committeeId).id, file)

  /** What a TV would show right now, for the editor's preview. */
  suspend fun preview(actor: String, committeeId: Int): DashboardPayload =
      render.render(editable(actor, committeeId))

  suspend fun announcements(actor: String, committeeId: Int): List<AnnouncementDto> =
      repository.announcements(editable(actor, committeeId).id)

  suspend fun addAnnouncement(actor: String, committeeId: Int, dto: AnnouncementDto): Int {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateAnnouncement(dto)
    return repository.addAnnouncement(committee.id, dto, actor).also { events.reload(committee.id) }
  }

  suspend fun updateAnnouncement(actor: String, committeeId: Int, id: Int, dto: AnnouncementDto) {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateAnnouncement(dto)
    if (!repository.updateAnnouncement(committee.id, id, dto)) throw notFound("announcement")
    events.reload(committee.id)
  }

  suspend fun deleteAnnouncement(actor: String, committeeId: Int, id: Int) {
    val committee = editable(actor, committeeId)
    if (!repository.deleteAnnouncement(committee.id, id)) throw notFound("announcement")
    events.reload(committee.id)
  }

  suspend fun targets(actor: String, committeeId: Int): List<CastTargetDto> =
      repository.targets(editable(actor, committeeId).id).map { it.dto }

  suspend fun addTarget(actor: String, committeeId: Int, dto: CastTargetDto): Int {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateTarget(dto)
    if (repository.targets(committee.id).size >= MAX_TARGETS_PER_COMMITTEE) {
      throw DashboardException("At most $MAX_TARGETS_PER_COMMITTEE TVs per committee")
    }
    return repository.addTarget(committee.id, dto).also { events.targetsChanged(committee.id) }
  }

  suspend fun updateTarget(actor: String, committeeId: Int, id: Int, dto: CastTargetDto) {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateTarget(dto)
    if (!repository.updateTarget(committee.id, id, dto)) throw notFound("TV")
    verifiedTargets.clear()
    events.targetsChanged(committee.id)
  }

  suspend fun deleteTarget(actor: String, committeeId: Int, id: Int) {
    val committee = editable(actor, committeeId)
    if (!repository.deleteTarget(committee.id, id)) throw notFound("TV")
    verifiedTargets.clear()
    events.targetsChanged(committee.id)
  }

  /** Cuts off the TV's current tokens; the agent is told to hand it a fresh one. */
  suspend fun revokeTarget(actor: String, committeeId: Int, id: Int) {
    val committee = editable(actor, committeeId)
    if (!repository.revokeTarget(committee.id, id)) throw notFound("TV")
    verifiedTargets.clear()
    events.targetsChanged(committee.id)
  }

  // ---- Cast agent ----

  suspend fun agentTargets(): List<AgentTarget> =
      repository.allEnabledTargets()
          .filter { row -> access.committee(row.committeeId)?.let { access.hasDashboard(it) } == true }
          .map {
            AgentTarget(
                id = it.dto.id!!,
                committeeId = it.committeeId,
                name = it.dto.name,
                host = it.dto.host,
                port = it.dto.port,
                displayStart = it.dto.displayStart,
                displayEnd = it.dto.displayEnd,
                reclaimAfterMinutes = it.dto.reclaimAfterMinutes,
                deviceId = it.deviceId,
                deviceName = it.dto.deviceName)
          }

  suspend fun issueToken(targetId: Int): TokenResponse {
    if (!tokens.isConfigured) {
      throw DashboardException("Dashboard tokens are not configured", DashboardError.UNAVAILABLE)
    }
    val row = repository.target(targetId)?.takeIf { it.dto.enabled } ?: throw notFound("TV")
    return tokens.issue(targetId, row.committeeId, row.tokenGeneration)
  }

  /** The agent learned who a TV is; kept so it can find the TV again if its address changes. */
  suspend fun recordIdentity(targetId: Int, identity: TargetIdentity) {
    if (identity.deviceId.isBlank()) throw DashboardException("A device id is required")
    repository.target(targetId) ?: throw notFound("TV")
    repository.setIdentity(targetId, identity.deviceId.trim(), identity.deviceName?.trim())
  }

  /** The agent found a TV at a new address; the TV is moved and the agent reconnects. */
  suspend fun moveTarget(targetId: Int, host: String) {
    DashboardValidator.validateHost(host)
    val row = repository.target(targetId) ?: throw notFound("TV")
    if (!repository.moveTarget(targetId, host)) throw notFound("TV")
    verifiedTargets.clear()
    events.targetsChanged(row.committeeId)
  }

  suspend fun reportStatus(targetId: Int, report: TargetStatusReport) {
    val status = if (report.connected) report.app ?: "idle" else "offline"
    repository.reportStatus(targetId, status, DashboardTime.nowUtc())
  }

  // ---- TV ----

  /** The dashboard for a valid token; null when the token is bad, expired or revoked. */
  @Suppress("ReturnCount")
  suspend fun dashboardFor(token: String?): DashboardPayload? =
      committeeForToken(token)?.let { render.render(it) }

  /** A picture for a TV: only from the committee its token belongs to. */
  suspend fun tvImage(token: String?, file: String): Pair<ByteArray, String>? =
      committeeForToken(token)?.let { images.get(it.id, file) }

  /** An uploaded member photo for a TV: only for people on that committee's member lists. */
  suspend fun tvAvatar(token: String?, username: String): Pair<ByteArray, String>? {
    val committee = committeeForToken(token) ?: return null
    val listed =
        repository.loadPages(committee.id).filter { it.enabled }.flatMap { it.widgets }.filter {
          it.type == WidgetType.MEMBER_LIST
        }.flatMap { it.config.members }.toSet()
    return members.photo(listed, username)
  }

  /** The committee a valid, unrevoked TV token belongs to; null otherwise. */
  @Suppress("ReturnCount")
  private suspend fun committeeForToken(token: String?): Committee? {
    val claims = tokens.verify(token) ?: return null
    val row = verifiedTargets.getOrLoad(claims.targetId) { repository.target(claims.targetId) }
    if (row == null || !row.dto.enabled || row.tokenGeneration != claims.generation) return null
    if (row.committeeId != claims.committeeId) return null
    return access.committee(row.committeeId)?.takeIf { access.hasDashboard(it) }
  }

  private suspend fun editable(actor: String, committeeId: Int): Committee {
    val committee = access.committee(committeeId) ?: throw notFound("committee")
    if (!access.canEditDashboard(actor, committee)) {
      throw DashboardException(
          "Only the committee's chairs can edit its dashboard", DashboardError.FORBIDDEN)
    }
    return committee
  }

  private fun notFound(what: String) =
      DashboardException("Unknown $what", DashboardError.NOT_FOUND)

  private companion object {
    val TARGET_TTL: Duration = Duration.ofMinutes(5)
  }
}
