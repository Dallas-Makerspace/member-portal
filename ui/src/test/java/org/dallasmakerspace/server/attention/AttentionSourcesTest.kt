package org.dallasmakerspace.server.attention

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.NotificationsInboxHandler
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AttentionSourcesTest {

  @Test
  fun `missing contact preference flags settings`() = runBlocking {
    val memberService = mock<MemberService>()
    whenever(memberService.getMemberSettings("jdoe", "s1")).thenReturn(emptyMap())

    val item = ContactPreferenceAttentionSource(memberService).check("jdoe", "s1")

    assertEquals("settings", item!!.key)
    assertEquals("/profile/@jdoe/settings#contact-preference", item.url)
  }

  @Test
  fun `set contact preference flags nothing`() = runBlocking {
    val memberService = mock<MemberService>()
    whenever(memberService.getMemberSettings("jdoe", "s1"))
        .thenReturn(mapOf("communication_preference" to "email"))

    assertNull(ContactPreferenceAttentionSource(memberService).check("jdoe", "s1"))
  }

  @Test
  fun `unread notifications flag the notifications item with a count`() = runBlocking {
    val client = mock<MemberServiceClient>()
    whenever(client.isFeatureEnabled(NotificationsInboxHandler.FEATURE_FLAG_KEY, "jdoe", "s1"))
        .thenReturn(true)
    whenever(client.getUnreadNotificationCount("jdoe", "s1")).thenReturn(3)

    val item = UnreadNotificationsAttentionSource(client).check("jdoe", "s1")

    assertEquals("notifications", item!!.key)
    assertEquals(3, item.count)
    assertEquals("3 unread", item.message)
  }

  @Test
  fun `no unread notifications flags nothing`() = runBlocking {
    val client = mock<MemberServiceClient>()
    whenever(client.isFeatureEnabled(NotificationsInboxHandler.FEATURE_FLAG_KEY, "jdoe", "s1"))
        .thenReturn(true)
    whenever(client.getUnreadNotificationCount("jdoe", "s1")).thenReturn(0)

    assertNull(UnreadNotificationsAttentionSource(client).check("jdoe", "s1"))
  }

  @Test
  fun `notifications flag off skips the unread lookup`() = runBlocking {
    val client = mock<MemberServiceClient>()
    whenever(client.isFeatureEnabled(NotificationsInboxHandler.FEATURE_FLAG_KEY, "jdoe", "s1"))
        .thenReturn(false)

    assertNull(UnreadNotificationsAttentionSource(client).check("jdoe", "s1"))
    verify(client, never()).getUnreadNotificationCount(any(), any())
    Unit
  }
}
