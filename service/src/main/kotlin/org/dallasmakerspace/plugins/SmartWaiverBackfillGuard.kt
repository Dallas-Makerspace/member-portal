package org.dallasmakerspace.plugins

import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig

/** Why the SmartWaiver backfill must not start right now, or null when it may. */
internal suspend fun smartWaiverBackfillRefusal(
    appConfig: AppConfig,
    configOverrideService: ConfigOverrideService,
): String? =
    when {
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services") ->
          "Refusing: mock services are on, so this would write mock waivers into the database."
      !configOverrideService.get(ConfigRegistry.SMARTWAIVER_MIRROR_ENABLED) ->
          "Refusing: smartwaiver.mirror.enabled is off. Apply smartwaiver-mirror-migration.sql, then turn it on."
      else -> null
    }
