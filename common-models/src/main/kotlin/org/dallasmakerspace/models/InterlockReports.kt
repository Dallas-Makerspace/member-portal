package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

object Interlocks {
  const val DEFAULT_REPORT_DAYS = 30
  const val DEFAULT_INACTIVITY_DAYS = 14
}

@Serializable
data class InterlockReport(
    val slug: String,
    val name: String,
    val description: String,
    val dataVizName: String,
    val defaultDays: Int = Interlocks.DEFAULT_REPORT_DAYS,
    val renderAs: String = "table",
)

object ToolReports {
  val ALL =
      listOf(
          InterlockReport(
              slug = "top-users",
              name = "Top Users (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Leaderboard by sessions and total hours.",
              dataVizName = "interlock-top-users",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "usage-heatmap",
              name = "Usage Patterns (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "When the tool is busiest, by hour of day and day of week.",
              dataVizName = "interlock-usage-heatmap",
              renderAs = "bar",
          ),
          InterlockReport(
              slug = "denied-access",
              name = "Denied Access Attempts (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Unauthorized swipes — member, time, badge.",
              dataVizName = "interlock-denied-access",
              renderAs = "table",
          ),
      )

  fun findBySlug(slug: String): InterlockReport? = ALL.find { it.slug == slug }
}

object CommitteeReports {
  val ALL =
      listOf(
          InterlockReport(
              slug = "top-tools",
              name = "Top Tools (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Most-used tools by number of members and total hours.",
              dataVizName = "interlock-top-tools",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "tool-activity",
              name = "Tool Activity (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Distinct members per tool per day — one line per tool.",
              dataVizName = "interlock-tool-activity",
              renderAs = "line",
          ),
          InterlockReport(
              slug = "tool-inactivity",
              name = "Tool Inactivity / Gaps",
              description = "Last-used timestamp per tool; flags silent tools.",
              dataVizName = "interlock-tool-inactivity",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "denied-access",
              name = "Denied Access Attempts (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Unauthorized swipes across all tools in the committee.",
              dataVizName = "interlock-denied-access",
              renderAs = "table",
          ),
      )

  fun findBySlug(slug: String): InterlockReport? = ALL.find { it.slug == slug }
}

object CabinetReports {
  val ALL =
      listOf(
          InterlockReport(
              slug = "top-users",
              name = "Top Users (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Leaderboard by sessions and total hours.",
              dataVizName = "interlock-top-users",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "usage-heatmap",
              name = "Usage Patterns (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "When the cabinet is busiest, by hour of day and day of week.",
              dataVizName = "interlock-usage-heatmap",
              renderAs = "bar",
          ),
          InterlockReport(
              slug = "denied-access",
              name = "Denied Access Attempts (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Unauthorized swipes — member, time, badge.",
              dataVizName = "interlock-cabinet-denied-access",
              renderAs = "table",
          ),
      )

  fun findBySlug(slug: String): InterlockReport? = ALL.find { it.slug == slug }
}

object CabinetCommitteeReports {
  val ALL =
      listOf(
          InterlockReport(
              slug = "top-cabinets",
              name = "Top Cabinets (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Most-used cabinets by number of members and total hours.",
              dataVizName = "interlock-top-cabinets",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "cabinet-activity",
              name = "Cabinet Activity (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Distinct members per cabinet per day — one line per cabinet.",
              dataVizName = "interlock-cabinet-activity",
              renderAs = "line",
          ),
          InterlockReport(
              slug = "cabinet-inactivity",
              name = "Cabinet Inactivity / Gaps",
              description = "Last-used timestamp per cabinet; flags silent cabinets.",
              dataVizName = "interlock-cabinet-inactivity",
              renderAs = "table",
          ),
          InterlockReport(
              slug = "denied-access",
              name = "Denied Access Attempts (last ${Interlocks.DEFAULT_REPORT_DAYS} days)",
              description = "Unauthorized swipes across all cabinets in the committee.",
              dataVizName = "interlock-cabinet-denied-access",
              renderAs = "table",
          ),
      )

  fun findBySlug(slug: String): InterlockReport? = ALL.find { it.slug == slug }
}
