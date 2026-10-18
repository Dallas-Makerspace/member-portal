package org.dallasmakerspace.server.subscriptions

import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.ConfirmCopy
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionButtonTest {

  private val copy =
      SubscriptionCopy(
          subscribeLabel = "Follow",
          subscribedLabel = "Following",
          unsubscribeLabel = "Unfollow",
          subscribeIcon = "person_add",
          subscribedIcon = "check",
          unsubscribeIcon = "person_remove",
          subscribeBusy = "Following…",
          unsubscribeBusy = "Unfollowing…",
          subscribedToast = "Followed",
          unsubscribedToast = "Unfollowed",
          hint = "A hint",
          subscribedHint = "A subscribed hint",
          subscribeConfirm = ConfirmCopy("Follow?", "Sure?", "Follow"))

  private fun status(subscribed: Boolean) =
      SubscriptionStatus("TOPIC", mapOf("username" to "asmith"), subscribed, copy)

  @Test
  fun notSubscribedOffersTheActionInPrimaryStyle() {
    val model = subscriptionButtonModel(status(false))

    assertEquals("Follow", model["label"])
    assertEquals("person_add", model["icon"])
    assertEquals("btn-primary", model["btnClass"])
    assertEquals(false, model["subscribed"])
    assertEquals(false, model["iconOnly"])
    assertEquals("A hint", model["hintOff"])
    assertEquals("A subscribed hint", model["hintOn"])
  }

  @Test
  fun subscribedShowsTheStateAtRest() {
    val model = subscriptionButtonModel(status(true))

    assertEquals("Following", model["label"])
    assertEquals("check", model["icon"])
    assertEquals("btn-outline-primary", model["btnClass"])
  }

  @Test
  fun iconVariantIsGhostWhenOffAndTintedWhenOnWithNoHint() {
    val off = subscriptionButtonModel(status(false), VARIANT_ICON)
    val on = subscriptionButtonModel(status(true), VARIANT_ICON)

    assertEquals(true, off["iconOnly"])
    assertEquals("btn-outline-secondary", off["btnClass"])
    assertEquals("btn-outline-primary dms-subscribe-on", on["btnClass"])
    assertNull(off["hintOff"])
    assertNull(off["hintOn"])
    assertNull(on["hintOff"])
    assertNull(on["hintOn"])
  }

  @Test
  fun hintIdIsUniquePerInstanceAndSafeAsAnId() {
    val other = status(false).copy(args = mapOf("username" to "b smith/x"))

    val first = subscriptionButtonModel(status(false))["hintId"] as String
    val second = subscriptionButtonModel(other)["hintId"] as String

    assertEquals("dms-sub-hint-TOPIC-asmith", first)
    assertEquals("dms-sub-hint-TOPIC-b_smith_x", second)
  }

  @Test
  fun iconVariantIsNamedByTheActionAClickPerforms() {
    assertEquals("Follow", subscriptionButtonModel(status(false), VARIANT_ICON)["actionLabel"])
    assertEquals("Unfollow", subscriptionButtonModel(status(true), VARIANT_ICON)["actionLabel"])
  }

  @Test
  fun overridesReachBothTheVisibleLabelAndTheScriptCopy() {
    val model =
        subscriptionButtonModel(
            status(false),
            subscribeLabel = "Notify me",
            subscribedLabel = "Notifying",
            unsubscribeLabel = "Stop",
            subscribeIcon = "notifications",
            subscribedIcon = "notifications_active",
            unsubscribeIcon = "notifications_off")

    assertEquals("Notify me", model["label"])
    assertEquals("notifications", model["icon"])
    val scriptCopy = Json.decodeFromString(SubscriptionCopy.serializer(), model["copyJson"] as String)
    assertEquals("Notify me", scriptCopy.subscribeLabel)
    assertEquals("Notifying", scriptCopy.subscribedLabel)
    assertEquals("Stop", scriptCopy.unsubscribeLabel)
    assertEquals("notifications_active", scriptCopy.subscribedIcon)
    assertEquals("notifications_off", scriptCopy.unsubscribeIcon)
    assertEquals("Follow?", scriptCopy.subscribeConfirm?.title)
  }

  @Test
  fun argsAndCopyAreEncodedAsJson() {
    val model = subscriptionButtonModel(status(false))

    assertTrue((model["argsJson"] as String).contains("\"username\":\"asmith\""))
    assertTrue((model["copyJson"] as String).contains("\"subscribedLabel\":\"Following\""))
  }
}
