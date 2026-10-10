package org.dallasmakerspace.doorcontroller

import org.dallasmakerspace.core.AppConfig

/** Configuration for door controller system. */
data class DoorControllerConfig(
    val timeoutSeconds: Int,
    val username: String,
    val password: String,
    val controllers: List<ControllerDefinition>,
) {
  companion object {
    @Suppress("MagicNumber")
    fun fromAppConfig(appConfig: AppConfig): DoorControllerConfig {
      val timeoutSeconds = appConfig.requireIntProperty("app.door-controller.timeout-seconds")
      val username = appConfig.requireStringProperty("app.door-controller.username")
      val password = appConfig.requireStringProperty("app.door-controller.password")

      // Load controllers from configuration
      // Each controller is defined with indexed properties (1-based)
      val controllers = mutableListOf<ControllerDefinition>()

      for (i in 1..10) { // Support up to 10 controllers
        val nameKey = "app.door-controller.controller-$i-name"
        val name = appConfig.getStringProperty(nameKey, "")

        // If no name is configured, we've reached the end of the list
        if (name.isEmpty()) {
          break
        }

        val ip = appConfig.requireStringProperty("app.door-controller.controller-$i-ip")
        val serial = appConfig.requireLongProperty("app.door-controller.controller-$i-serial")
        val doorsStr = appConfig.requireStringProperty("app.door-controller.controller-$i-doors")

        // Parse comma-separated door numbers
        val doors = doorsStr.split(",").map { it.trim().toInt() }.filter { it in 1..4 }

        controllers.add(ControllerDefinition(name = name, ip = ip, serial = serial, doors = doors))
      }

      return DoorControllerConfig(timeoutSeconds, username, password, controllers)
    }
  }
}

/**
 * Definition of a single door controller.
 *
 * @param name Controller identifier (e.g., "doors_normal_102")
 * @param ip IP address of the controller
 * @param serial Serial number written on the controller
 * @param doors List of door numbers this controller manages (1-4)
 */
data class ControllerDefinition(
    val name: String,
    val ip: String,
    val serial: Long,
    val doors: List<Int>,
)

/** Result of a door controller operation. */
sealed class DoorControllerResult {
  data class Success(val message: String) : DoorControllerResult()

  data class Failure(val error: String, val cause: Throwable? = null) : DoorControllerResult()
}

/** Result of checking user access to doors. */
data class UserAccessInfo(val badge: Int, val controllers: Map<String, ControllerAccessInfo>)

/** Access information for a specific controller. */
data class ControllerAccessInfo(
    val controllerName: String,
    val hasAccess: Boolean,
    val allowedDoors: List<Int>,
    val error: String? = null,
)

/** Represents a badge swipe event from a door controller. */
data class BadgeSwipeEvent(
    val controllerName: String,
    val doorNumber: Int,
    val badge: Int,
    val timestamp: Long,
    val eventType: SwipeEventType,
    val friendlyDoorName: String,
    val recordId: Int? = null,
    val rawStatus: String? = null,
)

/** Type of badge swipe event. */
enum class SwipeEventType {
  ACCESS_GRANTED,
  ACCESS_DENIED,
  UNKNOWN,
}

/** Result of querying badge swipes from all controllers. */
data class BadgeSwipesResult(val events: List<BadgeSwipeEvent>, val errors: List<String>)
