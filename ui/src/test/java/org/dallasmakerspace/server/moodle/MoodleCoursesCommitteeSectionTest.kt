package org.dallasmakerspace.server.moodle

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.MemberServiceException
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MoodleCoursesCommitteeSectionTest {

  private val client = mock<MemberServiceClient>()
  private val loader = MoodleCommitteeCoursesLoader(client)
  private val laser = Committees.findBySlug("laser")!!

  @Before
  fun noEditorsByDefault() = runBlocking<Unit> {
    whenever(client.getMultipleGroups(any(), anyOrNull())).thenReturn(emptyList())
  }

  private fun catalog(count: Int) =
      mapOf(
          "learnUrl" to "https://learn.example.org",
          "courses" to
              (1..count).map {
                mapOf(
                    "id" to it.toDouble(),
                    "name" to "Example $it",
                    "url" to "https://learn.example.org/course/view.php?id=$it",
                    "committeeSlug" to "laser",
                    "selfEnrol" to (it != 5),
                )
              },
      )

  @Test
  fun `section is a placeholder that makes no backend calls`() = runBlocking<Unit> {
    val section = MoodleCoursesCommitteeSection()

    val rendered = assertNotNull(section.renderSection(laser, "sid", "jdoe"))

    assertEquals("fragments/moodle-courses-placeholder", rendered.fragmentName)
    assertEquals("laser", rendered.model["committeeSlug"])
    assertNull(section.renderSection(laser, "sid", null))
  }

  @Test
  fun `loader returns nothing when the flag is off`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(eq(MOODLE_FEATURE_FLAG), eq("jdoe"), anyOrNull())).thenReturn(false)

    assertNull(loader.load(laser, "sid", "jdoe"))
    verify(client, never()).getMoodleCatalog(anyOrNull(), anyOrNull())
  }

  @Test
  fun `loader returns nothing for committees without courses`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
    whenever(client.getMoodleCatalog(anyOrNull(), eq("laser"))).thenReturn(catalog(0))
    whenever(client.getMoodleMemberCourses(anyOrNull(), eq("jdoe"))).thenReturn(emptyList())

    assertNull(loader.load(laser, "sid", "jdoe"))
  }

  @Test
  fun `loader shows the first three courses and collapses the rest`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
    whenever(client.getMoodleCatalog(anyOrNull(), eq("laser"))).thenReturn(catalog(5))
    whenever(client.getMoodleMemberCourses(anyOrNull(), eq("jdoe"))).thenReturn(emptyList())

    val model = assertNotNull(loader.load(laser, "sid", "jdoe"))

    assertEquals(3, (model["courses"] as List<*>).size)
    assertEquals(1, (model["moreCourses"] as List<*>).size) // course 5 isn't self-enrollable
    assertEquals(4, model["totalCount"])
    assertEquals("/reports/online-courses/overview?committee=laser", model["statsUrl"])
  }

  private fun withCourses() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
    whenever(client.getMoodleCatalog(anyOrNull(), eq("laser"))).thenReturn(catalog(2))
    whenever(client.getMoodleMemberCourses(anyOrNull(), eq("jdoe"))).thenReturn(emptyList())
  }

  private fun editorsGroup() =
      DMSGroup(
          name = "Laser Online Course Editors",
          distinguishedName = "CN=Laser Online Course Editors,OU=Example,DC=example,DC=test",
          objectGuid = null,
          members =
              listOf(
                  DMSMember(id = 1, username = "asmith", displayName = "Alex Smith", enabled = true),
                  DMSMember(id = 2, username = "bdoe", displayName = "Bo Doe", enabled = false),
              ),
      )

  @Test
  fun `loader lists the enabled editors and flags chairs who can manage them`() = runBlocking<Unit> {
    withCourses()
    whenever(client.getMultipleGroups(any(), anyOrNull())).thenReturn(listOf(editorsGroup()))
    whenever(client.isGroupAdmin(eq("jdoe"), eq("Laser+Online+Course+Editors"), anyOrNull())).thenReturn(true)

    val model = assertNotNull(loader.load(laser, "sid", "jdoe"))

    val editors = model["editors"] as List<*>
    assertEquals(listOf("asmith"), editors.map { (it as Map<*, *>)["username"] })
    assertEquals("/groups/Laser+Online+Course+Editors", model["editorsGroupUrl"])
    assertEquals(true, model["canManageEditors"])
  }

  @Test
  fun `loader shows an empty editors group and lets group admins manage it`() = runBlocking<Unit> {
    withCourses()
    whenever(client.getMultipleGroups(any(), anyOrNull()))
        .thenReturn(listOf(editorsGroup().copy(members = emptyList())))

    val model = assertNotNull(loader.load(laser, "sid", "jdoe", canAdminGroups = true))

    assertEquals(emptyList<Any>(), model["editors"])
    assertEquals("/groups/Laser+Online+Course+Editors", model["editorsGroupUrl"])
    assertEquals(true, model["canManageEditors"])
  }

  @Test
  fun `loader omits editors when the group does not exist`() = runBlocking<Unit> {
    withCourses()
    whenever(client.getMultipleGroups(any(), anyOrNull())).thenReturn(emptyList())

    val model = assertNotNull(loader.load(laser, "sid", "jdoe"))

    assertEquals(emptyList<Any>(), model["editors"])
    assertEquals(false, model["canManageEditors"])
  }

  @Test
  fun `loader still renders the card when the editors lookup fails`() = runBlocking<Unit> {
    withCourses()
    whenever(client.getMultipleGroups(any(), anyOrNull())).thenAnswer { throw MemberServiceException("AD down") }

    val model = assertNotNull(loader.load(laser, "sid", "jdoe"))

    assertEquals(2, model["totalCount"])
    assertEquals(emptyList<Any>(), model["editors"])
  }
}
