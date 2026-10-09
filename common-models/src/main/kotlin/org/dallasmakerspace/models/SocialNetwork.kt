package org.dallasmakerspace.models

private const val MAX_INPUT_LENGTH = 500

/**
 * A social network members can link from their profile. Members paste a profile URL; only the
 * handle or path is stored (as a member setting), and links are always rebuilt on the network's
 * own domain.
 */
enum class SocialNetwork(
    val settingKey: String,
    val displayName: String,
    val example: String,
    private val baseUrl: String,
    host: String,
    value: String,
) {
  LINKEDIN(
      settingKey = "social_linkedin",
      displayName = "LinkedIn",
      example = "https://www.linkedin.com/in/yourname",
      baseUrl = "https://www.linkedin.com/in/",
      host = """linkedin\.com/in/""",
      value = """(?:[A-Za-z0-9-]|%[0-9A-Fa-f]{2}){3,100}""",
  ),
  YOUTUBE(
      settingKey = "social_youtube",
      displayName = "YouTube",
      example = "https://www.youtube.com/@yourchannel",
      baseUrl = "https://www.youtube.com/",
      host = """youtube\.com/""",
      value =
          """@(?:[A-Za-z0-9._-]|%[0-9A-Fa-f]{2}){3,100}|channel/UC[A-Za-z0-9_-]{22}""" +
              """|(?:c|user)/[A-Za-z0-9._-]{1,100}""",
  ),
  FACEBOOK(
      settingKey = "social_facebook",
      displayName = "Facebook",
      example = "https://www.facebook.com/yourname",
      baseUrl = "https://www.facebook.com/",
      host = """facebook\.com/""",
      value =
          """profile\.php\?id=[0-9]{5,20}|share/[A-Za-z0-9]{5,30}""" +
              """|(?!(?:profile\.php|share|groups|pages|people|events|watch|photo|reel""" +
              """|marketplace|story\.php|permalink\.php)(?:[/?#&]|$))[A-Za-z0-9.]{5,50}""",
  ),
  INSTAGRAM(
      settingKey = "social_instagram",
      displayName = "Instagram",
      example = "https://www.instagram.com/yourname",
      baseUrl = "https://www.instagram.com/",
      host = """instagram\.com/""",
      value =
          """(?!(?:p|reel|reels|stories|explore|accounts|direct|tv)(?:[/?#&]|$))""" +
              """[A-Za-z0-9._]{1,30}""",
  );

  /** Matches a pasted profile URL (case-insensitive); group 1 is the stored value. */
  val inputPattern: String = """^(?:https?://)?(?:[a-z0-9-]+\.)*$host($value)(?:[/?#&].*)?$"""

  private val inputRegex = Regex(inputPattern, RegexOption.IGNORE_CASE)
  private val valueRegex = Regex("""^(?:$value)$""", RegexOption.IGNORE_CASE)

  /** The value to store for a pasted profile URL, or null if it isn't a link to a profile. */
  fun parse(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.length > MAX_INPUT_LENGTH) return null
    return inputRegex.matchEntire(trimmed)?.groupValues?.get(1)
  }

  fun isValidValue(value: String): Boolean = valueRegex.matches(value)

  fun profileUrl(value: String): String = baseUrl + value

  companion object {
    fun fromSettingKey(key: String): SocialNetwork? = entries.find { it.settingKey == key }
  }
}
