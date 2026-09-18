package org.dallasmakerspace.server.bambustatus

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.models.Committees
import org.junit.Test
import org.mockito.kotlin.mock

class BambuStatusCommitteeSectionTest {

  private val section = BambuStatusCommitteeSection(mock())

  @Test
  fun `applies only to the 3D Fab committee`() {
    val threeDFab = Committees.findByName("3D Fab")!!
    assertTrue(section.appliesTo(threeDFab))
  }

  @Test
  fun `does not apply to other committees`() {
    Committees.ALL.filter { it.slug != "3d-fab" }
        .forEach { committee -> assertFalse(section.appliesTo(committee)) }
  }
}
