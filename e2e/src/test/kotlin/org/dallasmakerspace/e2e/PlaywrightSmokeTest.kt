package org.dallasmakerspace.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.junit.UsePlaywright
import org.junit.jupiter.api.Test

@UsePlaywright(PlaywrightE2EOptions::class)
class PlaywrightSmokeTest {

  @Test
  fun playwrightLaunchesAndReadsPageContent(page: Page) {
    page.navigate(
        "data:text/html,<html><head><title>Playwright OK</title></head>" +
            "<body><h1>Harness works</h1></body></html>")
    assertThat(page).hasTitle("Playwright OK")
    assertThat(page.locator("h1")).hasText("Harness works")
  }
}
