package org.dallasmakerspace.config

import kotlinx.serialization.Serializable

/**
 * Value for a [ConfigValueType.FeatureFlagType] config key: a global on/off switch plus optional
 * per-username and per-group allowlists. The allowlists are checked even when [enabled] is false,
 * so a feature can be piloted with specific members/groups before a full rollout, then flipped on
 * for everyone by setting [enabled] to true.
 */
@Serializable
data class FeatureFlagValue(
    val enabled: Boolean = false,
    /** Usernames the feature is enabled for regardless of [enabled]. */
    val enabledUsernames: Set<String> = emptySet(),
    /** AD group CNs the feature is enabled for regardless of [enabled]. */
    val enabledGroupNames: Set<String> = emptySet(),
)
