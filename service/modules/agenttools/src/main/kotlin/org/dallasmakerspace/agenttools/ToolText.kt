package org.dallasmakerspace.agenttools

private val WHITESPACE = Regex("\\s+")

/** Single-line, length-capped text from a source we do not control (event titles, course names). */
internal fun cleanText(text: String?, max: Int = 120): String {
  val flat = (text ?: "").replace('<', ' ').replace('>', ' ').replace(WHITESPACE, " ").trim()
  return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
