package org.dallasmakerspace.models

/** The AD organizational units whose groups the portal lists and shows. */
object DirectoryOus {
  /** Search bases, in precedence order: the first wins if the same group name appears twice. */
  val GROUP_OUS = listOf("OU=Groups,DC=dms,DC=local", "OU=Groups,OU=Admin,DC=dms,DC=local")

  private val normalizedGroupOus = GROUP_OUS.map(::normalizeDn)

  /** True if [dn] is inside (at any depth) one of [GROUP_OUS]; case and spacing are ignored. */
  fun isInGroupOu(dn: String?): Boolean {
    if (dn.isNullOrBlank()) return false
    val normalized = normalizeDn(dn)
    return normalizedGroupOus.any { normalized.endsWith(",$it") }
  }

  private fun normalizeDn(dn: String) =
      dn.split(',').joinToString(",") { it.trim().lowercase() }
}
