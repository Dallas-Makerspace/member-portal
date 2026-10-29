package org.dallasmakerspace.agenttools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.junit.Test

class GetCommitteesToolTest {
  private val tool = GetCommitteesTool()
  private val context = ToolContext("jdoe", "c1", "ask-ai")

  private fun run(name: String? = null) = runBlocking {
    tool.run(if (name == null) JsonObject(emptyMap()) else buildJsonObject { put("name", name) }, context)
  }

  @Test
  fun `a name filter is a case-insensitive substring and returns full details`() {
    val r = run("WOOD")

    assertTrue(r.output.contains("Committee: Woodshop"))
    assertTrue(r.output.contains("Chair group: Woodshop Committee Chair"))
    assertTrue(r.output.contains("Group name prefixes: Woodshop, WS"))
    assertTrue(r.output.contains("DMS Learn categories: WOOD"))
    assertTrue(r.output.contains("Homepage: https://"))
    assertTrue(r.output.contains("About: "))
    assertFalse(r.output.contains("Committee: Laser"))
    assertEquals(listOf("Woodshop committee"), r.sources.map { it.title })
  }

  @Test
  fun `no filter lists every active committee within the size cap`() {
    val r = run()

    assertTrue(r.output.contains("Committee: Laser"))
    assertTrue(r.output.contains("Committee: Blacksmith"))
    assertTrue(r.output.length <= tool.maxResultChars, "was ${r.output.length}")
    assertTrue(r.output.contains("Descriptions are shortened"))
    assertTrue(r.sources.size <= 6)
  }

  @Test
  fun `committees without data say none listed and an unknown name matches nothing`() {
    assertTrue(run("logistics").output.contains("Homepage: none listed"))
    val none = run("zzz")
    assertFalse(none.isError)
    assertTrue(none.output.startsWith("No active committee"))
  }

  @Test
  fun `it exposes no member data and needs no member access`() {
    assertFalse(tool.touchesPersonalInfo)
    assertEquals(MemberAccess.SELF_ONLY, tool.requiredMemberAccess)
    assertEquals("get_committees", tool.descriptor.name)
    assertFalse(run().output.contains("jdoe"))
  }
}
