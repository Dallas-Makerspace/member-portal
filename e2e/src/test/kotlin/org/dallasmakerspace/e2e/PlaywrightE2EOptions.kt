package org.dallasmakerspace.e2e

import com.microsoft.playwright.junit.Options
import com.microsoft.playwright.junit.OptionsFactory

class PlaywrightE2EOptions : OptionsFactory {
  override fun getOptions(): Options =
      Options().setBrowserName("chromium").setHeadless(true)
}
