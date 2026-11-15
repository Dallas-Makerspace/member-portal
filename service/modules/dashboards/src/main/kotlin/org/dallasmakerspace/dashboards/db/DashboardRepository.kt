package org.dallasmakerspace.dashboards.db

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.dashboards.AnnouncementDto
import org.dallasmakerspace.dashboards.CastTargetDto
import org.dallasmakerspace.dashboards.DashboardLayout
import org.dallasmakerspace.dashboards.DashboardTime
import org.dallasmakerspace.dashboards.PageDto
import org.dallasmakerspace.dashboards.WidgetConfig
import org.dallasmakerspace.dashboards.WidgetDto
import org.dallasmakerspace.dashboards.WidgetType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** A TV row including the fields only the service needs. */
data class CastTargetRow(
    val committeeId: Int,
    val tokenGeneration: Int,
    val deviceId: String? = null,
    val dto: CastTargetDto,
)

@Suppress("TooManyFunctions")
@Singleton
class DashboardRepository @Inject constructor() {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun loadPages(committeeId: Int): List<PageDto> = suspendTransaction {
    val pages =
        DashboardPagesTable.select { DashboardPagesTable.committeeId eq committeeId }
            .orderBy(DashboardPagesTable.position, SortOrder.ASC)
            .toList()
    val widgetsByPage =
        pages
            .map { it[DashboardPagesTable.id].value }
            .let { ids -> if (ids.isEmpty()) emptyList() else widgetsFor(ids) }
            .groupBy({ it.first }, { it.second })
    pages.map { row ->
      val id = row[DashboardPagesTable.id].value
      PageDto(
          id = id,
          title = row[DashboardPagesTable.title],
          layout = DashboardLayout.valueOf(row[DashboardPagesTable.layout]),
          durationSeconds = row[DashboardPagesTable.durationSeconds],
          enabled = row[DashboardPagesTable.enabled],
          widgets = widgetsByPage[id].orEmpty().sortedBy { it.slot })
    }
  }

  /** Replaces all of a committee's pages with [pages] in one transaction. */
  suspend fun replacePages(committeeId: Int, pages: List<PageDto>, actor: String) =
      suspendTransaction {
        val now = DashboardTime.nowUtc()
        DashboardPagesTable.deleteWhere { DashboardPagesTable.committeeId eq committeeId }
        pages.forEachIndexed { index, page ->
          val pageId =
              DashboardPagesTable.insertAndGetId {
                it[DashboardPagesTable.committeeId] = committeeId
                it[position] = index
                it[layout] = page.layout.name
                it[title] = page.title
                it[durationSeconds] = page.durationSeconds
                it[enabled] = page.enabled
                it[updatedBy] = actor
                it[updatedAt] = now
              }
          page.widgets.forEach { widget ->
            DashboardWidgetsTable.insert {
              it[DashboardWidgetsTable.pageId] = pageId
              it[slot] = widget.slot
              it[type] = widget.type.name
              it[config] = json.encodeToString(widget.config)
            }
          }
        }
      }

  suspend fun announcements(committeeId: Int): List<AnnouncementDto> = suspendTransaction {
    DashboardAnnouncementsTable.select { DashboardAnnouncementsTable.committeeId eq committeeId }
        .orderBy(DashboardAnnouncementsTable.id, SortOrder.ASC)
        .map(::toAnnouncement)
  }

  suspend fun addAnnouncement(committeeId: Int, dto: AnnouncementDto, actor: String): Int =
      suspendTransaction {
        DashboardAnnouncementsTable.insertAndGetId {
              it[DashboardAnnouncementsTable.committeeId] = committeeId
              it[text] = dto.text
              it[startsAt] = DashboardTime.chicagoToUtc(dto.startsAt)
              it[endsAt] = DashboardTime.chicagoToUtc(dto.endsAt)
              it[createdBy] = actor
              it[createdAt] = DashboardTime.nowUtc()
            }
            .value
      }

  /** False when no such announcement belongs to [committeeId]. */
  suspend fun updateAnnouncement(committeeId: Int, id: Int, dto: AnnouncementDto): Boolean =
      suspendTransaction {
        DashboardAnnouncementsTable.update({
          (DashboardAnnouncementsTable.id eq id) and
              (DashboardAnnouncementsTable.committeeId eq committeeId)
        }) {
          it[text] = dto.text
          it[startsAt] = DashboardTime.chicagoToUtc(dto.startsAt)
          it[endsAt] = DashboardTime.chicagoToUtc(dto.endsAt)
        } > 0
      }

  suspend fun deleteAnnouncement(committeeId: Int, id: Int): Boolean = suspendTransaction {
    DashboardAnnouncementsTable.deleteWhere {
      (DashboardAnnouncementsTable.id eq id) and
          (DashboardAnnouncementsTable.committeeId eq committeeId)
    } > 0
  }

  suspend fun targets(committeeId: Int): List<CastTargetRow> = suspendTransaction {
    CastTargetsTable.select { CastTargetsTable.committeeId eq committeeId }
        .orderBy(CastTargetsTable.id, SortOrder.ASC)
        .map(::toTarget)
  }

  suspend fun allEnabledTargets(): List<CastTargetRow> = suspendTransaction {
    CastTargetsTable.select { CastTargetsTable.enabled eq true }
        .orderBy(CastTargetsTable.id, SortOrder.ASC)
        .map(::toTarget)
  }

  suspend fun target(id: Int): CastTargetRow? = suspendTransaction {
    CastTargetsTable.select { CastTargetsTable.id eq id }.singleOrNull()?.let(::toTarget)
  }

  suspend fun addTarget(committeeId: Int, dto: CastTargetDto): Int = suspendTransaction {
    CastTargetsTable.insertAndGetId {
          it[CastTargetsTable.committeeId] = committeeId
          it[name] = dto.name
          it[host] = dto.host
          it[port] = dto.port
          it[enabled] = dto.enabled
          it[displayStart] = dto.displayStart
          it[displayEnd] = dto.displayEnd
          it[reclaimAfterMinutes] = dto.reclaimAfterMinutes
        }
        .value
  }

  suspend fun updateTarget(committeeId: Int, id: Int, dto: CastTargetDto): Boolean =
      suspendTransaction {
        // A different address may be a different TV, so what was learned about the old one is dropped.
        val moved =
            CastTargetsTable.select {
                  (CastTargetsTable.id eq id) and (CastTargetsTable.committeeId eq committeeId)
                }
                .singleOrNull()
                ?.let { it[CastTargetsTable.host] != dto.host || it[CastTargetsTable.port] != dto.port }
                ?: false
        CastTargetsTable.update({
          (CastTargetsTable.id eq id) and (CastTargetsTable.committeeId eq committeeId)
        }) {
          if (moved) {
            it[deviceId] = null
            it[deviceName] = null
          }
          it[name] = dto.name
          it[host] = dto.host
          it[port] = dto.port
          it[enabled] = dto.enabled
          it[displayStart] = dto.displayStart
          it[displayEnd] = dto.displayEnd
          it[reclaimAfterMinutes] = dto.reclaimAfterMinutes
        } > 0
      }

  suspend fun deleteTarget(committeeId: Int, id: Int): Boolean = suspendTransaction {
    CastTargetsTable.deleteWhere {
      (CastTargetsTable.id eq id) and (CastTargetsTable.committeeId eq committeeId)
    } > 0
  }

  /** Records what the TV calls itself and its stable id (reported by the cast agent). */
  suspend fun setIdentity(id: Int, deviceId: String, deviceName: String?) = suspendTransaction {
    CastTargetsTable.update({ CastTargetsTable.id eq id }) {
      it[CastTargetsTable.deviceId] = deviceId.take(ID_MAX)
      it[CastTargetsTable.deviceName] = deviceName?.take(NAME_MAX)
    }
  }

  /** Points a TV at a new address, keeping what is known about it (the agent found it again). */
  suspend fun moveTarget(id: Int, host: String): Boolean = suspendTransaction {
    CastTargetsTable.update({ CastTargetsTable.id eq id }) { it[CastTargetsTable.host] = host } > 0
  }

  /** Invalidates every token issued for the TV by moving to the next generation. */
  suspend fun revokeTarget(committeeId: Int, id: Int): Boolean = suspendTransaction {
    val current =
        CastTargetsTable.select {
              (CastTargetsTable.id eq id) and (CastTargetsTable.committeeId eq committeeId)
            }
            .singleOrNull()
            ?.get(CastTargetsTable.tokenGeneration) ?: return@suspendTransaction false
    CastTargetsTable.update({ CastTargetsTable.id eq id }) {
      it[tokenGeneration] = current + 1
    } > 0
  }

  suspend fun reportStatus(id: Int, status: String?, seenAt: LocalDateTime) = suspendTransaction {
    CastTargetsTable.update({ CastTargetsTable.id eq id }) {
      it[lastSeenAt] = seenAt
      it[lastStatus] = status?.take(STATUS_MAX)
    }
  }

  private fun widgetsFor(pageIds: List<Int>): List<Pair<Int, WidgetDto>> =
      DashboardWidgetsTable.select { DashboardWidgetsTable.pageId inList pageIds }
          .map { row ->
            row[DashboardWidgetsTable.pageId].value to
                WidgetDto(
                    slot = row[DashboardWidgetsTable.slot],
                    type = WidgetType.valueOf(row[DashboardWidgetsTable.type]),
                    config = json.decodeFromString<WidgetConfig>(row[DashboardWidgetsTable.config]))
          }

  private fun toAnnouncement(row: ResultRow) =
      AnnouncementDto(
          id = row[DashboardAnnouncementsTable.id].value,
          text = row[DashboardAnnouncementsTable.text],
          startsAt = DashboardTime.utcToChicago(row[DashboardAnnouncementsTable.startsAt]),
          endsAt = DashboardTime.utcToChicago(row[DashboardAnnouncementsTable.endsAt]))

  private fun toTarget(row: ResultRow) =
      CastTargetRow(
          committeeId = row[CastTargetsTable.committeeId],
          tokenGeneration = row[CastTargetsTable.tokenGeneration],
          deviceId = row[CastTargetsTable.deviceId],
          dto =
              CastTargetDto(
                  id = row[CastTargetsTable.id].value,
                  name = row[CastTargetsTable.name],
                  host = row[CastTargetsTable.host],
                  port = row[CastTargetsTable.port],
                  enabled = row[CastTargetsTable.enabled],
                  displayStart = row[CastTargetsTable.displayStart],
                  displayEnd = row[CastTargetsTable.displayEnd],
                  reclaimAfterMinutes = row[CastTargetsTable.reclaimAfterMinutes],
                  lastSeenAt = DashboardTime.utcToChicago(row[CastTargetsTable.lastSeenAt]),
                  lastStatus = row[CastTargetsTable.lastStatus],
                  deviceName = row[CastTargetsTable.deviceName]))

  private companion object {
    const val STATUS_MAX = 120
    const val ID_MAX = 64
    const val NAME_MAX = 80
  }
}
