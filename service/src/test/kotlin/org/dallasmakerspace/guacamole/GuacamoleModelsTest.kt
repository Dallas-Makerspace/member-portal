package org.dallasmakerspace.guacamole

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.Test

class GuacamoleModelsTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `GuacamoleConnection parses maxConnections and maxConnectionsPerUser out of the attributes map`() {
    // Real shape returned by Guacamole's REST API (verified against a live instance): capacity
    // is nested under "attributes" as hyphenated string values, not top-level numeric fields.
    val raw =
        """{"identifier":"5","name":"Jump Server (PRWJUMP)","protocol":"rdp",
             "attributes":{"max-connections":"2","max-connections-per-user":"1","weight":null}}"""

    val connection = json.decodeFromString(GuacamoleConnection.serializer(), raw)

    assertEquals("5", connection.identifier)
    assertEquals(2, connection.maxConnections)
    assertEquals(1, connection.maxConnectionsPerUser)
  }

  @Test
  fun `GuacamoleConnection treats null attribute values and a missing attributes map as unlimited`() {
    val nullAttributeValue =
        """{"identifier":"1","name":"MasterCam Workstation (DMS-MS-HAAS-PC-1)","protocol":"rdp",
             "attributes":{"max-connections":null}}"""
    val missingAttributesMap =
        """{"identifier":"1","name":"MasterCam Workstation (DMS-MS-HAAS-PC-1)","protocol":"rdp"}"""

    val withNullAttribute =
        json.decodeFromString(GuacamoleConnection.serializer(), nullAttributeValue)
    val withoutAttributes =
        json.decodeFromString(GuacamoleConnection.serializer(), missingAttributesMap)

    assertNull(withNullAttribute.maxConnections)
    assertNull(withoutAttributes.maxConnections)
    assertNull(withoutAttributes.maxConnectionsPerUser)
  }
}
