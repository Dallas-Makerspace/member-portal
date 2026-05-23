package org.dallasmakerspace.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.junit.UsePlaywright
import org.dallasmakerspace.e2e.pages.HomePage
import org.dallasmakerspace.e2e.support.E2EEnvironment
import org.junit.jupiter.api.Test

@UsePlaywright(PlaywrightE2EOptions::class)
class HomePageTest {

  @Test
  fun homePageDisplaysAllComponents(page: Page) {
    E2EEnvironment.requireServerUp()

    val home = HomePage(page)
    home.openViaDevLogin(E2EEnvironment.baseUrl)

    assertThat(page).hasTitle("DMS Member Portal")

    assertBasicComponents(home)
    assertSuggestedEventsComponents(home)
  }

  private fun assertBasicComponents(home: HomePage) {
    assertThat(home.welcomeHeading).hasText("Welcome, Dev User One!")
    assertThat(home.viewProfileLink).hasText("View your profile")
    assertThat(home.viewProfileLink).hasAttribute("href", "./profile/@user1")
  }

  private fun assertSuggestedEventsComponents(home: HomePage) {
    assertThat(home.suggestedEventsHeading).hasText("Suggested Events For You")

    home.waitForSuggestedEventsLoaded()

    assertThat(home.suggestedEventsSkeleton).isHidden()
    if (home.suggestedEventCards.count() == 0) {
      assertThat(home.suggestedEventsEmpty)
          .hasText("No upcoming events to suggest right now.")
    }
  }
}
