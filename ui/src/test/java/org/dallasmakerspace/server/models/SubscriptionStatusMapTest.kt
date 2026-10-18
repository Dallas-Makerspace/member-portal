package org.dallasmakerspace.server.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionStatusMapTest {

  private fun copyMap(): MutableMap<String, Any?> =
      mutableMapOf(
          "subscribeLabel" to "Follow",
          "unsubscribeLabel" to "Unfollow",
          "subscribeIcon" to "person_add",
          "unsubscribeIcon" to "person_remove",
          "subscribeBusy" to "Following…",
          "unsubscribeBusy" to "Unfollowing…",
          "subscribedToast" to "Followed",
          "unsubscribedToast" to "Unfollowed",
          "hint" to "A hint",
          "subscribeConfirm" to
              mapOf("title" to "Follow?", "message" to "Sure?", "confirmLabel" to "Follow"))

  private fun statusMap(copy: Map<String, Any?> = copyMap()) =
      mapOf(
          "topic" to "TOPIC",
          "args" to mapOf("username" to "asmith"),
          "subscribed" to true,
          "copy" to copy)

  @Test
  fun parsesAFullResponse() {
    val status = subscriptionStatusFromMap(statusMap())

    assertNotNull(status)
    assertEquals("TOPIC", status!!.topic)
    assertEquals(mapOf("username" to "asmith"), status.args)
    assertEquals(true, status.subscribed)
    assertEquals("Follow?", status.copy.subscribeConfirm?.title)
    assertNull(status.copy.unsubscribeConfirm)
    assertEquals("A hint", status.copy.hint)
  }

  @Test
  fun optionalFieldsMayBeAbsent() {
    val copy = copyMap().apply {
      remove("hint")
      remove("subscribeConfirm")
    }

    val status = subscriptionStatusFromMap(statusMap(copy))

    assertNotNull(status)
    assertNull(status!!.copy.hint)
    assertNull(status.copy.subscribeConfirm)
  }

  @Test
  fun missingRequiredFieldsGiveNull() {
    assertNull(subscriptionStatusFromMap(statusMap(copyMap().apply { remove("subscribeLabel") })))
    assertNull(subscriptionStatusFromMap(statusMap() - "subscribed"))
    assertNull(subscriptionStatusFromMap(statusMap() - "copy"))
    assertNull(subscriptionStatusFromMap(emptyMap<String, Any?>()))
  }
}
