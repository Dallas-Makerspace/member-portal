package org.dallasmakerspace.models

import java.time.ZoneOffset
import org.dallasmakerspace.members.db.ProfileDAO

/** Converter function to create a DMSMember from a ProfileDAO. */
fun daoToProfileModel(dao: ProfileDAO) =
    DMSMember(
        id = dao.idColumn.value,
        username = dao.id.value,
        enabled = dao.isEnabled,
        discourseUsername = dao.discourseUsername,
        discourseAvatarUrl = dao.discourseAvatarUrl,
        discordUserId = dao.discordUserId,
        discordUsername = dao.discordUsername,
        discordAvatarUrl = dao.discordAvatarUrl,
        discordWebhookId = dao.discordWebhookId,
        linkedinUsername = dao.linkedinUsername,
        profilePhotoUrl = profilePhotoUrl(dao))

private fun profilePhotoUrl(dao: ProfileDAO): String? {
  if (dao.profilePhotoKey == null) return null
  val version = dao.profilePhotoUpdatedAt?.toInstant(ZoneOffset.UTC)?.epochSecond ?: 0L
  return "/avatar/@${dao.id.value}?v=$version"
}
