package org.dallasmakerspace.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import org.dallasmakerspace.core.AppConfig
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SmartWaiverBackfillGuardTest {
  private fun config(value: String?): AppConfig {
    val appConfig = mock<AppConfig>()
    whenever(appConfig.getStringProperty("app.smartwaiver.backfill.auto-resume", "false"))
        .thenReturn(value ?: "false")
    return appConfig
  }

  @Test
  fun `a service resumes an interrupted backfill only when it is explicitly set to`() {
    assertEquals(false, shouldAutoResumeBackfill(config(null)), "unset (a local dev service) must not resume")
    assertEquals(false, shouldAutoResumeBackfill(config("false")))
    assertEquals(false, shouldAutoResumeBackfill(config("")))
    assertEquals(false, shouldAutoResumeBackfill(config("yes")))
    assertEquals(true, shouldAutoResumeBackfill(config("true")))
    assertEquals(true, shouldAutoResumeBackfill(config("TRUE")))
  }
}
