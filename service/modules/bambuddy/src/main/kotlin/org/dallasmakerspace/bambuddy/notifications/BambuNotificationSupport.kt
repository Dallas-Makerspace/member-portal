package org.dallasmakerspace.bambuddy.notifications

import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig

/** The development placeholder shipped in application.conf for the BamBuddy webhook secret. */
const val DEV_WEBHOOK_SECRET_PLACEHOLDER = "dev-only-placeholder"

/** The unedited `.env.template` value; never a usable secret in any mode. */
const val TEMPLATE_WEBHOOK_SECRET_PLACEHOLDER = "your-bambuddy-webhook-secret-here"

/**
 * The configured BamBuddy webhook secret, or null when unset / the template value / the dev
 * placeholder outside development (fail closed: the receiver then 401s everything). Single shared
 * resolution used by the webhook handler per call and by the app module's boot warning.
 */
fun configuredBambuddyWebhookSecret(appConfig: AppConfig): String? {
  val raw =
      appConfig.getStringProperty("app.bambuddy.webhook-secret", "").takeIf { it.isNotBlank() }
          ?: return null
  if (raw == TEMPLATE_WEBHOOK_SECRET_PLACEHOLDER) return null
  if (raw == DEV_WEBHOOK_SECRET_PLACEHOLDER && !isBambuddyDevelopment(appConfig)) return null
  return raw
}

private fun isBambuddyDevelopment(appConfig: AppConfig): Boolean =
    try {
      appConfig.requireBooleanProperty("ktor.development")
    } catch (e: Exception) {
      false
    }

/**
 * Whether the Bambu notifications feature is in any rollout state (globally on, or piloting
 * to named users/groups). Webhooks and sweeper passes carry no single member identity, so
 * they process in any rollout state (design §12.1); fully off means no processing at all.
 */
fun FeatureFlagValue.inAnyRollout(): Boolean =
    enabled || enabledUsernames.isNotEmpty() || enabledGroupNames.isNotEmpty()

/** Strips CR/LF and caps length so payload-derived strings can never forge log lines. */
fun sanitizeForLog(value: String?, maxLength: Int = 80): String {
  val cleaned = value?.replace('\r', ' ')?.replace('\n', ' ')?.trim().orEmpty()
  return if (cleaned.length > maxLength) cleaned.take(maxLength) + "…" else cleaned
}
