package org.dallasmakerspace.askai.tools

/** Plain text from Confluence HTML or search highlights. */
internal object HtmlText {
  private val SCRIPT = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL)
  private val STYLE = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL)
  private val TAG = Regex("<[^>]+>")
  private val NUMERIC_ENTITY = Regex("&#\\d+;")
  private val WHITESPACE = Regex("\\s+")

  fun plain(html: String): String =
      html
          .replace(SCRIPT, "")
          .replace(STYLE, "")
          .replace(TAG, " ")
          .replace("@@@hl@@@", "")
          .replace("@@@endhl@@@", "")
          .replace("&nbsp;", " ")
          .replace("&lt;", "<")
          .replace("&gt;", ">")
          .replace("&quot;", "\"")
          .replace(NUMERIC_ENTITY, "")
          .replace("&amp;", "&")
          .replace(WHITESPACE, " ")
          .trim()
}
