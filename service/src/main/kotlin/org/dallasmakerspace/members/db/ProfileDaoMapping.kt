package org.dallasmakerspace.members.db

import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.profilePhotoUrl

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
        profilePhotoUrl = dao.profilePhotoKey?.let { profilePhotoUrl(dao.id.value, it) })
