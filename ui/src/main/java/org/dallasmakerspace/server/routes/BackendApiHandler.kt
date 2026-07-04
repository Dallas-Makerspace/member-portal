package org.dallasmakerspace.server.routes

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService

// Scoped to this handler only: the app-wide ContentNegotiation Gson decodes every JSON number as
// Double, so whole numbers (e.g. namespaceId: 1) come back as 1.0 and break strict Int
// deserialization when this handler forwards the map on to the backend service. Using a
// separate, locally-configured Gson here (instead of changing the global config) keeps this fix
// from affecting unrelated call sites like ActionTrackingHandler's Elasticsearch payloads.
private val bodyGson: Gson =
    GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
private val bodyMapType = object : TypeToken<Map<String, Any?>>() {}.type

class BackendApiHandler(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val path = call.request.path().removePrefix("/backend-api/")
    val queryString = call.request.queryString()
    val fullPath = if (queryString.isNotEmpty()) "$path?$queryString" else path
    val username = userInfo["preferred_username"] as? String

    val result =
        when (call.request.httpMethod) {
          HttpMethod.Get -> memberService.callBackendApi(fullPath, session.sessionId)
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

  private suspend fun receiveJsonBody(call: ApplicationCall): Map<String, Any?> {
    val text = call.receiveText()
    if (text.isBlank()) return emptyMap()
    return bodyGson.fromJson(text, bodyMapType)
  }
}
