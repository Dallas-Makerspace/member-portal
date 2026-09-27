package org.dallasmakerspace.server.routes

import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ProfilePhotoHandlerTest {

  @Test
  fun `same host origin is allowed`() {
    assertEquals(true, isSameOriginHeader("localhost:8000", "http://localhost:8000"))
  }

  @Test
  fun `same host referer is allowed`() {
    assertEquals(
        true, isSameOriginHeader("dms.example.org", "https://dms.example.org/profile/@jdoe"))
  }

  @Test
  fun `different host origin is rejected`() {
    assertEquals(false, isSameOriginHeader("dms.example.org", "https://evil.example.com"))
  }

  @Test
  fun `missing Origin and Referer is allowed`() {
    assertEquals(true, isSameOriginHeader("dms.example.org", null))
  }

  @Test
  fun `missing Host header is rejected`() {
    assertEquals(false, isSameOriginHeader(null, "https://dms.example.org"))
  }

  @Test
  fun `unparseable origin header is rejected`() {
    assertEquals(false, isSameOriginHeader("dms.example.org", "not a valid uri"))
  }
}
