package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.DashboardServiceClient

/** `/displays`: a read-only list of every TV the portal casts to, and what each is doing. */
class DisplaysHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val dashboard: DashboardServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  @Suppress("ReturnCount")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val result = dashboard.call(HttpMethod.Get, "displays", username, null, session.sessionId)
    val rows =
        (result.data as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { toRow(it) }
    val model =
        mapOf(
            "displays" to rows,
            "unavailable" to (result.statusCode !in OK_RANGE),
            "total" to rows.size,
            "online" to rows.count { it["state"] == "ONLINE" },
            "offline" to rows.count { it["state"] == "OFFLINE" },
            "notSetUp" to rows.count { it["managed"] == false },
            "setUp" to rows.count { it["managed"] == true },
            "attention" to rows.count { it["state"] in ATTENTION },
            "dashboards" to rows.count { it["showingDashboard"] == true })
    call.respond(ThymeleafContent("displays", model))
  }

  private fun toRow(row: Map<*, *>): Map<String, Any?> =
      mapOf(
          "id" to (row["id"] as? Number)?.toInt(),
          "name" to row["name"],
          "committeeName" to row["committeeName"],
          "committeeSlug" to row["committeeSlug"],
          "managed" to (row["managed"] != false),
          "state" to (row["state"] as? String ?: "UNSEEN"),
          "deviceName" to row["deviceName"],
          "enabled" to (row["enabled"] != false),
          "online" to row["online"] as? Boolean,
          "lastSeen" to lastSeenLabel(row["lastSeenAt"] as? String),
          "showing" to row["showing"],
          "showingDashboard" to (row["showingDashboard"] == true),
          "dashboardPages" to ((row["dashboardPages"] as? List<*>).orEmpty().map { it.toString() }),
          "schedule" to row["schedule"],
          "inHours" to (row["inHours"] != false),
          "reclaimAfterMinutes" to (row["reclaimAfterMinutes"] as? Number)?.toInt())

  private fun lastSeenLabel(iso: String?): String? {
    val seen = iso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val today = LocalDateTime.now(CHICAGO).toLocalDate()
    val time = TIME.format(seen)
    return when (seen.toLocalDate()) {
      today -> "Today, $time"
      today.minusDays(1) -> "Yesterday, $time"
      else -> "${DAY.format(seen)}, $time"
    }
  }

  companion object {
    private val OK_RANGE = 200..299
    private val ATTENTION = setOf("OFFLINE", "UNSEEN", "DISABLED")
    private val CHICAGO = ZoneId.of("America/Chicago")
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
  }
}
