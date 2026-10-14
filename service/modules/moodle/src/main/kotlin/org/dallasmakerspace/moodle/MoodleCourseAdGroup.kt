package org.dallasmakerspace.moodle

private const val AD_GROUP_FIELD = "ad_group"

/** The AD group the course grants on completion (custom field `ad_group`), or null if unset. */
fun MoodleCourse.adGroupName(): String? =
    customfields
        .firstOrNull { it.shortname == AD_GROUP_FIELD }
        ?.let { MoodleText.plainText(it.valueraw ?: it.value) }
        ?.takeIf { it.isNotEmpty() }
