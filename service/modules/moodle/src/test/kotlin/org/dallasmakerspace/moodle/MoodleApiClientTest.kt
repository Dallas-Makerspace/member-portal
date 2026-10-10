package org.dallasmakerspace.moodle

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.builtins.ListSerializer
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MoodleApiClientTest {

  private val client =
      MoodleApiClient(
          mock<AppConfig>(),
          mock<LoggerFactory>().also { whenever(it.create(any())).thenReturn(mock<Logger>()) },
      )

  private val users = ListSerializer(MoodleUser.serializer())

  @Test
  fun `decodes a normal response, ignoring unknown fields`() {
    val result =
        client.decode("fn", """[{"id": 7, "username": "jdoe", "firstname": "J"}]""", users)
    assertEquals(listOf(MoodleUser(7, "jdoe")), result)
  }

  @Test
  fun `Moodle exception objects returned with HTTP 200 throw`() {
    val body =
        """{"exception":"webservice_access_exception","errorcode":"accessexception",""" +
            """"message":"Access control exception"}"""
    val e = assertFailsWith<MoodleApiException> { client.decode("fn", body, users) }
    assertEquals("Moodle fn failed: accessexception", e.message)
  }

  @Test
  fun `HTML bodies such as proxy challenge pages throw`() {
    assertFailsWith<MoodleApiException> {
      client.decode("fn", "<!DOCTYPE html><html><body>Just a moment...</body></html>", users)
    }
  }

  @Test
  fun `unexpected JSON shapes throw`() {
    assertFailsWith<MoodleApiException> { client.decode("fn", """{"id": 7}""", users) }
  }
}
