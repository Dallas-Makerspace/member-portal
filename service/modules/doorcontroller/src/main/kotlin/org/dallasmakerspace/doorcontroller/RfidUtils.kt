package org.dallasmakerspace.doorcontroller

/**
 * RFID card format conversion utilities for EM4100/4001 spec RFID cards.
 *
 * On an EM4100/4001 spec RFID card there are typically two sets of numbers:
 * - 10-digit format: e.g., 0015362878
 * - Comma format: e.g., 234,27454
 *
 * The part before the comma represents the first hex byte of the "10 digit" number, and the second
 * part is the last 2 hex bytes of the "10 digit" card number.
 *
 * Example: 15362878 = EA6B3E
 * - EA (hex) = 234 (decimal)
 * - 6B3E (hex) = 27454 (decimal)
 */
object RfidUtils {
  /**
   * Converts a 10-digit RFID number to comma-format (without the comma).
   *
   * @param badge 10-digit RFID card number
   * @return Comma-format number as integer (e.g., 23427454)
   * @throws IllegalArgumentException if badge number is invalid
   */
  fun tenDigitToCommaFormat(badge: Int): Int {
    // Only the last 8 digits are the ID, and the 8 digits correspond to only 6 hex values
    // So the max is 0xFFFFFF = 16777215
    require(badge <= 16777215) { "Invalid RFID number: $badge (max: 16777215)" }

    // Convert to hex string, pad to 6 characters
    val formattedId = badge.toString(16).padStart(6, '0')

    // Split at first two and last 4 hex chars, convert each to decimal
    val firstPart = formattedId.substring(0, 2).toInt(16).toString().padStart(3, '0')
    val secondPart = formattedId.substring(2, 6).toInt(16).toString().padStart(5, '0')

    return (firstPart + secondPart).toInt()
  }

  /**
   * Converts comma-format RFID number (without comma) to 10-digit format.
   *
   * @param badge Comma-format RFID card number (e.g., 23427454)
   * @return 10-digit RFID number
   * @throws IllegalArgumentException if badge number is invalid
   */
  fun commaFormatToTenDigit(badge: Int): Int {
    // The 8 digits correspond to a set of two and four hex values
    // Max is decimal version of FF and FFFF concatenated = 25565535
    require(badge <= 25565535) { "Invalid RFID number: $badge (max: 25565535)" }

    val badgeStr = badge.toString().padStart(8, '0')

    // Split at last 5 digits and everything except last 5
    val firstPart = badgeStr.substring(0, 3).toInt().toString(16).padStart(2, '0')
    val secondPart = badgeStr.substring(3, 8).toInt().toString(16).padStart(4, '0')

    // Combine hex strings and convert to integer
    return (firstPart + secondPart).toInt(16)
  }
}
