package org.dallasmakerspace.server.agent

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AgentRoutesTest {
  private val id = "20327921-9506-418d-9506-c90044f75d83"

  @Test
  fun `browser paths map onto service routes`() {
    assertEquals("/agent/bots", AgentRoutes.servicePath("GET", "/api/agent/bots"))
    assertEquals(
        "/agent/ask-ai/conversations", AgentRoutes.servicePath("POST", "/api/agent/ask-ai/conversations"))
    assertEquals(
        "/agent/conversations/$id/messages",
        AgentRoutes.servicePath("POST", "/api/agent/conversations/$id/messages"))
    assertEquals(
        "/agent/conversations/$id/stream",
        AgentRoutes.servicePath("GET", "/api/agent/conversations/$id/stream"))
    assertEquals(
        "/agent/conversations/$id", AgentRoutes.servicePath("DELETE", "/api/agent/conversations/$id"))
  }

  @Test
  fun `anything else is refused`() {
    assertNull(AgentRoutes.servicePath("GET", "/api/agent/conversations/../bots"))
    assertNull(AgentRoutes.servicePath("GET", "/api/agent/conversations/$id/messages"))
    assertNull(AgentRoutes.servicePath("PUT", "/api/agent/bots"))
    assertNull(AgentRoutes.servicePath("GET", "/api/agent/ask-ai/../admin/conversations"))
    assertNull(AgentRoutes.servicePath("GET", "/api/agent/Bad_Bot/conversations"))
    assertNull(AgentRoutes.servicePath("GET", "/api/agent/conversations/not-a-uuid"))
  }

  @Test
  fun `only known query parameters are forwarded`() {
    assertEquals(
        mapOf("scope" to "public", "limit" to "15"),
        AgentRoutes.allowedQuery(mapOf("scope" to "public", "limit" to "15", "x" to "y")))
  }
}
