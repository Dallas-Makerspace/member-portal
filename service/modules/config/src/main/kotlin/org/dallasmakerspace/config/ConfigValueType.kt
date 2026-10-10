package org.dallasmakerspace.config

import kotlinx.serialization.KSerializer

sealed class ConfigValueType {
  object StringType : ConfigValueType()

  object IntType : ConfigValueType()

  object DateType : ConfigValueType()

  object BooleanType : ConfigValueType()

  /**
   * A feature gate: globally on/off, plus optional per-username and per-group allowlists so a
   * feature can be piloted before a full rollout. Value type is always [FeatureFlagValue]. See
   * [ConfigOverrideService.isFeatureEnabledFor].
   */
  object FeatureFlagType : ConfigValueType()

  data class ComplexType<T>(val serializer: KSerializer<T>) : ConfigValueType()
}
