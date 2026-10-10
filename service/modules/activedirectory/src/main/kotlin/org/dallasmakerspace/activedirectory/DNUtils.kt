package org.dallasmakerspace.activedirectory

/**
 * Determine if a DN represents a group (vs. a user). Groups typically have OU=Security,
 * OU=Organizational, or OU=Groups in their DN. Users typically have OU=Users or OU=People in
 * their DN.
 *
 * @param dn The distinguished name to check
 * @return true if the DN represents a group, false otherwise
 */
fun isGroupDN(dn: String): Boolean {
  val lowerDN = dn.lowercase()
  return lowerDN.contains("ou=security") ||
      lowerDN.contains("ou=organizational") ||
      lowerDN.contains("ou=groups")
}

/**
 * Extracts the CN (common name) component from a distinguished name, e.g.
 * "CN=CA Teachers,OU=Security,OU=Groups,DC=dms,DC=local" -> "CA Teachers".
 *
 * @param dn The distinguished name to parse
 * @return The CN value, or the original string if no CN component is found
 */
fun parseCNFromDN(dn: String): String {
  val cnPrefix = "CN="
  val startIndex = dn.indexOf(cnPrefix, ignoreCase = true)
  if (startIndex == -1) return dn
  val cnStart = startIndex + cnPrefix.length
  val cnEnd = dn.indexOf(',', cnStart)
  return if (cnEnd != -1) dn.substring(cnStart, cnEnd) else dn.substring(cnStart)
}
