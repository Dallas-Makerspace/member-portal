package org.dallasmakerspace.plugins

import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig

/**
 * Whether this service should pick up an interrupted backfill when it starts. Off unless
 * `SMARTWAIVER_BACKFILL_AUTO_RESUME=true` is set, which only the production service has: any other
 * service started against the same database (a local dev run) would otherwise resume the backfill
 * itself, take the run over from production, and run it with the wrong settings (for example no
 * object-store bucket). Starting a backfill on purpose is unaffected.
 */
internal fun shouldAutoResumeBackfill(appConfig: AppConfig): Boolean =
    appConfig.getStringProperty("app.smartwaiver.backfill.auto-resume", "false").toBoolean()

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
