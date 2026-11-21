package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayDescriptionTest {
  private fun media(state: String? = "PLAYING", title: String? = null, artist: String? = null) =
      DiscoveredMedia(state = state, title = title, artist = artist)

  @Test
  fun `nothing running and the ambient screen are told apart`() {
    assertEquals("Nothing running", DisplayStatus.describeDiscovered(null, null, null))
    assertEquals("Nothing running", DisplayStatus.describeDiscovered("  ", "", null))
    assertEquals("Idle (ambient screen)", DisplayStatus.describeDiscovered("Backdrop", "", null))
  }

  @Test
  fun `an app with no media is just its name`() {
    assertEquals("Spotify", DisplayStatus.describeDiscovered("Spotify", "Spotify", null))
    assertEquals("Spotify", DisplayStatus.describeDiscovered("Spotify", "", media()))
  }

  @Test
  fun `a title and artist follow the app, and a pause is noted`() {
    assertEquals("YouTube: Shop safety", DisplayStatus.describeDiscovered("YouTube", null, media(title = "Shop safety")))
    assertEquals(
        "Music: Song · Band",
        DisplayStatus.describeDiscovered("Music", null, media(title = "Song", artist = "Band")))
    assertEquals(
        "YouTube: Shop safety (paused)",
        DisplayStatus.describeDiscovered("YouTube", null, media(state = "PAUSED", title = "Shop safety")))
  }

  @Test
  fun `a status text that adds something is used when there is no title`() {
    assertEquals("Photos: Casting to album", DisplayStatus.describeDiscovered("Photos", "Casting to album", null))
  }

  @Test
  fun `only web addresses can become a link`() {
    assertEquals("https://media.example.test/a.mp4", DisplayStatus.safeLink(" https://media.example.test/a.mp4 "))
    assertEquals("http://media.example.test/a", DisplayStatus.safeLink("http://media.example.test/a"))
    assertNull(DisplayStatus.safeLink("javascript:alert(1)"))
    assertNull(DisplayStatus.safeLink("data:text/html,<b>x</b>"))
    assertNull(DisplayStatus.safeLink("abc123"))
    assertNull(DisplayStatus.safeLink(null))
    assertNull(DisplayStatus.safeLink("https://x.example.test/" + "a".repeat(1100)))
  }
}
