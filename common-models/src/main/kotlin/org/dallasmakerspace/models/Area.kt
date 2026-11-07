package org.dallasmakerspace.models

/** A part of the space, seen through its door readers and interlocked tools. */
data class Area(
    val key: String,
    val name: String,
    val committeeId: Int,
    /** Door names, as in `AccessControl.slots`. */
    val doors: List<String>,
    val interlockTags: List<String>,
) {
  val tools: List<Tool>
    get() = Tools.ALL.filter { it.interlockTag in interlockTags }

  val chairGroup: String?
    get() = Committees.findById(committeeId)?.chairGroupName
}

object Areas {
  val ALL =
      listOf(
          Area(
              key = "auto",
              name = "Automotive area",
              committeeId = 17,
              doors = listOf("Garage-In"),
              interlockTags = listOf("auto-lift-1"),
          ),
      )

  fun find(text: String): Area? {
    val wanted = text.trim()
    return ALL.firstOrNull { it.key.equals(wanted, true) || it.name.equals(wanted, true) }
  }
}
