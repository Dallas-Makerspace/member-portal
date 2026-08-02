package org.dallasmakerspace.members

/**
 * Describes a single known member setting. New settings are added to [MemberSettingsRegistry] by
 * declaring a key here — no schema or API shape changes required.
 *
 * @param key The unique storage key used in the member_settings table.
 * @param description A human-readable description of the setting.
 * @param deserialize Converts a stored string value into the typed representation.
 * @param validate Returns true if the raw string value is acceptable to store.
 */
data class MemberSettingsKey<T>(
    val key: String,
    val description: String,
    val deserialize: (String) -> T,
    val validate: (String) -> Boolean = { true },
)
