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
    private val interlock: InterlockService,
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

  /** What a TV would show if the unsaved pages were saved; nothing is stored. Same checks as saving. */
  suspend fun previewDraft(actor: String, committeeId: Int, draft: DraftPreviewRequest): DashboardPayload {
    val committee = editable(actor, committeeId)
    DashboardValidator.validateDocument(committee, DashboardDocument(draft.pages))
    return render.render(committee, draft = draft)
  }

  /**
   * Every saved page in order, hidden and empty ones too, for the editor's "On the TV" preview: it keeps them
   * and shows whichever page is selected without asking again.
   */
  suspend fun previewSavedAll(actor: String, committeeId: Int): DashboardPayload {
    val committee = editable(actor, committeeId)
    return render.render(committee, draft = DraftPreviewRequest(repository.loadPages(committee.id), all = true))
  }

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

  // ---- TVs / Displays page ----

  /**
   * Every TV the portal knows of, for the read-only TVs / Displays page: those set up for a committee, then
   * Cast devices the cast agent's sweep found that nobody has set up.
   */
  suspend fun displays(): List<DisplayDto> {
    val now = java.time.LocalTime.now(DashboardTime.CHICAGO)
    val pagesByCommittee = mutableMapOf<Int, List<String>>()
    val targets = repository.allTargets()
    val managed =
        targets.mapNotNull { row ->
          val committee = access.committee(row.committeeId) ?: return@mapNotNull null
          val status = DisplayStatus.summarize(row.dto.lastStatus)
          val (inHours, schedule) = DisplayStatus.schedule(now, row.dto.displayStart, row.dto.displayEnd)
          val pages =
              if (status.dashboard) {
                pagesByCommittee.getOrPut(committee.id) {
                  pagesToShow(repository.loadPages(committee.id), null).map { it.title.ifBlank { "Untitled page" } }
                }
              } else {
                emptyList()
              }
          DisplayDto(
              id = row.dto.id!!,
              name = row.dto.name,
              committeeName = committee.name,
              committeeSlug = committee.slug,
              state = DisplayStatus.state(row.dto.enabled, status.online),
              host = row.dto.host,
              deviceName = row.dto.deviceName,
              enabled = row.dto.enabled,
              online = status.online,
              lastSeenAt = row.dto.lastSeenAt,
              showing = status.showing,
              showingDashboard = status.dashboard,
              dashboardPages = pages,
              schedule = schedule,
              inHours = inHours,
              reclaimAfterMinutes = row.dto.reclaimAfterMinutes)
        }.sortedWith(compareBy({ it.committeeName }, { it.name }))
    val knownIds = targets.mapNotNull { it.deviceId?.lowercase() }.toSet()
    val knownNames = targets.mapNotNull { it.dto.deviceName?.trim()?.lowercase() }.toSet()
    val nowUtc = DashboardTime.nowUtc()
    val unmanaged =
        repository.discovered()
            .filter { it.deviceId.lowercase() !in knownIds && it.name?.trim()?.lowercase() !in knownNames }
            .map { device ->
              val state = DisplayStatus.discoveredState(device.lastSeenAt, nowUtc)
              DisplayDto(
                  id = 0,
                  name = device.name?.ifBlank { null } ?: "Unnamed device",
                  managed = false,
                  state = state,
                  host = device.host,
                  deviceName = device.name,
                  enabled = true,
                  lastSeenAt = DashboardTime.utcToChicago(device.lastSeenAt),
                  showing =
                      if (state == DisplayState.ON_NETWORK) {
                        DisplayStatus.describeDiscovered(
                            device.appName,
                            device.statusText,
                            DiscoveredMedia(state = device.mediaState, title = device.mediaTitle, artist = device.mediaArtist))
                      } else {
                        "Not seen recently"
                      },
                  mediaUrl = if (state == DisplayState.ON_NETWORK) DisplayStatus.safeLink(device.mediaUrl) else null,
                  schedule = "Not managed")
            }
            .sortedBy { it.name.lowercase() }
    return managed + unmanaged
  }

  /** The cast agent's sweep results: remembered so the page can list TVs nobody has set up yet. */
  suspend fun recordDiscovered(batch: DiscoveredBatch) {
    val valid =
        batch.devices
            .map { it.copy(deviceId = it.deviceId.trim().lowercase(), host = it.host.trim()) }
            .filter { DEVICE_ID.matches(it.deviceId) && HOST_OK.matches(it.host) }
            .take(MAX_DISCOVERED)
    if (valid.isNotEmpty()) repository.recordDiscovered(valid, DashboardTime.nowUtc())
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
    val widgets = repository.loadPages(committee.id).filter { it.enabled }.flatMap { it.widgets }
    val listed = widgets.filter { it.type == WidgetType.MEMBER_LIST }.flatMap { it.config.members }.toSet()
    val operators =
        if (widgets.any { it.type == WidgetType.TOOL_STATUS && it.config.toolMode == ToolMode.IN_USE }) {
          interlock.operatorUsernames()
        } else {
          emptySet()
        }
    return members.photo(listed + operators, username)
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
    val DEVICE_ID = Regex("^[0-9a-f-]{8,64}$")
    val HOST_OK = Regex("^[A-Za-z0-9.:-]{1,64}$")
    const val MAX_DISCOVERED = 500
  }
}
