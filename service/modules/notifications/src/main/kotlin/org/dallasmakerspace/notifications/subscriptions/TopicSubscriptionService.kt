package org.dallasmakerspace.notifications.subscriptions

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.models.SubscriptionStatus

sealed interface SubscriptionResult {
  data class Ok(val status: SubscriptionStatus) : SubscriptionResult

  data class NotFound(val message: String) : SubscriptionResult

  data class Forbidden(val message: String) : SubscriptionResult

  data class BadRequest(val message: String) : SubscriptionResult
}

/** Looks up the [TopicSubscriptionHandler] for a topic key and applies the common checks. */
@Singleton
class TopicSubscriptionService
@Inject
constructor(
    private val handlers: Map<String, @JvmSuppressWildcards TopicSubscriptionHandler>,
    private val featureFlagService: FeatureFlagService,
) {

  suspend fun status(topicKey: String, actor: String, args: Map<String, String>) =
      execute(topicKey, actor, args) {}

  suspend fun subscribe(topicKey: String, actor: String, args: Map<String, String>) =
      execute(topicKey, actor, args) { it.subscribe(actor, args) }

  suspend fun unsubscribe(topicKey: String, actor: String, args: Map<String, String>) =
      execute(topicKey, actor, args) { it.unsubscribe(actor, args) }

  private suspend fun execute(
      topicKey: String,
      actor: String,
      args: Map<String, String>,
      action: suspend (TopicSubscriptionHandler) -> Unit,
  ): SubscriptionResult {
    val handler = handlers[topicKey] ?: return SubscriptionResult.NotFound("Unknown topic")
    if (args.keys != handler.argNames || args.values.any { it.isBlank() }) {
      return SubscriptionResult.BadRequest("Required arguments: ${handler.argNames.sorted()}")
    }
    val flag = handler.featureFlag
    if (flag != null && !featureFlagService.isEnabledForUsername(flag, actor)) {
      return SubscriptionResult.Forbidden("Not enabled for $actor")
    }
    return try {
      action(handler)
      SubscriptionResult.Ok(
          SubscriptionStatus(
              topic = topicKey,
              args = args,
              subscribed = handler.isSubscribed(actor, args),
              copy = handler.copy(args)))
    } catch (e: IllegalArgumentException) {
      SubscriptionResult.BadRequest(e.message ?: "Invalid request")
    } catch (e: IllegalStateException) {
      // ProfileResolver throws this when a member has no profile row.
      SubscriptionResult.BadRequest(e.message ?: "Invalid request")
    }
  }
}
