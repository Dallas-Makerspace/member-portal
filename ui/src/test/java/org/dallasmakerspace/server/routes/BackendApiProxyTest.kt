package org.dallasmakerspace.server.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendApiProxyTest {

  @Test
  fun allowsOrdinaryServicePaths() {
    assertTrue(isProxyAllowed("members/jdoe/follow"))
    assertTrue(isProxyAllowed("data-viz/some-report"))
  }

  @Test
  fun blocksNotificationAndWebhookPaths() {
    listOf(
            "notifications",
            "notifications?username=asmith",
            "notifications/preferences/asmith",
            "webhook/calendar-event",
            "/notifications",
            "./notifications",
            "Notifications",
            "%6Eotifications",
            "members/../notifications",
        )
        .forEach { assertFalse(it, isProxyAllowed(it)) }
  }
}
