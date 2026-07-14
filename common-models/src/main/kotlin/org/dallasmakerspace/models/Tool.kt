package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

@Serializable
data class Tool(
    val id: Int,
    val name: String,
    val committeeId: Int,
    val interlockTag: String,
    val timeoutSeconds: Int,
    val prerequisiteGroup: String?,
)

object Tools {
  val ALL =
      listOf(
          Tool(
              id = 1,
              name = "Auto Lift 1",
              committeeId = 17,
              interlockTag = "auto-lift-1",
              timeoutSeconds = 30,
              prerequisiteGroup = "Automotive 102 (Lift Training)",
          ),
          Tool(
              id = 2,
              name = "Blacksmith Forge 1",
              committeeId = 2,
              interlockTag = "blacksmith-forge-1",
              timeoutSeconds = 30,
              prerequisiteGroup = "Blacksmith Induction Forge",
          ),
      )

  fun findBySlug(slug: String): Tool? = ALL.find { it.interlockTag == slug }

  fun byCommittee(): Map<Committee, List<Tool>> = ALL.groupBy {
    Committees.findById(it.committeeId)!!
  }
}
