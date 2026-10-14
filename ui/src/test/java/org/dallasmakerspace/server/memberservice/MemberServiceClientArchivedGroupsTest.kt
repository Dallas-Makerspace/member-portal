package org.dallasmakerspace.server.memberservice

import io.ktor.util.StringValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.ArchivedGroup
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MemberServiceClientArchivedGroupsTest {
  private val http = mock<DMSHttpClient>()
  private val config =
      mock<AppConfig>().also {
        whenever(it.requireStringProperty("app.member-service.url")).thenReturn("http://service.test")
        whenever(it.requireStringProperty("app.member-service.api-key")).thenReturn("test-key")
        whenever(it.requireStringProperty("app.member-service.api-client")).thenReturn("test-client")
      }
  private val client = MemberServiceClient(LoggerFactory(), config, http)

  private fun respond(data: Any?) = runBlocking<Unit> {
    whenever(http.getJson(any(), any(), any()))
        .thenReturn(if (data == null) emptyMap<String, Any>() else mapOf("data" to data))
  }

  private fun capture(): Pair<String, StringValues> = runBlocking {
    val url = argumentCaptor<String>()
    val headers = argumentCaptor<StringValues>()
    verify(http).getJson(url.capture(), headers.capture(), any())
    url.firstValue to headers.firstValue
  }

  @Test
  fun `prefixes become repeated url encoded query parameters`() = runBlocking<Unit> {
    respond(emptyList<Any>())

    client.getArchivedGroups(listOf("Machine Shop", "C&A", "WS"), "sess-1")

    val (url, headers) = capture()
    assertEquals(
        "http://service.test/groups/archived?prefix=Machine+Shop&prefix=C%26A&prefix=WS", url)
    assertEquals("test-key", headers["X-Api-Key"])
    assertEquals("test-client", headers["X-Api-Client"])
    assertEquals("sess-1", headers["X-Request-Id"])
  }

  @Test
  fun `no prefixes sends no query string and no request id without a session`() = runBlocking<Unit> {
    respond(emptyList<Any>())

    client.getArchivedGroups(emptyList(), null)

    val (url, headers) = capture()
    assertEquals("http://service.test/groups/archived", url)
    assertNull(headers["X-Request-Id"])
  }

  @Test
  fun `rows are mapped including optional artifact fields`() = runBlocking<Unit> {
    respond(
        listOf(
            mapOf(
                "id" to 7,
                "name" to "Example Group",
                "archivedAt" to "2026-03-05T03:30:00Z",
                "archivedBy" to "jdoe",
                "reason" to "Reason text",
                "runId" to 42,
                "artifactStepIndex" to 1,
                "artifactName" to "members.json"),
            mapOf(
                "id" to 8,
                "name" to "Other Group",
                "archivedAt" to "2026-03-04T00:00:00Z",
                "archivedBy" to "asmith",
                "reason" to "Another reason")))

    val groups = client.getArchivedGroups(emptyList(), "sess")

    assertEquals(
        listOf(
            ArchivedGroup(
                7, "Example Group", "2026-03-05T03:30:00Z", "jdoe", "Reason text", 42L, 1, "members.json"),
            ArchivedGroup(8, "Other Group", "2026-03-04T00:00:00Z", "asmith", "Another reason")),
        groups)
  }

  @Test
  fun `a bare json array is accepted as well as the data envelope`() = runBlocking<Unit> {
    val row =
        mapOf(
            "id" to 7,
            "name" to "Example Group",
            "archivedAt" to "2026-03-05T03:30:00Z",
            "archivedBy" to "jdoe",
            "reason" to "Reason text")
    val expected = ArchivedGroup(7, "Example Group", "2026-03-05T03:30:00Z", "jdoe", "Reason text")

    whenever(http.getJson(any(), any(), any())).thenReturn(listOf(row))
    assertEquals(listOf(expected), client.getArchivedGroups(emptyList(), "sess"))

    whenever(http.getJson(any(), any(), any())).thenReturn(mapOf("data" to listOf(row)))
    assertEquals(listOf(expected), client.getArchivedGroups(emptyList(), "sess"))

    whenever(http.getJson(any(), any(), any())).thenReturn(emptyList<Any>())
    assertEquals(emptyList(), client.getArchivedGroups(emptyList(), "sess"))
  }

  @Test
  fun `a missing data attribute or a transport error is a MemberServiceException`() = runBlocking<Unit> {
    respond(null)
    assertFailsWith<MemberServiceException> { client.getArchivedGroups(emptyList(), "sess") }

    whenever(http.getJson(any(), any(), any())).thenAnswer { throw RuntimeException("down") }
    assertFailsWith<MemberServiceException> { client.getArchivedGroups(listOf("WS"), "sess") }
  }
}
