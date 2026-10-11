package org.dallasmakerspace.activedirectory

private const val GUID_BYTES = 16
private const val GUID_HEX_LENGTH = 32
private val CANONICAL_GUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val HEX_32 = Regex("^[0-9a-f]{32}$")

/** AD objectGUID helpers. Canonical form is lowercase hyphenated, 36 chars. */
object AdGuid {
  /** Formats the raw 16-byte objectGUID attribute (Microsoft mixed-endian layout), else null. */
  fun fromBytes(bytes: ByteArray?): String? {
    if (bytes == null || bytes.size != GUID_BYTES) return null
    val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
    val hex = order.joinToString("") { "%02x".format(bytes[it].toInt() and 0xff) }
    return format(hex)
  }

  /** Normalizes a textual GUID (hyphenated, braced or bare hex, any case), else null. */
  fun normalize(raw: String?): String? {
    val trimmed = raw?.trim()?.removePrefix("{")?.removeSuffix("}")?.lowercase() ?: return null
    if (CANONICAL_GUID.matches(trimmed)) return trimmed
    if (HEX_32.matches(trimmed)) return format(trimmed)
    return null
  }

  private fun format(hex: String): String {
    require(hex.length == GUID_HEX_LENGTH)
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
  }
}
