package org.dallasmakerspace.config

data class ConfigKey<T>(
    val key: String,
    val description: String,
    val category: String,
    val defaultValue: T,
    val type: ConfigValueType,
    val validators: List<ConfigValidator<T>> = emptyList(),
    val overridable: Boolean = true,
    val sensitive: Boolean = false,
)
