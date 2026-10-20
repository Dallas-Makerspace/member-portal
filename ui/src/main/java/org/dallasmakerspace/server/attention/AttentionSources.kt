package org.dallasmakerspace.server.attention

import javax.inject.Inject
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.SettingsSections
import org.dallasmakerspace.server.routes.NotificationsInboxHandler

/** Member hasn't told others how to reach them. */
class ContactPreferenceAttentionSource
@Inject
constructor(private val memberService: MemberService) : AttentionSource {
  override suspend fun check(username: String, sessionId: String?): AttentionItem? {
    val settings = memberService.getMemberSettings(username, sessionId)
    if (!settings["communication_preference"].isNullOrBlank()) return null
    return AttentionItem(
        key = "settings",
        message = "Set your contact preference",
        url = SettingsSections.url(username, SettingsSections.CONTACT_PREFERENCE))
  }
}

/** Delivered notifications the member hasn't opened the inbox to see. */
class UnreadNotificationsAttentionSource
@Inject
constructor(private val memberServiceClient: MemberServiceClient) : AttentionSource {
  override suspend fun check(username: String, sessionId: String?): AttentionItem? {
    if (!memberServiceClient.isFeatureEnabled(
        NotificationsInboxHandler.FEATURE_FLAG_KEY, username, sessionId)) {
      return null
    }
    val count = memberServiceClient.getUnreadNotificationCount(username, sessionId)
    if (count <= 0) return null
    return AttentionItem(
        key = "notifications",
        message = "$count unread",
        url = "/profile/@$username/notifications",
        count = count)
  }
}
