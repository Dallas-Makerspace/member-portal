package org.dallasmakerspace.doorcontroller

/** Mapping of door controller names to user-friendly door identifiers. */
object DoorMapping {
  /**
   * Map of controller name + door number to user-friendly door names. Format:
   * "controller_name+door_number" -> "friendly_door_name"
   *
   * Example: "doors_normal_104+1" -> "lobby_entrance"
   */
  val DOOR_NAMES =
      mapOf(
          // Building 102 - Normal Doors
          "doors_normal_102+1" to "Front-In",
          "doors_normal_102+2" to "Flex-In",
          "doors_normal_102+3" to "Common-In",
          "doors_normal_102+4" to "Back-In",

          // Building 104 - Normal Doors
          "doors_normal_104+1" to "Front W Entr-In",
          "doors_normal_104+2" to "Whse S Entr-In",
          "doors_normal_104+3" to "Woodshop-In",
          "doors_normal_104+4" to "Whse E Entr-In",

          // Building 102 - Special Doors
          "doors_special_102+1" to "Ramp-In",
          "doors_special_102+2" to "102 Server-In",
          "doors_special_102+3" to "Admin-In",
          "doors_special_102+4" to "Storage-In",

          // Building 104 - Special Doors
          "doors_special_104+1" to "Server-In",
          "doors_special_104+2" to "Electrical-In",
          "doors_special_104+3" to "N/A-In",
          "doors_special_104+4" to "Garage-In",
      )

  /**
   * Get friendly name for a door.
   *
   * @param controllerName Name of the controller
   * @param doorNumber Door number (1-4)
   * @return Friendly door name, or default format if not mapped
   */
  fun getFriendlyName(controllerName: String, doorNumber: Int): String {
    val key = "$controllerName+$doorNumber"
    return DOOR_NAMES[key] ?: "${controllerName}_door_$doorNumber"
  }
}
