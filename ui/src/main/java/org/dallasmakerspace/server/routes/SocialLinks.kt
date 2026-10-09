package org.dallasmakerspace.server.routes

import io.ktor.http.Parameters
import org.dallasmakerspace.models.SocialNetwork

/** Social profile links on the profile page, stored as member settings. */
object SocialLinks {
  const val FORM_NAME = "socials"

  /** Links to show on a profile, in display order; invalid stored values are skipped. */
  fun displayLinks(settings: Map<String, String>): List<Map<String, String>> =
      SocialNetwork.entries.mapNotNull { network ->
        storedValue(network, settings)?.let { value ->
          mapOf(
              "name" to network.displayName,
              "icon" to icon(network),
              "url" to network.profileUrl(value),
          )
        }
      }

  /** The dialog's fields, prefilled with the member's current links. */
  fun formFields(settings: Map<String, String>): List<Map<String, String>> =
      SocialNetwork.entries.map { network ->
        mapOf(
            "key" to network.settingKey,
            "name" to network.displayName,
            "icon" to icon(network),
            "example" to network.example,
            "pattern" to network.inputPattern,
            "value" to (storedValue(network, settings)?.let { network.profileUrl(it) } ?: ""),
        )
      }

  /**
   * Settings updates from the dialog: each posted field becomes its stored value, or "" to clear
   * it. Null if any field isn't a link to a profile on its network.
   */
  fun parseForm(params: Parameters): Map<String, String>? {
    val updates =
        SocialNetwork.entries.mapNotNull { network ->
          params[network.settingKey]?.let { input ->
            network.settingKey to (if (input.isBlank()) "" else network.parse(input))
          }
        }
    if (updates.any { it.second == null }) return null
    return updates.associate { (key, value) -> key to value.orEmpty() }
  }

  private fun storedValue(network: SocialNetwork, settings: Map<String, String>): String? =
      settings[network.settingKey]?.takeIf { network.isValidValue(it) }

  private fun icon(network: SocialNetwork) = "/static/images/${network.name.lowercase()}.svg"
}
