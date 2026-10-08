package org.dallasmakerspace.askai.search

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.moodle.LearnCatalog
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MoodleCourseSearchSourceTest {

  private val catalogService = mock<MoodleCatalogService>()
  private val configOverrideService = mock<ConfigOverrideService>()
  private val source =
      MoodleCourseSearchSource(
          catalogService,
          configOverrideService,
          mock<LoggerFactory>().also { whenever(it.create(any())).thenReturn(mock<Logger>()) },
      )

  private fun course(id: Int, name: String) =
      LearnCourse(
          id = id,
          shortname = "EX-$id",
          name = name,
          summary = "Self-paced quiz",
          url = "https://learn.example.org/course/view.php?id=$id",
          visible = true,
          categoryName = "Example",
          committeeSlug = "laser",
          committeeName = "Laser",
      )

  private suspend fun stub(enabled: Boolean) {
    whenever(configOverrideService.get(eq(ConfigRegistry.MOODLE_ENABLED), any()))
        .thenReturn(FeatureFlagValue(enabled = enabled))
    whenever(catalogService.getCatalog(anyOrNull()))
        .thenReturn(
            LearnCatalog(
                "https://learn.example.org",
                listOf(course(1, "Example Laser Safety"), course(2, "Example Table Saw Basics"))))
  }

  @Test
  fun `returns matching courses when the flag is globally on`() = runBlocking<Unit> {
    stub(enabled = true)

    val results = source.search("how do I use the laser", 10)

    // Both courses are in the Laser committee; the one named for it ranks first.
    assertEquals("Example Laser Safety (DMS Learn online course)", results.first().title)
    assertEquals("DMS Learn", results.first().source)
    assertTrue(results.first().snippet.contains("Laser committee"))
  }

  @Test
  fun `queries that match nothing return nothing`() = runBlocking<Unit> {
    stub(enabled = true)
    assertTrue(source.search("pottery wheel", 10).isEmpty())
  }

  @Test
  fun `returns nothing while the flag is off`() = runBlocking<Unit> {
    stub(enabled = false)
    assertTrue(source.search("laser", 10).isEmpty())
  }
}
