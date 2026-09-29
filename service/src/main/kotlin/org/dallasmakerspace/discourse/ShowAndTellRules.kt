package org.dallasmakerspace.discourse

/**
 * What counts as a member "project": an image reply in one of the monthly "Show and Tell" topics.
 * Shared by the profile's Featured Projects list ([FeaturedProjectsService]) and the follower
 * notification webhook so the two can never disagree.
 */
object ShowAndTellRules {
  const val CATEGORY_SLUG = "show-and-tell"
  const val CATEGORY_ID = 49
  private const val TOPIC_TITLE_PREFIX = "Show and Tell"
  private const val MIN_IMAGE_WIDTH = 300
  private const val DISCOURSE_BASE_URL = "https://talk.dallasmakerspace.org"

  // Capture each full <img ...> tag so we can inspect width and src together
  private val imgTagRegex = Regex("""<img([^>]+)>""")
  private val srcAttrRegex = Regex("""src="([^"]+)"""")
  private val largeSrcAttrRegex = Regex("""data-large-src="([^"]+)"""")
  private val widthAttrRegex = Regex("""width="(\d+)"""")

  fun isProjectTopic(title: String): Boolean = title.startsWith(TOPIC_TITLE_PREFIX)

  /** Post #1 is the monthly intro post; projects are the replies. */
  fun isProjectPost(postNumber: Int): Boolean = postNumber != 1

  /**
   * Finds the first large image URL from Discourse's rendered HTML. Prefers `data-large-src`
   * (full-resolution Discourse upload) over `src`. Skips images whose `width` attribute is below
   * [MIN_IMAGE_WIDTH] (filters out emojis, avatars, inline icons).
   */
  fun extractLargeImageUrl(cooked: String): String? {
    for (match in imgTagRegex.findAll(cooked)) {
      val attrs = match.groupValues[1]
      val width = widthAttrRegex.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
      if (width != null && width < MIN_IMAGE_WIDTH) continue
      val url =
          largeSrcAttrRegex.find(attrs)?.groupValues?.get(1)
              ?: srcAttrRegex.find(attrs)?.groupValues?.get(1)
              ?: continue
      if (url.isBlank()) continue
      return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$DISCOURSE_BASE_URL$url"
        else -> url
      }
    }
    return null
  }

  fun postUrl(topicSlug: String, topicId: Int, postNumber: Int): String =
      "$DISCOURSE_BASE_URL/t/$topicSlug/$topicId/$postNumber"
}
