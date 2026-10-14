package org.dallasmakerspace.workflows.groups.references

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.calendar.PrerequisiteReferences
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.groups.Constants
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.ProtectedGroups
import org.dallasmakerspace.moodle.IMoodleApiClient
import org.dallasmakerspace.moodle.MoodleCourse
import org.dallasmakerspace.moodle.MoodleCustomField
import org.dallasmakerspace.remoteaccess.RemoteAccessCategory
import org.dallasmakerspace.voterregistration.VoterRegistrationManager
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ProtectedGroupReferenceFinderTest {
  private val finder = ProtectedGroupReferenceFinder()

  @Test
  fun `role, chair, teacher, voting and editors groups are protected`() = runBlocking {
    val committee = Committees.ALL.first { it.teacherGroups.isNotEmpty() }
    val names =
        listOf(
            "Infrastructure",
            "Domain Admins",
            committee.chairGroupName!!,
            committee.teacherGroups.first(),
            "${committee.name} Online Course Editors",
            "Voting Members")
    names.forEach { name ->
      val hit = finder.find(name).single()
      assertEquals(GroupReferenceKinds.PROTECTED, hit.kind, name)
      assertEquals(1, hit.count)
    }
  }

  @Test
  fun `case and surrounding spaces do not matter`() = runBlocking {
    assertEquals(1, finder.find("  infrastructure ").size)
    assertEquals(1, finder.find("DOMAIN ADMINS").size)
  }

  @Test
  fun `an ordinary group and a committee prefix alone are not protected`() = runBlocking {
    assertTrue(finder.find("Example Basics").isEmpty())
    assertTrue(finder.find(Committees.ALL.first { it.groupPrefixes.isNotEmpty() }.groupPrefixes[0]).isEmpty())
  }

  @Test
  fun `the shared list covers the group names the service hard-codes`() {
    listOf(
            Constants.INFRASTRUCTURE,
            VoterRegistrationManager.VOTING_MEMBERS_GROUP,
            VoterRegistrationManager.VOTING_MEMBERS_GROUP_TEST)
        .forEach { assertTrue(ProtectedGroups.isProtected(it), it) }
  }
}

class CalendarReferenceFinderTest {
  private fun finder(result: PrerequisiteReferences) =
      CalendarReferenceFinder(
          mock<CalendarService> {
            onBlocking { countPrerequisiteReferences(any()) } doReturn result
          })

  @Test
  fun `no prerequisite means no reference`() = runBlocking {
    assertTrue(finder(PrerequisiteReferences(0, 0, 0)).find("Example Group").isEmpty())
  }

  @Test
  fun `a prerequisite with only past events still blocks and says so`() = runBlocking {
    val hit = finder(PrerequisiteReferences(1, 0, 4)).find("Example Group").single()
    assertEquals(GroupReferenceKinds.PREREQUISITE, hit.kind)
    assertEquals(1, hit.count)
    assertTrue(hit.detail.contains("0 upcoming and 4 past"), hit.detail)
  }

  @Test
  fun `upcoming events are reported`() = runBlocking {
    val hit = finder(PrerequisiteReferences(2, 3, 1)).find("Example Group").single()
    assertEquals(2, hit.count)
    assertTrue(hit.detail.contains("2 prerequisites"), hit.detail)
    assertTrue(hit.detail.contains("3 upcoming and 1 past"), hit.detail)
  }

  @Test
  fun `a failed lookup propagates instead of reading as no references`() {
    val calendar =
        mock<CalendarService> {
          onBlocking { countPrerequisiteReferences(any()) } doThrow IllegalStateException("db down")
        }
    assertFailsWith<IllegalStateException> {
      runBlocking { CalendarReferenceFinder(calendar).find("Example Group") }
    }
  }

  @Test
  fun `the name is passed through untouched for the repository to escape`() = runBlocking<Unit> {
    val calendar =
        mock<CalendarService> {
          onBlocking { countPrerequisiteReferences(any()) } doReturn PrerequisiteReferences(0, 0, 0)
        }
    CalendarReferenceFinder(calendar).find("O'Example Group")
    verify(calendar).countPrerequisiteReferences("O'Example Group")
  }
}

class MoodleReferenceFinderTest {
  private fun course(id: Int, field: String?, value: String?) =
      MoodleCourse(
          id = id,
          shortname = "EX-$id",
          customfields = if (field == null) emptyList() else listOf(MoodleCustomField(field, value, value)))

  private fun finder(vararg courses: MoodleCourse) =
      MoodleReferenceFinder(
          mock<IMoodleApiClient> { onBlocking { getCourses() } doReturn courses.toList() })

  @Test
  fun `courses naming the group are counted with their short names`() = runBlocking {
    val hit =
        finder(
                course(1, "ad_group", "Example Basics"),
                course(2, "ad_group", "Other Group"),
                course(3, "ad_group", "Example Basics"),
                course(4, null, null))
            .find("Example Basics")
            .single()
    assertEquals(GroupReferenceKinds.COURSE, hit.kind)
    assertEquals(2, hit.count)
    assertTrue(hit.detail.contains("EX-1, EX-3"), hit.detail)
  }

  @Test
  fun `case, spaces and wrapping html in the field value are ignored`() = runBlocking {
    val hit = finder(course(1, "ad_group", "<p> EXAMPLE basics </p>")).find("example Basics")
    assertEquals(1, hit.single().count)
  }

  @Test
  fun `other custom fields and unrelated groups are misses`() = runBlocking {
    assertTrue(finder(course(1, "something_else", "Example Basics")).find("Example Basics").isEmpty())
    assertTrue(finder(course(1, "ad_group", "Example Basics Plus")).find("Example Basics").isEmpty())
  }

  @Test
  fun `an unreachable Moodle propagates`() {
    val client = mock<IMoodleApiClient> { onBlocking { getCourses() } doThrow IllegalStateException("down") }
    assertFailsWith<IllegalStateException> { runBlocking { MoodleReferenceFinder(client).find("Example") } }
  }

  @Test
  fun `it says chair-group mappings cannot be read`() {
    assertTrue(MoodleReferenceFinder(mock()).notes.single().contains("cannot be read"))
  }
}

class ConfigReferenceFinderTest {
  private fun flag(key: String) =
      ConfigKey(
          key = key,
          description = "d",
          category = "c",
          defaultValue = FeatureFlagValue(),
          type = ConfigValueType.FeatureFlagType)

  private fun text(key: String) =
      ConfigKey(
          key = key,
          description = "d",
          category = "c",
          defaultValue = "",
          type = ConfigValueType.StringType)

  private val flagA = flag("test.flag-a")
  private val flagB = flag("test.flag-b")
  private val textA = text("test.text-a")
  private val textB = text("test.text-b")
  private val flagBoolean =
      ConfigKey("test.bool", "d", "c", false, ConfigValueType.BooleanType)

  private fun finder(values: Map<String, Any?>, keys: List<ConfigKey<*>>) =
      ConfigReferenceFinder({ key -> values[key.key] ?: error("no value for ${key.key}") }, keys)

  @Test
  fun `flags listing the group and strings equal to it are reported separately`() = runBlocking {
    val values =
        mapOf(
            "test.flag-a" to FeatureFlagValue(enabledGroupNames = setOf("Other", "example group")),
            "test.flag-b" to FeatureFlagValue(enabledGroupNames = setOf("Other")),
            "test.text-a" to "  EXAMPLE GROUP ",
            "test.text-b" to "something else",
            "test.bool" to true)
    val hits =
        finder(values, listOf(flagA, flagB, textA, textB, flagBoolean)).find("Example Group")
    assertEquals(
        listOf(GroupReferenceKinds.FEATURE_FLAG, GroupReferenceKinds.CONFIG_VALUE),
        hits.map { it.kind })
    assertTrue(hits[0].detail.contains("test.flag-a"))
    assertTrue(!hits[0].detail.contains("test.flag-b"))
    assertTrue(hits[1].detail.contains("test.text-a"))
  }

  @Test
  fun `nothing referenced is empty and blank values never match a blank name`() = runBlocking {
    val values = mapOf("test.text-a" to "", "test.flag-a" to FeatureFlagValue(enabledGroupNames = setOf("")))
    assertTrue(finder(values, listOf(textA, flagA)).find("Example Group").isEmpty())
    assertTrue(finder(values, listOf(textA, flagA)).find("").isEmpty())
  }

  @Test
  fun `remote-access group keys are left to the remote-access finder`() = runBlocking {
    val keys = RemoteAccessCategory.entries.map { it.adGroupConfigKey }
    val values = keys.associate { it.key to "Example Group" }
    assertTrue(finder(values, keys).find("Example Group").isEmpty())
  }

  @Test
  fun `a failing read propagates`() {
    val failing = ConfigReferenceFinder({ throw IOException("db down") }, listOf(textA))
    assertFailsWith<IOException> { runBlocking { failing.find("Example Group") } }
  }

  @Test
  fun `the real registry has no key that matches an ordinary name by default`() = runBlocking {
    val defaults = ConfigRegistry.ALL.associate { it.key to it.defaultValue }
    assertTrue(
        ConfigReferenceFinder({ key -> defaults[key.key] }, ConfigRegistry.ALL)
            .find("Example Group")
            .isEmpty())
  }
}

class RemoteAccessReferenceFinderTest {
  private fun finder(values: Map<String, String>) =
      RemoteAccessReferenceFinder(
          { key -> values[key.key] ?: "" }, RemoteAccessCategory.entries.toList())

  @Test
  fun `a category gated on the group is reported with its display name`() = runBlocking {
    val gated = RemoteAccessCategory.MASTERCAM
    val hit =
        finder(mapOf(gated.adGroupConfigKey.key to " example GROUP ")).find("Example Group").single()
    assertEquals(GroupReferenceKinds.REMOTE_ACCESS, hit.kind)
    assertEquals(1, hit.count)
    assertTrue(hit.detail.contains(gated.displayName))
  }

  @Test
  fun `several categories on the same group are counted once each`() = runBlocking {
    val values = RemoteAccessCategory.entries.associate { it.adGroupConfigKey.key to "Example Group" }
    assertEquals(RemoteAccessCategory.entries.size, finder(values).find("Example Group").single().count)
  }

  @Test
  fun `an ungated or differently gated category is a miss`() = runBlocking {
    assertTrue(finder(emptyMap()).find("Example Group").isEmpty())
    assertTrue(
        finder(mapOf(RemoteAccessCategory.JUMP_SERVER.adGroupConfigKey.key to "Another"))
            .find("Example Group")
            .isEmpty())
  }
}
