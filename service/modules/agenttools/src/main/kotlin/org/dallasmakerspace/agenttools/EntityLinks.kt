package org.dallasmakerspace.agenttools

import java.net.URLEncoder

/** A markdown link to a member; the chat page renders it as a member card. */
internal fun memberLink(username: String, name: String? = null): String =
    "[${linkText(name ?: username)}](/profile/@$username)"

/** A markdown link to a group; the chat page renders it as a group pill. */
internal fun groupLink(name: String): String =
    "[${linkText(name)}](/groups/${URLEncoder.encode(name, Charsets.UTF_8)})"

private fun linkText(text: String) = text.replace('[', '(').replace(']', ')')
