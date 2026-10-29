package org.dallasmakerspace.agenttools

/** Small helpers shared by the member lookup tools. */
internal object UserToolSupport {
  private const val MAX_TEXT = 80
  private val USERNAME = Regex("^[A-Za-z0-9._-]{1,64}$")
  private val CONTROL = Regex("[\\p{Cntrl}\\s]+")

  const val SELF_ONLY_REFUSAL = "Members can only look up themselves. Ask about your own account."

  fun isValidUsername(value: String) = USERNAME.matches(value)

  /** Directory and course text is untrusted: one line, no control characters, bounded length. */
  fun clean(value: String?): String? {
    val text = value?.replace(CONTROL, " ")?.trim().orEmpty()
    return if (text.isEmpty() || text.equals("null", ignoreCase = true)) null else text.take(MAX_TEXT)
  }

  /** The last four digits only; shorter badge numbers are not shown at all. */
  fun maskBadge(badge: String): String {
    val digits = badge.trim()
    return if (digits.length > MASK_KEEP) "****" + digits.takeLast(MASK_KEEP) else "****"
  }

  fun hasBadge(value: String?) = clean(value) != null

  private const val MASK_KEEP = 4
}
