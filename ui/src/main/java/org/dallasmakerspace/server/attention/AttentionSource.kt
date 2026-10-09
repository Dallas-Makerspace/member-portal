package org.dallasmakerspace.server.attention

/**
 * Something that may need the signed-in member's attention, shown as the red dot on the navbar
 * avatar plus an entry in the account menu. To add one, implement this and bind it with
 * `@Binds @IntoSet` in [org.dallasmakerspace.server.attention.di.AttentionModule].
 */
interface AttentionSource {
  /** Null when there's nothing to flag. Failures are logged and treated as null. */
  suspend fun check(username: String, sessionId: String?): AttentionItem?
}

/**
 * [key] matches an account-menu entry's `data-attention-key` (e.g. "settings", "notifications") to
 * flag that entry; any other key gets its own entry at the top of the menu linking to [url].
 */
data class AttentionItem(
    val key: String,
    val message: String,
    val url: String,
    val count: Int? = null,
)
