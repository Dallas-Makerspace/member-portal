package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InterlockServiceTest {
  private val client: InterlockClient = mock()
  private val directory: IActiveDirectoryService = mock()
  private val members: DashboardMembers = mock()
  private val service = InterlockService(client, directory, members, LoggerFactory())

  private fun data(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private fun user(name: String, enabled: Boolean = true) =
      ADUser(
          sAMAccountName = name,
          mail = "$name@example.test",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = name,
          objectGuid = "guid-$name",
          whenCreated = null,
          enabled = enabled,
          groups = emptyList())

  private suspend fun tools(json: String) {
    whenever(client.isConfigured).thenReturn(true)
    whenever(client.query(argThat { startsWith("{ equipment(") })).thenReturn(data(json))
  }

  @Test
  fun `a tool system that is not set up reports itself unavailable`() = runBlocking {
    whenever(client.isConfigured).thenReturn(false)
    assertEquals(ToolList(false, emptyList()), service.list(ToolMode.IN_USE))
  }

  @Test
  fun `a tool system that cannot be reached reports itself unavailable`() = runBlocking {
    whenever(client.isConfigured).thenReturn(true)
    whenever(client.query(any())).thenAnswer { throw InterlockException("down") }
    assertFalse(service.list(ToolMode.IN_USE).available)
  }

  @Test
  fun `maintenance mode lists only tools under maintenance`() = runBlocking {
    tools(
        """{"equipment":[
          {"id":"a1","name":" Planer ","isRelayOn":false,"isUnderMaintenance":true,"currentOperatorName":null},
          {"id":"b2","name":"Jointer","isRelayOn":true,"isUnderMaintenance":false,"currentOperatorName":"Pat Doe"},
          {"id":"c3","name":"Lathe","isRelayOn":false,"isUnderMaintenance":false,"currentOperatorName":null}]}""")
    val list = service.list(ToolMode.MAINTENANCE)
    assertTrue(list.available)
    assertEquals(listOf("Planer"), list.rows.map { it.name })
  }

  @Test
  fun `an in use tool is matched to a member through the operator's badge`() = runBlocking {
    tools(
        """{"equipment":[
          {"id":"b2","name":"Jointer","isRelayOn":true,"isUnderMaintenance":false,"currentOperatorName":"Pat Doe"},
          {"id":"c3","name":"Lathe","isRelayOn":false,"isUnderMaintenance":false,"currentOperatorName":null}]}""")
    whenever(client.query(argThat { startsWith("{ recentActivity(equipmentId: \"b2\"") }))
        .thenReturn(
            data(
                """{"recentActivity":[
                  {"eventDateTime":"2026-11-18T10:00:00Z","operatorId":"op9","operatorName":"Other Person","activityKind":"USER_ACTIVITY"},
                  {"eventDateTime":"2026-11-18T09:00:00Z","operatorId":"op1","operatorName":"Pat Doe","activityKind":"USER_ACTIVITY"},
                  {"eventDateTime":"2026-11-18T08:00:00Z","operatorId":"op2","operatorName":"Pat Doe","activityKind":"USER_ACTIVITY"}]}"""))
    whenever(client.query(argThat { startsWith("{ operators(id: \"op1\")") }))
        .thenReturn(data("""{"operators":[{"rfid":"0005978828"}]}"""))
    whenever(directory.getMembersByBadgeNumberList(listOf("0005978828", "5978828")))
        .thenReturn(mapOf("0005978828" to null, "5978828" to user("pdoe")))
    val row = MemberRow("pdoe", "Pat Doe")
    whenever(members.rows(listOf("pdoe"))).thenReturn(listOf(row))

    val list = service.list(ToolMode.IN_USE)
    assertEquals(listOf("Jointer"), list.rows.map { it.name })
    assertEquals(row, list.rows.single().operator)
    assertEquals(setOf("pdoe"), service.operatorUsernames())
  }

  @Test
  fun `an operator with no matching member is still shown by name`() = runBlocking {
    tools(
        """{"equipment":[
          {"id":"b2","name":"Jointer","isRelayOn":true,"isUnderMaintenance":false,"currentOperatorName":"Pat Doe"}]}""")
    whenever(client.query(argThat { startsWith("{ recentActivity(") }))
        .thenReturn(
            data(
                """{"recentActivity":[{"eventDateTime":"2026-11-18T09:00:00Z","operatorId":"op1","operatorName":"Pat Doe","activityKind":"USER_ACTIVITY"}]}"""))
    whenever(client.query(argThat { startsWith("{ operators(") }))
        .thenReturn(data("""{"operators":[{"rfid":"0000000"}]}"""))

    val row = service.list(ToolMode.IN_USE).rows.single()
    assertNull(row.operator)
    assertEquals("Pat Doe", row.operatorName)
  }

  @Test
  fun `badges are tried as given and without padding`() {
    assertEquals(listOf("0005978828", "5978828"), badgeForms("0005978828"))
    assertEquals(listOf("1234567"), badgeForms("1234567"))
    assertEquals(emptyList(), badgeForms("0000000"))
    assertEquals(emptyList(), badgeForms(" "))
  }
}
