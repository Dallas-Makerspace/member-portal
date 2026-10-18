package org.dallasmakerspace.notifications.templates

/** Matches `.featured-project-img-wrap` on the profile page. */
private const val THUMB_WIDTH = 218
private const val THUMB_HEIGHT = 147

/** Usable width inside the email shell's card (600px - 2x30px padding - 2x20px card padding). */
private const val COLUMNS_WIDTH = 500
private const val TEXT_WIDTH = COLUMNS_WIDTH - THUMB_WIDTH
private const val GUTTER = "16px"

/**
 * Thumbnail left, text right - built the "hybrid" way: inline-block columns that sit side by side
 * when there's room and stack when there isn't (no media-query support needed), an MSO ghost table
 * for Outlook desktop (which ignores inline-block), and `dms-col` / `dms-thumb` classes that the
 * email shell's mobile media query uses to make the stacked thumbnail full width. With a blank
 * [imageUrl] the text takes the full width. [bodyHtml] must already be escaped; [imageUrl] and
 * [imageAlt] are escaped here. The thumbnail links to [linkUrl].
 */
fun thumbnailColumnsHtml(
    imageUrl: String,
    imageAlt: String,
    linkUrl: String,
    bodyHtml: String,
): String = buildString {
  val hasImage = imageUrl.isNotBlank()
  append("<div style=\"font-size:0;line-height:0;\">")
  if (hasImage) {
    append("<!--[if mso]><table role=\"presentation\" width=\"$COLUMNS_WIDTH\"><tr>")
    append("<td width=\"$THUMB_WIDTH\" valign=\"top\"><![endif]-->")
    append("<div class=\"dms-col\" style=\"display:inline-block;vertical-align:top;")
    append("width:${THUMB_WIDTH}px;max-width:100%;font-size:16px;line-height:22px;\">")
    append("<a href=\"${escapeHtml(linkUrl)}\" style=\"text-decoration:none;\">")
    append("<img class=\"dms-thumb\" src=\"${escapeHtml(imageUrl)}\" ")
    append("alt=\"${escapeHtml(imageAlt)}\" width=\"$THUMB_WIDTH\" ")
    append("height=\"$THUMB_HEIGHT\" style=\"display:block;width:${THUMB_WIDTH}px;")
    append("height:${THUMB_HEIGHT}px;object-fit:cover;border:0;border-radius:12px;")
    append("background-color:#1a1a1a;box-shadow:0 2px 10px rgba(0,0,0,0.09);\"></a></div>")
    append("<!--[if mso]></td><td width=\"${COLUMNS_WIDTH - THUMB_WIDTH}\" valign=\"top\"><![endif]-->")
  }
  append("<div class=\"dms-col\" style=\"display:inline-block;vertical-align:top;width:100%;")
  append("max-width:${if (hasImage) TEXT_WIDTH else COLUMNS_WIDTH}px;font-size:16px;line-height:22px;\">")
  append("<div class=\"dms-text\" style=\"padding:0")
  if (hasImage) append(" 0 0 $GUTTER")
  append(";\">")
  append(bodyHtml)
  append("</div></div>")
  if (hasImage) append("<!--[if mso]></td></tr></table><![endif]-->")
  append("</div>")
}
