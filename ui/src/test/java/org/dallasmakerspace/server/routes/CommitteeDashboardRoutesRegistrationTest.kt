package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CommitteeDashboardRoutesRegistrationTest {

  private fun resolved(requestPath: String, expected: RouteFactory.Paths) {
    val path = RouteFactory.sanitizePathInternal(requestPath)
    assertEquals(expected.path, path)
    assertNotNull(RouteFactory.getHandler(path), "no handler for $path")
  }

  @Test
  fun `the editor page and its preview resolve to handlers`() {
    resolved("/committees/woodshop/dashboard", RouteFactory.Paths.COMMITTEE_DASHBOARD)
    resolved("/committees/woodshop/dashboard/preview", RouteFactory.Paths.COMMITTEE_DASHBOARD_PREVIEW)
  }

  @Test
  fun `every editor api path resolves to the one api handler`() {
    listOf(
            "/committees/woodshop/dashboard-api/dashboard",
            "/committees/woodshop/dashboard-api/dashboard/announcements/12",
            "/committees/woodshop/dashboard-api/cast-targets/3/revoke")
        .forEach { resolved(it, RouteFactory.Paths.COMMITTEE_DASHBOARD_API) }
  }

  @Test
  fun `the public TV paths resolve to handlers`() {
    resolved("/tv/receiver", RouteFactory.Paths.TV_RECEIVER)
    resolved("/tv/api/dashboard", RouteFactory.Paths.TV_API_DASHBOARD)
  }
}
