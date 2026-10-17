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

  @Test
  fun blocksKioskPhotoPaths() {
    listOf(
            "members/jdoe/kiosk-waiver-photos",
            "members/jdoe/kiosk-waiver-photos?x=1",
            "/members/jdoe/kiosk-waiver-photos",
            "Members/jdoe/Kiosk-Waiver-Photos",
            "members/jdoe/%6Biosk-waiver-photos",
            "members/./jdoe/kiosk-waiver-photos",
        )
        .forEach { assertFalse(it, isProxyAllowed(it)) }
    assertTrue(isProxyAllowed("members/jdoe/follow"))
  }

  @Test
  fun identifiesWaiverReportPath() {
    listOf(
            "data-viz/recent-signed-waivers",
            "data-viz/recent-signed-waivers?days=7",
            "/data-viz/Recent-Signed-Waivers",
            "data-viz/%72ecent-signed-waivers",
            "data-viz/./recent-signed-waivers",
        )
        .forEach { assertTrue(it, isWaiverReportPath(it)) }
    assertFalse(isWaiverReportPath("data-viz/signed-waivers"))
    assertFalse(isWaiverReportPath("members/jdoe/recent-signed-waivers"))
  }

  @Test
  fun waiverReportAllowlist() {
    assertTrue(isWaiverReportAllowlisted("jdoe", "asmith, jdoe ,bsmith"))
    assertFalse(isWaiverReportAllowlisted("jdoe", "asmith,bsmith"))
    assertFalse(isWaiverReportAllowlisted("jdoe", ""))
    assertFalse(isWaiverReportAllowlisted("jdoe", null))
    assertFalse(isWaiverReportAllowlisted("", ",,"))
  }
}
