package org.dallasmakerspace.server.bambustatus

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.subscriptions.subscriptionButtonModel

class BambuPrinterStatusHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val currentUsername = userInfo["preferred_username"] as? String
    val featureEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled("bambu-status.enabled", it, session.sessionId)
        } ?: false

    if (!featureEnabled) {
      call.respond(ThymeleafContent("bambu-printer-status-denied", mapOf("reason" to "disabled")))
      return
    }

    // Notification affordances are piloted independently of the status page itself.
    val notificationsEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled("bambu-notifications.enabled", it, session.sessionId)
        } ?: false
    val watches =
        if (notificationsEnabled && currentUsername != null) {
          memberServiceClient.getBambuWatches(session.sessionId, currentUsername)
        } else {
          emptyList()
        }
    val committeeSubscription =
        if (notificationsEnabled && currentUsername != null) {
          memberServiceClient
              .getSubscriptionStatus(
                  TopicKeys.BAMBU_COMMITTEE_ALERTS, emptyMap(), currentUsername, session.sessionId)
              ?.let { subscriptionButtonModel(it) }
        } else {
          null
        }

    val printers =
        memberServiceClient.getBambuPrinters(session.sessionId).map {
          buildPrinterViewModel(it, currentUsername, watches, notificationsEnabled)
        }
    val model = mutableMapOf<String, Any>(
        "printers" to printers,
        "notificationsEnabled" to notificationsEnabled,
    )
    committeeSubscription?.let { model["committeeSubscription"] = it }
    call.respond(ThymeleafContent("bambu-printer-status", model))
  }
}
