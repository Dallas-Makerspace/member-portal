package org.dallasmakerspace.models

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
        profilePhotoUrl = dao.profilePhotoKey?.let { profilePhotoUrl(dao.id.value, it) })

private const val HEX_RADIX = 16

/** Versioned by the storage key (new per upload), so the immutable-cached URL changes every time. */
fun profilePhotoUrl(username: String, key: String): String =
    "/avatar/@$username?v=${key.hashCode().toUInt().toString(HEX_RADIX)}"
