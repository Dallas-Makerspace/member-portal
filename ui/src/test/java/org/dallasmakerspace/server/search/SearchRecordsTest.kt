package org.dallasmakerspace.server.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.server.models.EventSummary
import org.dallasmakerspace.server.models.getSlugFromName
import org.dallasmakerspace.server.routes.ActionTrackingHandler
import org.dallasmakerspace.server.routes.SearchHandler

class SearchRecordsTest {
  private val committee =
      Committee(
          id = 99,
          name = "Test Shop",
          chairGroupName = "Test Shop Chair",
          description = "Makes test things",
          teacherGroups = listOf("Test Shop Teachers"),
          groupPrefixes = listOf("Test Shop", "TS"),
      )

  private fun chairGroup(vararg names: String?) =
      DMSGroup(
          name = "Test Shop Chair",
          distinguishedName = "CN=Test Shop Chair,OU=Groups,DC=example,DC=internal",
          objectGuid = null,
          members = names.mapIndexed { i, n -> DMSMember(id = i, username = "user$i", displayName = n) },
      )

  @Test
  fun `a committee record links to its page and lists chairs and related group names`() {
    val record =
        SearchRecords.committees(
                listOf(committee),
                mapOf(getSlugFromName("Test Shop Chair") to chairGroup("Jane Doe", null)))
            .single()

    assertEquals("committee", record["type"])
    assertEquals("Test Shop", record["displayName"])
    assertEquals("test-shop", record["username"])
    assertEquals("/committees/test-shop", record["url"])
    assertEquals(listOf("Jane Doe", "user1"), record["chairs"])
    assertEquals(listOf("Test Shop", "TS", "Test Shop Teachers", "Test Shop Chair"), record["keywords"])
  }

  @Test
  fun `a committee without a chair lookup still appears, with no chairs`() {
    val record = SearchRecords.committees(listOf(committee), emptyMap()).single()

    assertEquals(emptyList<String>(), record["chairs"])
    assertEquals("Makes test things", record["description"])
  }

  @Test
  fun `an event record is an external calendar link with a readable start time`() {
    val record =
        SearchRecords.event(
            EventSummary(
                id = 42,
                name = "Lathe Basics",
                eventStart = "2026-12-05 10:00:00.0",
                status = "approved",
                fulfillsPrerequisiteGroup = "Test Shop Lathe"))

    assertEquals("event", record["type"])
    assertEquals("42", record["id"])
    assertEquals("Sat, Dec 5 · 10:00 AM", record["subtitle"])
    assertEquals("${SearchRecords.CALENDAR_EVENT_URL}42", record["url"])
    assertEquals(true, record["external"])
    assertEquals(listOf("Training"), record["badges"])
  }

  @Test
  fun `short links open through go, show their description and site, and skip dynamic or inactive ones`() {
    val namespaces =
        listOf(
            mapOf(
                "id" to 7,
                "isActive" to true,
                "aliases" to
                    listOf(
                        mapOf("alias" to "ts", "isPrimary" to false),
                        mapOf("alias" to "testshop", "isPrimary" to true))))
    val links =
        listOf(
            mapOf("id" to 1, "slug" to "forum", "destinationUrl" to "https://www.forum.example.org/c/1",
                "description" to "Member forum", "isActive" to true, "redirectType" to "BASIC", "namespaceId" to null),
            mapOf("id" to 2, "slug" to "sign-up", "destinationUrl" to "https://forms.example.org/x",
                "description" to null, "isActive" to true, "redirectType" to "BASIC", "namespaceId" to 7),
            mapOf("id" to 3, "slug" to "ticket", "destinationUrl" to "https://tickets.example.org/{id}",
                "isActive" to true, "redirectType" to "DYNAMIC", "namespaceId" to null),
            mapOf("id" to 4, "slug" to "old", "destinationUrl" to "https://old.example.org",
                "isActive" to false, "redirectType" to "BASIC", "namespaceId" to null),
            mapOf("id" to 5, "slug" to "orphan", "destinationUrl" to "https://x.example.org",
                "isActive" to true, "redirectType" to "BASIC", "namespaceId" to 99),
        )

    val records = SearchRecords.links(links, namespaces)

    assertEquals(listOf("1", "2"), records.map { it["id"] })
    val forum = records[0]
    assertEquals("link", forum["type"])
    assertEquals("Member forum", forum["displayName"])
    assertEquals("/go/forum", forum["url"])
    assertEquals("forum.example.org", forum["site"])
    val signUp = records[1]
    assertEquals("Sign up", signUp["displayName"])
    assertEquals("/go/testshop/sign-up", signUp["url"])
    assertEquals(listOf("sign-up", "testshop", "forms.example.org"), signUp["keywords"])
  }

  @Test
  fun `an unparseable event start is shown as given`() {
    assertEquals("soon", SearchRecords.formatStart("soon"))
  }

  @Test
  fun `related terms are lowercased, deduplicated and single-term lines dropped`() {
    val groups = SearchRecords.relatedTerms("CNC, Router , cnc\n\n lonely \nPottery,Kiln")

    assertEquals(listOf(listOf("cnc", "router"), listOf("pottery", "kiln")), groups)
    assertEquals(emptyList(), SearchRecords.relatedTerms(null))
  }

  @Test
  fun `server search sends the query first, then distinct alternatives, capped`() {
    val alts = (1..30).map { "term$it" } + listOf("LATHE", "lathe", "x", "laht")

    val keywords = SearchHandler.keywords("  Laht ", alts)

    assertEquals("laht", keywords.first())
    assertEquals(1 + SearchHandler.MAX_ALT_TERMS, keywords.size)
    assertTrue(keywords.size <= 20)
    assertEquals(keywords.distinct(), keywords)
  }

  @Test
  fun `server search needs at least two characters and caps the query length`() {
    assertEquals(emptyList(), SearchHandler.keywords(" a ", listOf("lathe")))
    assertEquals(emptyList(), SearchHandler.keywords(null, emptyList()))
    assertEquals(SearchHandler.MAX_QUERY_LENGTH, SearchHandler.keywords("a".repeat(500), emptyList()).single().length)
  }

  @Test
  fun `tracking keeps only short scalar client fields`() {
    val payload =
        mapOf<String, Any?>(
            "actionType" to "search_result_click",
            "resultRank" to 2,
            "fromRecents" to false,
            "nested" to mapOf("a" to 1),
            "long" to "x".repeat(5000),
            "k".repeat(100) to "too long a key",
        ) + (1..40).associate { "extra$it" to it }

    val clean = ActionTrackingHandler.sanitize(payload)

    assertEquals("search_result_click", clean["actionType"])
    assertEquals(2, clean["resultRank"])
    assertFalse("nested" in clean)
    assertEquals(1024, (clean["long"] as String).length)
    assertFalse("k".repeat(100) in clean)
    assertTrue(clean.size <= 20)
  }
}
