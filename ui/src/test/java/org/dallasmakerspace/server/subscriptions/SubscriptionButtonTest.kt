package org.dallasmakerspace.server.subscriptions

import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.ConfirmCopy
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionButtonTest {

  private val copy =
      SubscriptionCopy(
          subscribeLabel = "Follow",
          unsubscribeLabel = "Unfollow",
          subscribeIcon = "person_add",
          unsubscribeIcon = "person_remove",
          subscribeBusy = "Following…",
          unsubscribeBusy = "Unfollowing…",
          subscribedToast = "Followed",
          unsubscribedToast = "Unfollowed",
          hint = "A hint",
          subscribeConfirm = ConfirmCopy("Follow?", "Sure?", "Follow"))

  private fun status(subscribed: Boolean) =
      SubscriptionStatus("TOPIC", mapOf("username" to "asmith"), subscribed, copy)

  @Test
  fun notSubscribedShowsSubscribeLabelIconAndPrimaryStyle() {
    val model = subscriptionButtonModel(status(false))

    assertEquals("Follow", model["label"])
    assertEquals("person_add", model["icon"])
    assertEquals("btn-primary", model["btnClass"])
    assertEquals(false, model["subscribed"])
    assertEquals(false, model["iconOnly"])
    assertEquals("A hint", model["hint"])
  }

  @Test
  fun subscribedShowsUnsubscribeLabelIconAndOutlineStyle() {
    val model = subscriptionButtonModel(status(true))

    assertEquals("Unfollow", model["label"])
    assertEquals("person_remove", model["icon"])
    assertEquals("btn-outline-secondary", model["btnClass"])
  }

  @Test
  fun iconVariantDropsTheHint() {
    val model = subscriptionButtonModel(status(false), VARIANT_ICON)

    assertEquals(true, model["iconOnly"])
    assertNull(model["hint"])
  }

  @Test
  fun overridesReachBothTheVisibleLabelAndTheScriptCopy() {
    val model =
        subscriptionButtonModel(
            status(false),
            subscribeLabel = "Notify me",
            unsubscribeLabel = "Stop",
            subscribeIcon = "notifications",
            unsubscribeIcon = "notifications_off")

    assertEquals("Notify me", model["label"])
    assertEquals("notifications", model["icon"])
    val scriptCopy = Json.decodeFromString(SubscriptionCopy.serializer(), model["copyJson"] as String)
    assertEquals("Notify me", scriptCopy.subscribeLabel)
    assertEquals("Stop", scriptCopy.unsubscribeLabel)
    assertEquals("notifications_off", scriptCopy.unsubscribeIcon)
    assertEquals("Follow?", scriptCopy.subscribeConfirm?.title)
  }

  @Test
  fun argsAreEncodedAsJson() {
    val model = subscriptionButtonModel(status(false))

    assertTrue((model["argsJson"] as String).contains("\"username\":\"asmith\""))
    assertFalse((model["copyJson"] as String).isEmpty())
  }
}
