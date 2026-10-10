package org.dallasmakerspace.config

import kotlinx.serialization.Serializable

@Serializable
data class ConfigOverrideEntry(
    val id: Long,
    val key: String,
    val value: String,
    val type: String,
    val changedBy: String,
    val changeReason: String,
    val changedAt: String,
    val isReset: Boolean,
    val infraOnly: Boolean,
)

@Serializable
data class ConfigCurrentValue(
    val key: String,
    val description: String,
    val category: String,
    val currentValue: String,
    val defaultValue: String,
    val type: String,
    val isOverridden: Boolean,
    val sensitive: Boolean,
    val overridable: Boolean,
    val lastChangedBy: String?,
    val lastChangedAt: String?,
    val lastChangeReason: String?,
    val infraOnly: Boolean,
)
