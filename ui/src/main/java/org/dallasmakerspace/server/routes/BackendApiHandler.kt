package org.dallasmakerspace.server.routes

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient

internal const val WAIVER_REPORT_ALLOWED_USERNAMES_KEY = "waiver-report.allowed-usernames"

// Scoped to this handler only: the app-wide ContentNegotiation Gson decodes every JSON number as
// Double, so whole numbers (e.g. namespaceId: 1) come back as 1.0 and break strict Int
// deserialization when this handler forwards the map on to the backend service. Using a
// separate, locally-configured Gson here (instead of changing the global config) keeps this fix
// from affecting unrelated call sites like ActionTrackingHandler's Elasticsearch payloads.
private val bodyGson: Gson =
    GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
private val bodyMapType = object : TypeToken<Map<String, Any?>>() {}.type

/**
 * Service path prefixes the generic proxy must never forward. The service authorizes these by the
 * UI's own API credentials, not the end user, so proxying them would let any member read other
 * members' notifications or DMS Learn course progress, emit emails to anyone, or trigger webhook
 * fan-outs. The UI reaches them only through dedicated, user-checked handlers.
 */
private val BLOCKED_PREFIXES = setOf("notifications", "webhook", "moodle")

/** Path segments after normalizing encoding and dot/empty segments, lowercased; null if traversal. */
private fun proxiedSegments(path: String): List<String>? {
  val decoded = runCatching { java.net.URLDecoder.decode(path.substringBefore('?'), "UTF-8") }
      .getOrDefault(path)
  val segments = decoded.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
  if (segments.any { it == ".." }) return null
  return segments.map { it.lowercase() }
}

internal fun proxiedRootSegment(path: String): String? = proxiedSegments(path)?.firstOrNull()

/** `members/{username}/kiosk-waiver-photos`: served only by the dedicated, permission-checked handler. */
internal fun isKioskPhotoPath(path: String): Boolean {
  val segments = proxiedSegments(path) ?: return false
  return segments.size >= 3 && segments[0] == "members" && segments[2] == "kiosk-waiver-photos"
}

/** The data-viz report that lists individual signers; its page is gated by [isWaiverReportAuthorized]. */
internal fun isWaiverReportPath(path: String): Boolean =
    proxiedSegments(path)?.take(2) == listOf("data-viz", "recent-signed-waivers")

internal fun isProxyAllowed(path: String): Boolean {
  val root = proxiedRootSegment(path) ?: return false
  return root !in BLOCKED_PREFIXES && !isKioskPhotoPath(path)
}

/** Whether [username] is listed in the comma-separated `waiver-report.allowed-usernames` value. */
internal fun isWaiverReportAllowlisted(username: String, allowedUsernames: String?): Boolean =
    allowedUsernames.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .contains(username)

class BackendApiHandler(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val path = call.request.path().removePrefix("/backend-api/")
    val queryString = call.request.queryString()
    val fullPath = if (queryString.isNotEmpty()) "$path?$queryString" else path
    val username = userInfo["preferred_username"] as? String
    if (!isProxyAllowed(path)) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    if (isWaiverReportPath(path) && !canViewWaiverReport(username.orEmpty())) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val result =
        when (call.request.httpMethod) {
          HttpMethod.Get -> memberService.callBackendApi(fullPath, session.sessionId, username)
          HttpMethod.Post -> {
            val body = receiveJsonBody(call)
            memberService.callBackendApiPost(fullPath, session.sessionId, username, body)
          }
          HttpMethod.Patch -> {
            val body = receiveJsonBody(call)
            memberService.callBackendApiPatch(fullPath, session.sessionId, username, body)
          }
          HttpMethod.Delete ->
              memberService.callBackendApiDelete(fullPath, session.sessionId, username)
          else ->
              throw IllegalArgumentException("Unsupported HTTP method: ${call.request.httpMethod}")
        }
    call.respond(result)
  }

  private suspend fun canViewWaiverReport(username: String): Boolean =
      authz.can(Permission.MANAGE_BADGES.name) ||
          isWaiverReportAllowlisted(
              username,
              memberServiceClient.getConfigValue(WAIVER_REPORT_ALLOWED_USERNAMES_KEY, session.sessionId))

  private suspend fun receiveJsonBody(call: ApplicationCall): Map<String, Any?> {
    val text = call.receiveText()
    if (text.isBlank()) return emptyMap()
    return bodyGson.fromJson(text, bodyMapType)
  }
}
