package org.dallasmakerspace.server.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GoogleDocsInterstitialTest {

  @Test
  fun matchesDocsFamilyHosts() {
    val email = "tbjk@dallasmakerspace.org"
    assertEquals(
        "https://docs.google.com/document/d/abc123/edit?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://docs.google.com/document/d/abc123/edit", email),
    )
    assertEquals(
        "https://sheets.google.com/spreadsheets/d/abc123/edit?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://sheets.google.com/spreadsheets/d/abc123/edit", email),
    )
    assertEquals(
        "https://slides.google.com/presentation/d/abc123/edit?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://slides.google.com/presentation/d/abc123/edit", email),
    )
    assertEquals(
        "https://forms.google.com/forms/d/abc123/viewform?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://forms.google.com/forms/d/abc123/viewform", email),
    )
    assertEquals(
        "https://forms.gle/abc123?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://forms.gle/abc123", email),
    )
    assertEquals(
        "https://drive.google.com/file/d/abc123/view?authuser=tbjk%40dallasmakerspace.org",
        googleDocsDestinationOrNull("https://drive.google.com/file/d/abc123/view", email),
    )
  }

  @Test
  fun returnsNullForNonGoogleDestinations() {
    val email = "tbjk@dallasmakerspace.org"
    assertNull(googleDocsDestinationOrNull("https://example.com/some/page", email))
    assertNull(googleDocsDestinationOrNull("https://calendar.google.com/event?eid=abc123", email))
    assertNull(googleDocsDestinationOrNull("not a url", email))
  }

  @Test
  fun replacesExistingAuthUserParamInsteadOfDuplicating() {
    val result =
        googleDocsDestinationOrNull(
            "https://docs.google.com/document/d/abc123/edit?authuser=0",
            "tbjk@dallasmakerspace.org",
        )
    assertEquals(
        "https://docs.google.com/document/d/abc123/edit?authuser=tbjk%40dallasmakerspace.org",
        result,
    )
  }

  @Test
  fun preservesOtherExistingQueryParams() {
    val result =
        googleDocsDestinationOrNull(
            "https://docs.google.com/document/d/abc123/edit?usp=sharing",
            "tbjk@dallasmakerspace.org",
        )
    assertEquals(
        "https://docs.google.com/document/d/abc123/edit?usp=sharing&authuser=tbjk%40dallasmakerspace.org",
        result,
    )
  }

  @Test
  fun returnsOriginalLocationWhenEmailNullButStillGoogleDestination() {
    val location = "https://docs.google.com/document/d/abc123/edit"
    assertEquals(location, googleDocsDestinationOrNull(location, null))
  }
}
