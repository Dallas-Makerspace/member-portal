package org.dallasmakerspace.e2e.pages

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState

class HomePage(private val page: Page) {

  val welcomeHeading: Locator get() = page.locator("h1.welcome-member-22")

  val viewProfileLink: Locator get() = page.locator("#editYourProfile a")

  val suggestedEventsHeading: Locator get() = page.locator("h2.suggested-events-heading")

  val suggestedEventsSkeleton: Locator get() = page.locator("#suggestedEventsSkeleton")

  val suggestedEventsEmpty: Locator get() = page.locator("#suggestedEventsEmpty")

  val suggestedEventCards: Locator get() = page.locator("#suggestedEventsContent a.suggested-event-card")

  fun openViaDevLogin(baseUrl: String) {
    page.navigate("$baseUrl/dev-login")
  }

  fun waitForSuggestedEventsLoaded() {
    suggestedEventsSkeleton.waitFor(
        Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(15_000.0))
  }
}
