package org.dallasmakerspace.doorcontroller

/** Utility functions for door event processing Ported from Python upload.py script */
object DoorEventUtils {

  /**
   * Determine if access was granted based on status, options, and raw badge number
   *
   * @param status Event status code
   * @param options Event options code
   * @param rawBadge Raw badge number (CN format)
   * @return True if access was granted, false otherwise
   */
  @Suppress("ReturnCount", "MagicNumber", "ComplexCondition")
  fun isGranted(status: Int, options: Int, rawBadge: Int = 0): Boolean {
    // Calculate part: (options - (options mod 2)) / 2
    val part = (options - (options % 2)) / 2

    // Valid Swipe: ((((f_RecOption - (f_RecOption mod 2)) / 2) mod 2) =0) AND (f_Status < 128)
    if ((part % 2) == 0 && (status < 128)) {
      return true
    }

    // Remote Open: ((((f_RecOption - (f_RecOption mod 2)) / 2) mod 4) =3) AND (f_Status < 128)
    if ((part % 4) == 3 && (status < 128)) {
      return true
    }

    // Super Password Open: ((((f_RecOption - (f_RecOption mod 2)) / 2) mod 4) =1)
    // AND (f_Status < 128) AND (f_CardNO = 10 OR f_CardNO = 11 OR f_CardNO = 12)
    if ((part % 4) == 1 && (status < 128) && (rawBadge == 10 || rawBadge == 11 || rawBadge == 12)) {
      return true
    }

    // Invalid Swipe: ((((f_RecOption - (f_RecOption mod 2)) / 2) mod 2) =0) AND (f_Status >= 128)
    if ((part % 2) == 0 && (status >= 128)) {
      return false
    }

    // Default to false
    return false
  }

  /**
   * Get human-readable status text for the event
   *
   * @param status Event status code
   * @param options Event options code
   * @param rawBadge Raw badge number (CN format)
   * @return Status text description, or null if unknown
   */
  @Suppress("CyclomaticComplexMethod", "MagicNumber")
  fun getStatusText(status: Int, options: Int, rawBadge: Int = 0): String? {
    // Calculate part: (options - (options mod 2)) / 2
    val part = (options - (options % 2)) / 2
    val isSuccess = status < 128
    val isFailure = status >= 128

    return when {
      // Valid Swipe
      (part % 2) == 0 && isSuccess -> "Valid Swipe"
      // Invalid Swipe
      (part % 2) == 0 && isFailure -> "Invalid Swipe"
      // Remote Open
      (part % 4) == 3 && isSuccess -> "Remote Open"
      // Push Button
      (part % 4) == 1 && rawBadge in 1..7 -> "Push Button"
      // Door Status
      (part % 4) == 1 && isSuccess && rawBadge in 8..9 -> "Door Status"
      // Super Password Open
      (part % 4) == 1 && isSuccess && rawBadge in 10..12 -> "Super Password Open"
      // Warn
      (part % 4) == 1 && isFailure && rawBadge in 81..90 -> "Warn"
      // Unknown status
      else -> null
    }
  }
}
