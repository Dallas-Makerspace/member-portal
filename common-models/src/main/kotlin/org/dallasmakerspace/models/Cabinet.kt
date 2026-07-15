package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

@Serializable
data class Cabinet(
    val id: Int,
    val name: String,
    val committeeId: Int,
    val interlockTag: String,
    val prerequisiteGroup: String?,
)

object Cabinets {
  val ALL =
      listOf(
          Cabinet(
              id = 1,
              name = "Logistics Cabinet 1",
              committeeId = 21,
              interlockTag = "logistics-cabinet-1",
              prerequisiteGroup = "Logistics Cabinet 1",
          ),
      )

  fun findBySlug(slug: String): Cabinet? = ALL.find { it.interlockTag == slug }

  fun byCommittee(): Map<Committee, List<Cabinet>> =
      ALL.groupBy { Committees.findById(it.committeeId)!! }
}
