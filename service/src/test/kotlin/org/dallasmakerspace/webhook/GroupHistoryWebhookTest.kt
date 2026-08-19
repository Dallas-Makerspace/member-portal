package org.dallasmakerspace.webhook

import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.webhook.handlers.GroupHistoryWebhook
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GroupHistoryWebhookTest {

  private val mockAd = mock<ActiveDirectoryService>()
  private val mockGroupHistory = mock<GroupHistoryRepository>()
  private val mockMemberService = mock<MemberService>()
  private val mockMemberRepository = mock<IMemberRepository>()
  private val handler =
      GroupHistoryWebhook(mockAd, mockGroupHistory, mockMemberService, mockMemberRepository)

  private fun member(id: Int, username: String) = DMSMember(id = id, username = username)

  private fun adUser(username: String) =
      ADUser(
          sAMAccountName = username,
          mail = "$username@example.com",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = null,
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = emptyList())

  private fun requestBody(actorUsername: String, memberDn: String = "CN=muzlotia1,OU=Members,DC=dms,DC=local") =
      """
      {
        "TimeCreated": "2026-08-17T14:35:30.123Z",
        "EventId": "4728",
        "Subject": { "Domain": "DMS", "UserName": "$actorUsername" },
        "TargetGroup": { "Name": "Woodshop Bandsaw Laguna", "Domain": "DMS" },
        "Member": { "Name": "$memberDn" },
        "RecordId": "12345",
        "Computer": "dc1.dms.local"
      }
      """
          .trimIndent()

  private suspend fun stubMemberAndGroupResolution(memberUsername: String) {
    whenever(mockAd.getMembersByDnList(any())).thenReturn(listOf(adUser(memberUsername)))
    whenever(mockMemberService.getMemberByUsername(memberUsername))
        .thenReturn(member(1, memberUsername))
    whenever(mockGroupHistory.getOrCreateGroup("Woodshop Bandsaw Laguna")).thenReturn(99)
    whenever(mockGroupHistory.hasPortalInitiatedRecord(any(), any(), any(), any(), any()))
        .thenReturn(false)
    whenever(mockGroupHistory.insertGroupHistory(any(), any(), any(), any(), any(), any()))
        .thenReturn(mock())
  }

  @Test
  fun `handleWebhook records the target member when the actor resolves directly`() = runBlocking {
    stubMemberAndGroupResolution("muzlotia1")
    whenever(mockMemberService.getMemberByUsername("jphelps")).thenReturn(member(2, "jphelps"))

    val result = handler.handleWebhook(requestBody(actorUsername = "jphelps"))

    assertTrue(result.success)
    verify(mockGroupHistory)
        .insertGroupHistory(eq(2), eq(1), eq(99), any(), any(), any())
    verify(mockMemberRepository, never()).getMemberOrInsert(any(), any())
    Unit
  }

  @Test
  fun `handleWebhook falls back to the stripped username for a privileged adm_ actor`() =
      runBlocking {
        stubMemberAndGroupResolution("muzlotia1")
        whenever(mockMemberService.getMemberByUsername("adm_jphelps"))
            .thenThrow(RuntimeException("User adm_jphelps not found in AD"))
        whenever(mockMemberService.getMemberByUsername("jphelps")).thenReturn(member(2, "jphelps"))

        val result = handler.handleWebhook(requestBody(actorUsername = "adm_jphelps"))

        assertTrue(result.success)
        verify(mockGroupHistory)
            .insertGroupHistory(eq(2), eq(1), eq(99), any(), any(), any())
        verify(mockMemberRepository, never()).getMemberOrInsert(any(), any())
        Unit
      }

  @Test
  fun `handleWebhook falls back to the system actor when the stripped username also fails`() =
      runBlocking {
        stubMemberAndGroupResolution("muzlotia1")
        whenever(mockMemberService.getMemberByUsername("adm_ozindfw"))
            .thenThrow(RuntimeException("User adm_ozindfw not found in AD"))
        whenever(mockMemberService.getMemberByUsername("ozindfw"))
            .thenThrow(RuntimeException("User ozindfw not found in AD"))
        whenever(mockMemberRepository.getMemberOrInsert(eq("ad-system"), eq(false)))
            .thenReturn(member(3, "ad-system"))

        val result = handler.handleWebhook(requestBody(actorUsername = "adm_ozindfw"))

        assertTrue(result.success)
        verify(mockGroupHistory)
            .insertGroupHistory(eq(3), eq(1), eq(99), any(), any(), any())
        Unit
      }

  @Test
  fun `handleWebhook falls back to the system actor directly for an unprefixed unresolvable actor`() =
      runBlocking {
        stubMemberAndGroupResolution("muzlotia1")
        whenever(mockMemberService.getMemberByUsername("DC1$"))
            .thenThrow(RuntimeException("User DC1$ not found in AD"))
        whenever(mockMemberRepository.getMemberOrInsert(eq("ad-system"), eq(false)))
            .thenReturn(member(3, "ad-system"))

        val result = handler.handleWebhook(requestBody(actorUsername = "DC1$"))

        assertTrue(result.success)
        verify(mockGroupHistory)
            .insertGroupHistory(eq(3), eq(1), eq(99), any(), any(), any())
        Unit
      }
}
