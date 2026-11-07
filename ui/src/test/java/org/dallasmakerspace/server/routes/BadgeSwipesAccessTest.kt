package org.dallasmakerspace.server.routes

import io.ktor.http.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BadgeSwipesAccessTest {
  private val chair = listOf("/Automotive Committee Chair")
  private val plain = listOf("/Some Group")

  @Test
  fun `officers and infrastructure can read any door or none named`() {
    assertTrue(canReadBadgeSwipes(true, plain, emptyList<String>()))
    assertTrue(canReadBadgeSwipes(true, plain, listOf("Front-In")))
  }

  @Test
  fun `a chair can read only their own area's doors, and must name them`() {
    assertTrue(canReadBadgeSwipes(false, chair, listOf("Garage-In")))
    assertFalse(canReadBadgeSwipes(false, chair, listOf("Garage-In", "Front-In")))
    assertFalse(canReadBadgeSwipes(false, chair, emptyList<String>()))
  }

  @Test
  fun `another committee's chair and ordinary members can read nothing`() {
    assertFalse(canReadBadgeSwipes(false, listOf("/Woodshop Committee Chair"), listOf("Garage-In")))
    assertFalse(canReadBadgeSwipes(false, plain, listOf("Garage-In")))
    assertFalse(canReadBadgeSwipes(false, plain, emptyList<String>()))
  }

  @Test
  fun `only the swipes data path is gated`() {
    assertTrue(isBadgeSwipesData("data-viz/badge-swipes"))
    assertFalse(isBadgeSwipesData("data-viz/visitors-by-date"))
  }

  @Test
  fun `a link from the assistant fills in the page`() {
    val params =
        parametersOf(
            "doors" to listOf("Garage-In"),
            "from" to listOf("2026-11-05T17:00"),
            "to" to listOf("2026-11-05T23:59"))
    val model = badgeSwipesPageModel(false, chair, params)

    assertEquals(true, model["allowed"])
    assertEquals(
        "/backend-api/data-viz/badge-swipes?doors=Garage-In&from=2026-11-05T17%3A00&to=2026-11-05T23%3A59",
        model["dataUrl"])
    assertEquals("Badge swipes: Garage-In from 2026-11-05 17:00 to 2026-11-05 23:59", model["title"])
  }

  @Test
  fun `a chair who names no door gets theirs, and is refused another door`() {
    assertEquals(
        "/backend-api/data-viz/badge-swipes?doors=Garage-In",
        badgeSwipesPageModel(false, chair, parametersOf())["dataUrl"])
    assertEquals(false, badgeSwipesPageModel(false, chair, parametersOf("doors", "Front-In"))["allowed"])
    assertEquals(false, badgeSwipesPageModel(false, plain, parametersOf())["allowed"])
  }

  @Test
  fun `the plain report page is unchanged for officers`() {
    val model = badgeSwipesPageModel(true, plain, parametersOf())

    assertEquals("/backend-api/data-viz/badge-swipes", model["dataUrl"])
    assertEquals("Recent Badge Swipes", model["title"])
  }
}
