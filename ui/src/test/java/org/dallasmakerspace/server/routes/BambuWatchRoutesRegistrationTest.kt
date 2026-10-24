package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BambuWatchRoutesRegistrationTest {

  @Test
  fun `watch arm path is left alone and resolves to a handler`() {
    val path = RouteFactory.sanitizePathInternal("/bambu-api/watches")
    assertEquals(RouteFactory.Paths.BAMBU_WATCHES_API.path, path)
    assertNotNull(RouteFactory.getHandler(path))
  }

  @Test
  fun `watch cancel path is rewritten to the id template and resolves to a handler`() {
    val path = RouteFactory.sanitizePathInternal("/bambu-api/watches/42")
    assertEquals(RouteFactory.Paths.BAMBU_WATCH_API.path, path)
    assertNotNull(RouteFactory.getHandler(path))
  }
}
