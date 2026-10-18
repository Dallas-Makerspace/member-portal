package org.dallasmakerspace.notifications.subscriptions

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.ConfirmCopy
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic

/** "Follow a committee" - args: `committee`, the committee's numeric id. */
@Singleton
class FollowCommitteeSubscriptionHandler
@Inject
constructor(private val repository: CommitteeSubscriptionsRepository) : TopicSubscriptionHandler {

  override val topic = CommitteeUpdatesTopic
  override val featureFlag = ConfigRegistry.COMMITTEE_FOLLOW_ENABLED
  override val argNames = setOf(ARG_COMMITTEE)

  override suspend fun isSubscribed(actor: String, args: Map<String, String>) =
      repository.isSubscribed(actor, committee(args).id)

  override suspend fun subscribe(actor: String, args: Map<String, String>) =
      repository.subscribe(actor, committee(args).id)

  override suspend fun unsubscribe(actor: String, args: Map<String, String>) =
      repository.unsubscribe(actor, committee(args).id)

  override fun copy(args: Map<String, String>): SubscriptionCopy {
    val name = committee(args).name
    return SubscriptionCopy(
        subscribeLabel = "Follow",
        subscribedLabel = "Following",
        unsubscribeLabel = "Unfollow",
        subscribeIcon = "notifications",
        subscribedIcon = "notifications_active",
        unsubscribeIcon = "notifications_off",
        subscribeBusy = "Following…",
        unsubscribeBusy = "Unfollowing…",
        subscribedToast = "You're following $name. You'll be notified when it adds a class.",
        unsubscribedToast = "You unfollowed $name.",
        hint = "Get notified when this committee adds a class",
        subscribedHint = "You'll be notified when this committee adds a class",
        subscribeConfirm =
            ConfirmCopy(
                title = "Follow $name?",
                message =
                    "You'll be notified when $name adds a class you haven't taken yet. " +
                        "You can change how in Notification Preferences.",
                confirmLabel = "Follow"),
    )
  }

  /**
   * A committee with no group prefixes can't be tied to a class, so there is nothing to follow
   * yet; reject it the same as an unknown id.
   */
  private fun committee(args: Map<String, String>): Committee {
    val found =
        args.getValue(ARG_COMMITTEE).toIntOrNull()?.let { Committees.findById(it) }?.takeIf {
          it.isActive
        }
    require(found != null) { "Unknown committee" }
    require(found.groupPrefixes.isNotEmpty()) { "${found.name} can't be followed yet" }
    return found
  }

  companion object {
    const val ARG_COMMITTEE = "committee"
  }
}
