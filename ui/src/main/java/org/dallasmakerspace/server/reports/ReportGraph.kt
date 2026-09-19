package org.dallasmakerspace.server.reports

object ReportGraph {
  @JvmStatic
  fun getRoot(): ReportNode {
    return ReportNode(
        name = "root",
        description = "Root node of the report graph",
        children =
            listOf(
                ReportNode(
                    name = "Membership",
                    description = "Membership reports",
                    urlSlug = "membership",
                    children =
                        listOf(
                            ReportNode(
                                name = "Active members",
                                description = "Active member trends",
                                urlSlug = "active-members",
                            ),
                            ReportNode(
                                name = "Distribution",
                                description = "Distribution of members",
                                urlSlug = "distribution",
                            ),
                        ),
                ),
                ReportNode(
                    name = "Calendar",
                    description = "Calendar reports",
                    urlSlug = "calendar",
                    children =
                        listOf(
                            ReportNode(
                                name = "Event Trends",
                                description = "Calendar trends",
                                urlSlug = "event-trends",
                            ),
                            ReportNode(
                                name = "First Events",
                                description = "First event for new members",
                                urlSlug = "first-events",
                            ),
                            ReportNode(
                                name = "Group Calendar",
                                description = "Prerequisite events for a specific group",
                                urlSlug = "group-calendar",
                                visibleInNav = false,
                            ),
                            ReportNode(
                                name = "Top Members",
                                description = "Top event organizers and attendees",
                                urlSlug = "top-members",
                            ),
                        ),
                ),
                ReportNode(
                    name = "Member Visits",
                    description = "Reports from badge swipes",
                    urlSlug = "member-visits",
                    children =
                        listOf(
                            ReportNode(
                                name = "Visitors by Date",
                                description = "Number of badge swipes per day",
                                urlSlug = "daily-activity",
                            ),
                            ReportNode(
                                name = "Time of Day",
                                description = "Average activity by time of day",
                                urlSlug = "time-of-day",
                            ),
                            ReportNode(
                                name = "Recent Badge Swipes",
                                description = "Recent badge swipe activity (Infra/Officers only)",
                                urlSlug = "badge-swipes",
                            ),
                        ),
                ),
                ReportNode(
                    name = "IT Infrastructure",
                    description = "IT Infrastructure reports",
                    urlSlug = "it",
                    children =
                        listOf(
                            ReportNode(
                                name = "Badge Validation",
                                description =
                                    "Badge validation across MakerManager and Active Directory (Infra only)",
                                urlSlug = "badge-validation-merged",
                            ),
                            ReportNode(
                                name = "Lapsed Members with Active AD",
                                description =
                                    "Past members with enabled AD accounts (badge 0000000)",
                                urlSlug = "lapsed-members-active-ad",
                            ),
                            ReportNode(
                                name = "Recent Signed Waivers",
                                description =
                                    "Waivers signed in the past 24 hrs / 7 days / 30 days (Infra/Officers only)",
                                urlSlug = "recent-signed-waivers",
                            ),
                            ReportNode(
                                name = "New Member Signups",
                                description =
                                    "Members who joined in a given month (Infra/Officers/Board only)",
                                urlSlug = "new-members",
                            ),
                            ReportNode(
                                name = "AD Replication Health",
                                description =
                                    "Domain controller replication status, latency, and failure trends - " +
                                        "sampled periodically via WinRM into Elasticsearch",
                                urlSlug = "ad-replication-health",
                            ),
                        ),
                ),
                ReportNode(
                    name = "Group History",
                    description = "History of group membership changes",
                    urlSlug = "group-history",
                    visibleInNav = false,
                ),
            ),
        urlSlug = "reports",
    )
  }
}

data class ReportNode(
    val name: String,
    val description: String,
    val children: List<ReportNode>? = null,
    val urlSlug: String,
    val visibleInNav: Boolean = true,
) {
  fun getVisibleChildren(): List<ReportNode>? {
    return children?.filter { it.visibleInNav }
  }
}
