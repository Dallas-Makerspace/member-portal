package org.dallasmakerspace.server.agent

import io.ktor.http.HttpMethod
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.server.workflows.ServiceResponse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class AgentBotAccessTest {
  private val body =
      """{"status":"SUCCESS","message":"OK","data":[{"key":"ask-ai","displayName":"Ask AI"},{"key":"it-assistant","displayName":"IT"}]}"""

  @Test
  fun `bot keys are read from the service response`() {
    assertEquals(setOf("ask-ai", "it-assistant"), AgentBotAccess.parseBotKeys(body))
    assertEquals(emptySet(), AgentBotAccess.parseBotKeys("not json"))
    assertEquals(emptySet(), AgentBotAccess.parseBotKeys("""{"status":"ERROR"}"""))
  }

  @Test
  fun `access follows the service answer and is cached per member`() = runBlocking<Unit> {
    val client = mock<AgentServiceClient>()
    org.mockito.kotlin.whenever(client.call(any(), any(), any(), anyOrNull(), any(), anyOrNull())) doReturn
        ServiceResponse(200, body)
    val access = AgentBotAccess(client)

    assertTrue(access.canUse("it-assistant", "jdoe", "s"))
    assertTrue(access.canUse("ask-ai", "jdoe", "s"))
    assertFalse(access.canUse("other-bot", "jdoe", "s"))
    assertFalse(access.canUse("it-assistant", null, "s"))
    verify(client, times(1)).call(eq(HttpMethod.Get), any(), any(), anyOrNull(), any(), anyOrNull())
  }

  @Test
  fun `a failing service means no access`() = runBlocking<Unit> {
    val client = mock<AgentServiceClient>()
    org.mockito.kotlin.whenever(client.call(any(), any(), any(), anyOrNull(), any(), anyOrNull())) doReturn
        ServiceResponse(500, "oops")

    assertFalse(AgentBotAccess(client).canUse("it-assistant", "asmith", "s"))
  }

  private fun <T> eq(value: T): T = org.mockito.kotlin.eq(value)
}
