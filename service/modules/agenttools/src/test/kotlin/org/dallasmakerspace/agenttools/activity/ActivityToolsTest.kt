package org.dallasmakerspace.agenttools.activity

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.junit.Test

class ActivityToolsTest {
  private val chair = listOf("Automotive Committee Chair")
  private val infra = listOf("Infrastructure")

  private fun context() = ToolContext("asker", "conversation", "ask-ai")

  private fun args(vararg extra: Pair<String, String>) =
      buildJsonObject {
        put("from", "2026-11-05T17:00")
        put("to", "2026-11-05T23:59")
        extra.forEach { (k, v) -> put(k, v) }
      }

  private fun presence(groups: List<String>, swipes: FakeSwipes = FakeSwipes(), sessions: FakeSessions = FakeSessions()) =
      AreaPresenceTool(
          activityAccess(groups), names("jdoe" to "Jane Doe", "asmith" to "Alex Smith"), swipes, sessions)

  private fun ToolResult.text(): String = output

  @Test
  fun `get_area_presence lists the strongest evidence first with names`() = blocking {
    val tool =
        presence(
            chair,
            FakeSwipes(listOf(swipe("17:55", "jdoe"), swipe("19:10", "asmith"))),
            FakeSessions(listOf(session("18:00", "19:30", "jdoe", endedBy = IDLE_TIMEOUT))))

    val result = tool.run(args("area" to "auto"), context())

    assertFalse(result.isError, result.text())
    val lines = result.text().lines()
    assertContains(lines[0], "2 people seen")
    assertTrue(lines[1].startsWith("1. [Jane Doe](/profile/@jdoe) | used Auto Lift 1 18:00-19:30 (ended by idle timeout); entered Garage-In 17:55"), lines[1])
    assertTrue(lines[2].startsWith("2. [Alex Smith](/profile/@asmith) | entered Garage-In 19:10"), lines[2])
    assertContains(result.text(), "not proof of who left a mess")
    assertEquals(
        listOf("Garage-In badge swipes", "Auto Lift 1 usage"), result.sources.map { it.title })
    assertEquals(
        "/reports/member-visits/badge-swipes?doors=Garage-In&from=2026-11-05T17:00&to=2026-11-05T23:59",
        result.sources.first().url)
    assertTrue(result.sources.last().url.startsWith("/interlock-tools?tool=auto-lift-1&days="))
    assertContains(result.text(), "Check the data: [Garage-In badge swipes](/reports/member-visits/badge-swipes?doors=Garage-In")
  }

  @Test
  fun `get_area_presence says so when nothing was recorded`() = blocking {
    val result = presence(infra).run(args("area" to "auto"), context())

    assertFalse(result.isError)
    assertContains(result.text(), "No door entries or tool sessions were recorded")
  }

  @Test
  fun `a door log that stops before the window ends is called out, not read as an empty room`() = blocking {
    val behind = presence(infra, FakeSwipes(latest = at("19:00")))
    val none = presence(infra, FakeSwipes(latest = null))
    val current = presence(infra, FakeSwipes(listOf(swipe("18:00", "jdoe")), latest = at("23:59", "2026-11-06")))

    assertContains(behind.run(args("area" to "auto"), context()).text(), "Door log gap: the last event recorded by these doors' controllers was Thu Nov 5 19:00")
    assertContains(none.run(args("area" to "auto"), context()).text(), "no events are recorded for these doors at all")
    assertFalse(current.run(args("area" to "auto"), context()).text().contains("Door log gap"))
  }

  @Test
  fun `get_badge_swipes also warns when the door log is behind`() = blocking {
    val tool = BadgeSwipesTool(activityAccess(chair), names(), FakeSwipes(latest = at("10:00", "2026-03-11")))

    assertContains(tool.run(args(), context()).text(), "Door log gap")
  }

  @Test
  fun `an unlinked badge shows only its last four digits`() = blocking {
    val tool = presence(infra, FakeSwipes(listOf(swipe("18:00", null, tail = "4321"))))

    val result = tool.run(args("area" to "auto"), context())

    assertContains(result.text(), "unlinked badge ending 4321")
  }

  @Test
  fun `a member who cannot see any area is refused without a search`() = blocking {
    val swipes = FakeSwipes()
    val result = presence(listOf("Automotive 102 (Lift Training)"), swipes).run(args("area" to "auto"), context())

    assertTrue(result.isError)
    assertEquals(null, swipes.lastDoors)
  }

  @Test
  fun `the tools are offered only to members who may ask about an area`() = blocking {
    val chairTool = presence(chair)
    val infraTool = presence(infra)
    val plain = presence(listOf("Automotive 102 (Lift Training)"))

    assertTrue(chairTool.isAvailableTo("asker"))
    assertTrue(infraTool.isAvailableTo("asker"))
    assertFalse(plain.isAvailableTo("asker"))
  }

  @Test
  fun `an unknown area or a bad window is reported to the model`() = blocking {
    val tool = presence(infra)

    assertContains(tool.run(args("area" to "woodshop"), context()).text(), "Unknown area")
    assertContains(tool.run(args(), context()).text(), "area is required")
    val badTime = buildJsonObject { put("area", "auto"); put("from", "Thursday"); put("to", "Thursday") }
    assertContains(tool.run(badTime, context()).text(), "from must look like")
  }

  @Test
  fun `get_badge_swipes shows granted and denied swipes in time order`() = blocking {
    val swipes = FakeSwipes(listOf(swipe("19:00", "asmith", granted = false), swipe("17:05", "jdoe")))
    val tool = BadgeSwipesTool(activityAccess(chair), names("jdoe" to "Jane Doe"), swipes)

    val result = tool.run(args("door" to "garage-in", "deniedOnly" to "false"), context())

    assertContains(result.text(), "17:05 | Garage-In | granted | [Jane Doe](/profile/@jdoe)")
    assertContains(result.text(), "19:00 | Garage-In | denied | [asmith](/profile/@asmith)")
    assertEquals(listOf("Garage-In"), swipes.lastDoors?.toList())
  }

  @Test
  fun `get_badge_swipes passes the member and denied-only filters through`() = blocking {
    val swipes = FakeSwipes(listOf(swipe("17:05", "jdoe")))
    val tool = BadgeSwipesTool(activityAccess(infra), names(), swipes)
    val json = buildJsonObject { put("from", "2026-11-05"); put("to", "2026-11-05"); put("member", "jdoe"); put("deniedOnly", true) }

    tool.run(json, context())

    assertEquals(setOf("jdoe"), swipes.lastUsernames)
    assertEquals(true, swipes.lastDeniedOnly)
  }

  @Test
  fun `get_badge_swipes refuses a door outside the asker's areas and a bad member name`() = blocking {
    val tool = BadgeSwipesTool(activityAccess(chair), names(), FakeSwipes())

    assertContains(tool.run(args("door" to "Front W Entr-In"), context()).text(), "Unknown door")
    assertContains(tool.run(args("member" to "jdoe'; DROP"), context()).text(), "member must be a username")
  }

  @Test
  fun `get_interlock_sessions describes how each session ended`() = blocking {
    val sessions =
        FakeSessions(
            listOf(
                session("17:12", "18:40", "jdoe", endedBy = IDLE_TIMEOUT),
                session("19:00", "19:10", "asmith", endedBy = "MANUAL_BADGE_OUT"),
                session("20:00", null, "asmith", endedBy = null, authorized = false)))
    val tool = InterlockSessionsTool(activityAccess(chair), names("jdoe" to "Jane Doe"), sessions)

    val result = tool.run(args("tool" to "Auto Lift 1"), context())

    assertContains(result.text(), "3 sessions on Auto Lift 1")
    assertContains(result.text(), "17:12-18:40 (1h 28m) | Auto Lift 1 | [Jane Doe](/profile/@jdoe) | ended by idle timeout")
    assertContains(result.text(), "19:00-19:10 (10m) | Auto Lift 1 | [asmith](/profile/@asmith) | badged out")
    assertContains(result.text(), "20:00 | Auto Lift 1 | [asmith](/profile/@asmith) | denied start")
  }

  @Test
  fun `get_interlock_sessions filters by member and refuses tools outside the asker's areas`() = blocking {
    val sessions = FakeSessions(listOf(session("17:12", "18:40", "jdoe"), session("19:00", "19:10", "asmith")))
    val tool = InterlockSessionsTool(activityAccess(chair), names(), sessions)

    val mine = tool.run(args("member" to "ASmith"), context())

    assertContains(mine.text(), "1 session on Auto Lift 1")
    assertFalse(mine.text().contains("jdoe"))
    assertContains(tool.run(args("tool" to "Blacksmith Forge 1"), context()).text(), "Unknown tool")
  }

  @Test
  fun `every tool marks the chat private and shares one prompt section`() {
    val all =
        listOf(
            presence(infra),
            BadgeSwipesTool(activityAccess(infra), names(), FakeSwipes()),
            InterlockSessionsTool(activityAccess(infra), names(), FakeSessions()))

    all.forEach { assertTrue(it.touchesPersonalInfo) }
    assertEquals(1, all.map { it.promptSection }.distinct().size)
    assertContains(all.first().promptSection!!, "not proof that they caused anything")
    assertEquals(
        setOf("get_area_presence", "get_badge_swipes", "get_interlock_sessions"), all.map { it.descriptor.name }.toSet())
  }
}
