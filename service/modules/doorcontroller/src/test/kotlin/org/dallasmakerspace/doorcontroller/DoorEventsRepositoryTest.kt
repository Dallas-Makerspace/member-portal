package org.dallasmakerspace.doorcontroller

import kotlin.test.assertEquals
import org.junit.Test

class DoorEventsRepositoryTest {
  @Test
  fun `the dedup maximum counts scraped rows only, so imported numbering cannot hide new events`() {
    val sql = DoorEventsRepository().maxDbindexSql(listOf(2, 4))

    assertEquals(
        "SELECT controllerId, slotNumber, MAX(dbindex) AS maxDbindex FROM `AccessControl`.`events` " +
            "WHERE controllerId IN (2,4) AND dbindextype = 1 GROUP BY controllerId, slotNumber",
        sql)
  }
}
