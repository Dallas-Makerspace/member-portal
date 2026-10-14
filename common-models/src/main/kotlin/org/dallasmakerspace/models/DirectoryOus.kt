package org.dallasmakerspace.models

/** The AD organizational units whose groups the portal lists and shows. */
object DirectoryOus {
  /** The main group OU; the only one whose groups the portal may archive (delete from AD). */
  const val MAIN_GROUP_OU = "OU=Groups,DC=dms,DC=local"

  /** Search bases, in precedence order: the first wins if the same group name appears twice. */
  val GROUP_OUS = listOf(MAIN_GROUP_OU, "OU=Groups,OU=Admin,DC=dms,DC=local")

  private val groupOuParts = GROUP_OUS.map(::dnParts)
  private val mainGroupOuParts = dnParts(MAIN_GROUP_OU)

  /** True if [dn] is inside (at any depth) one of [GROUP_OUS]; case and spacing are ignored. */
  fun isInGroupOu(dn: String?): Boolean = groupOuParts.any { isBelow(dn, it) }

  /** True if [dn] is inside (at any depth) [MAIN_GROUP_OU]; the admin group OU does not count. */
  fun isInMainGroupOu(dn: String?): Boolean = isBelow(dn, mainGroupOuParts)

  /** True if [ou] is [MAIN_GROUP_OU] itself or an OU inside it. */
  fun isMainGroupOuOrChild(ou: String?): Boolean =
      !ou.isNullOrBlank() && (dnParts(ou) == mainGroupOuParts || isBelow(ou, mainGroupOuParts))

  private fun isBelow(dn: String?, ouParts: List<String>): Boolean {
    if (dn.isNullOrBlank()) return false
    val parts = dnParts(dn)
    return parts.size > ouParts.size && parts.takeLast(ouParts.size) == ouParts
  }

  /** Lowercased, trimmed RDNs; splits only on unescaped commas so a crafted CN cannot fake an OU. */
  private fun dnParts(dn: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (c in dn) {
      when {
        escaped -> {
          current.append(c)
          escaped = false
        }
        c == '\\' -> {
          current.append(c)
          escaped = true
        }
        c == ',' -> {
          parts.add(current.toString().trim().lowercase())
          current.clear()
        }
        else -> current.append(c)
      }
    }
    parts.add(current.toString().trim().lowercase())
    return parts
  }
}
