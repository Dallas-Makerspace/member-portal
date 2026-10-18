package org.dallasmakerspace.notifications.subscriptions

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.models.ConfirmCopy
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.topics.FollowingTopic

/** "Follow a member" - args: `username`, the member being followed. */
@Singleton
class FollowMemberSubscriptionHandler
@Inject
constructor(private val service: ProfileSubscriptionService) : TopicSubscriptionHandler {

  override val topic = FollowingTopic
  override val featureFlag = ConfigRegistry.FOLLOW_FEATURE_ENABLED
  override val argNames = setOf(ARG_USERNAME)

  override suspend fun isSubscribed(actor: String, args: Map<String, String>) =
      service.isFollowing(actor, args.getValue(ARG_USERNAME))

  override suspend fun subscribe(actor: String, args: Map<String, String>) =
      service.follow(actor, args.getValue(ARG_USERNAME))

  override suspend fun unsubscribe(actor: String, args: Map<String, String>) =
      service.unfollow(actor, args.getValue(ARG_USERNAME))

  override fun copy(args: Map<String, String>): SubscriptionCopy {
    val target = "@${args.getValue(ARG_USERNAME)}"
    return SubscriptionCopy(
        subscribeLabel = "Follow",
        subscribedLabel = "Following",
        unsubscribeLabel = "Unfollow",
        subscribeIcon = "person_add",
        subscribedIcon = "check",
        unsubscribeIcon = "person_remove",
        subscribeBusy = "Following…",
        unsubscribeBusy = "Unfollowing…",
        subscribedToast =
            "You're following $target. You'll be notified when they post an event or a project.",
        unsubscribedToast = "You unfollowed $target.",
        hint = "Get notified when they post an event or a project",
        subscribedHint = "You'll get an email when they post an event or a project",
        subscribeConfirm =
            ConfirmCopy(
                title = "Follow $target?",
                message =
                    "You'll get an email when $target posts an event or a project. " +
                        "You can turn this off in Notification Preferences.",
                confirmLabel = "Follow"),
    )
  }

  companion object {
    const val ARG_USERNAME = "username"
  }
}
