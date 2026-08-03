package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.plugins.AuthException

class ProfileDebugInfoHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")

    val currentUsername = userInfo["preferred_username"] as String?
    val isSelf = requestedUsername == currentUsername
    val canManageMembers = authz.can(Permission.MANAGE_MEMBERS.name)
    val isSelfOrAdmin = isSelf || canManageMembers

    if (!isSelfOrAdmin) {
      call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden"))
      return@coroutineScope
    }

    val debugInfoDeferred =
        if (canManageMembers)
            async { memberService.getDebugInfo(requestedUsername, session.sessionId) }
        else null
    val swipesDeferred =
        if (canManageMembers)
            async { memberService.getRecentBadgeSwipes(requestedUsername, session.sessionId) }
        else null
    val badgeMMDeferred = async {
      memberService.getBadgeFromMakerManager(requestedUsername, session.sessionId)
    }
    val badgeADDeferred = async {
      memberService.getBadgeFromActiveDirectory(requestedUsername, session.sessionId)
    }

    try {
      val result = mutableMapOf<String, Any>("status" to "ok")

      val debugInfo = debugInfoDeferred?.await()
      if (debugInfo != null) {
        result["ad_account_enabled"] = debugInfo.adAccountEnabled
        result["mm_ad_active"] = debugInfo.mmAdActive
        result["whmcs_active"] = debugInfo.whmcsActive
        debugInfo.daysInCurrentWhmcsStatus?.let { result["days_in_current_whmcs_status"] = it }
        debugInfo.totalActiveDays?.let { days ->
          result["total_active_days"] = days
          result["total_active_duration"] = formatDurationFromDays(days)
        }
        if (debugInfo.timeline.isNotEmpty()) {
          result["product_timeline"] =
              debugInfo.timeline.map { entry ->
                mapOf(
                    "type" to entry.type.name,
                    "start_date" to entry.startDate.toString(),
                    "end_date" to (entry.endDate?.toString() ?: "Present"),
                    "duration_days" to entry.durationDays,
                )
              }
        }
      }

      val swipes = swipesDeferred?.await()
      if (swipes != null) {
        result["recent_badge_swipes"] =
            swipes.map {
              mapOf(
                  "swipe_time" to it.swipeTime, "door_name" to it.doorName, "granted" to it.granted)
            }
      }

      val badgeMM = badgeMMDeferred.await()
      val badgeAD = badgeADDeferred.await()
      if (badgeMM == null && badgeAD == null) {
        // Both sources returned null — no badge data available from live fetch
      } else {
        badgeMM?.let { result["badge_number_mm"] = it }
        badgeAD?.let { result["badge_number_ad"] = it }
      }
      val badgeToCheck = badgeAD
      if (badgeToCheck != null && badgeToCheck.length < 10) {
        result["badge_needs_fix"] = true
        result["badge_padded"] = badgeToCheck.padStart(10, '0')
      }

      call.respond(result)
    } catch (e: Exception) {
      log.warn("Failed to fetch debug info for member: $requestedUsername", e)
      call.respond(
          HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch debug info"))
    }
  }

  @Suppress("MagicNumber")
  private fun formatDurationFromDays(totalDays: Int): String {
    if (totalDays <= 0) return "0 days"
    val years = totalDays / 365
    val remainingDaysAfterYears = totalDays % 365
    val months = remainingDaysAfterYears / 30
    val days = remainingDaysAfterYears % 30
    return when {
      years > 1 && months > 0 -> "$years years, $months months"
      years > 1 -> "$years years"
      years == 1 && months > 0 -> "$years year, $months months"
      years == 1 -> "$years year"
      months > 1 -> "$months months"
      months == 1 -> "$months month"
      days > 1 -> "$days days"
      else -> "$days day"
    }
  }
}
