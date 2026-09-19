package org.dallasmakerspace.dataviz.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.dataviz.reports.TimeOfDayReport
import org.dallasmakerspace.dataviz.reports.VisitorsByDayReport
import org.dallasmakerspace.dataviz.reports.accesscontrol.BadgeSwipesReport
import org.dallasmakerspace.dataviz.reports.calendar.CalTrendsEvents
import org.dallasmakerspace.dataviz.reports.calendar.FirstEvents
import org.dallasmakerspace.dataviz.reports.calendar.GroupCalendarReport
import org.dallasmakerspace.dataviz.reports.calendar.TopAttendees
import org.dallasmakerspace.dataviz.reports.calendar.TopOrganizers
import org.dallasmakerspace.dataviz.reports.groups.GroupHistoryReport
import org.dallasmakerspace.dataviz.reports.it.AdReplicationFailureTrendReport
import org.dallasmakerspace.dataviz.reports.it.AdReplicationHealthReport
import org.dallasmakerspace.dataviz.reports.it.AdReplicationLatencyTrendReport
import org.dallasmakerspace.dataviz.reports.interlock.CabinetActivityReport
import org.dallasmakerspace.dataviz.reports.interlock.CabinetDeniedAccessReport
import org.dallasmakerspace.dataviz.reports.interlock.CabinetInactivityReport
import org.dallasmakerspace.dataviz.reports.interlock.DeniedAccessReport
import org.dallasmakerspace.dataviz.reports.interlock.InterlockUsageReport
import org.dallasmakerspace.dataviz.reports.interlock.ToolActivityReport
import org.dallasmakerspace.dataviz.reports.interlock.ToolInactivityReport
import org.dallasmakerspace.dataviz.reports.interlock.TopCabinetsReport
import org.dallasmakerspace.dataviz.reports.interlock.TopToolsReport
import org.dallasmakerspace.dataviz.reports.interlock.TopUsersReport
import org.dallasmakerspace.dataviz.reports.interlock.UsageHeatmapReport
import org.dallasmakerspace.dataviz.reports.it.BadgeValidationAdReport
import org.dallasmakerspace.dataviz.reports.it.BadgeValidationMergedReport
import org.dallasmakerspace.dataviz.reports.it.LapsedMembersActiveAdReport
import org.dallasmakerspace.dataviz.reports.it.NewMembersReport
import org.dallasmakerspace.dataviz.reports.it.RecentSignedWaivers
import org.dallasmakerspace.dataviz.reports.membership.ActiveMembers
import org.dallasmakerspace.dataviz.reports.membership.BadgeValidationReport
import org.dallasmakerspace.dataviz.reports.membership.DistributionDistance
import org.dallasmakerspace.dataviz.reports.membership.DistributionRate
import org.dallasmakerspace.smartwaiver.SignedWaivers

@Module
class DataVizModule {
  @Provides
  @ElementsIntoSet
  @Suppress("LongParameterList")
  fun provideReports(
      visitorsByDayReport: VisitorsByDayReport,
      timeOfDayReport: TimeOfDayReport,
      calTrendsEvents: CalTrendsEvents,
      firstEvents: FirstEvents,
      groupCalendarReport: GroupCalendarReport,
      topOrganizers: TopOrganizers,
      topAttendees: TopAttendees,
      activeMembers: ActiveMembers,
      distributionDistance: DistributionDistance,
      distributionRate: DistributionRate,
      signedWaivers: SignedWaivers,
      groupHistoryReport: GroupHistoryReport,
      badgeSwipesReport: BadgeSwipesReport,
      interlockUsageReport: InterlockUsageReport,
      deniedAccessReport: DeniedAccessReport,
      toolActivityReport: ToolActivityReport,
      toolInactivityReport: ToolInactivityReport,
      topToolsReport: TopToolsReport,
      topUsersReport: TopUsersReport,
      usageHeatmapReport: UsageHeatmapReport,
      topCabinetsReport: TopCabinetsReport,
      cabinetActivityReport: CabinetActivityReport,
      cabinetInactivityReport: CabinetInactivityReport,
      cabinetDeniedAccessReport: CabinetDeniedAccessReport,
      badgeValidationReport: BadgeValidationReport,
      badgeValidationAdReport: BadgeValidationAdReport,
      badgeValidationMergedReport: BadgeValidationMergedReport,
      lapsedMembersActiveAdReport: LapsedMembersActiveAdReport,
      newMembersReport: NewMembersReport,
      recentSignedWaivers: RecentSignedWaivers,
      adReplicationHealthReport: AdReplicationHealthReport,
      adReplicationLatencyTrendReport: AdReplicationLatencyTrendReport,
      adReplicationFailureTrendReport: AdReplicationFailureTrendReport,
  ): Set<DataVizReport> {
    return setOf(
        visitorsByDayReport,
        timeOfDayReport,
        calTrendsEvents,
        firstEvents,
        groupCalendarReport,
        topOrganizers,
        topAttendees,
        activeMembers,
        distributionDistance,
        distributionRate,
        signedWaivers,
        groupHistoryReport,
        badgeSwipesReport,
        interlockUsageReport,
        deniedAccessReport,
        toolActivityReport,
        toolInactivityReport,
        topToolsReport,
        topUsersReport,
        usageHeatmapReport,
        topCabinetsReport,
        cabinetActivityReport,
        cabinetInactivityReport,
        cabinetDeniedAccessReport,
        badgeValidationReport,
        badgeValidationAdReport,
        badgeValidationMergedReport,
        lapsedMembersActiveAdReport,
        newMembersReport,
        recentSignedWaivers,
        adReplicationHealthReport,
        adReplicationLatencyTrendReport,
        adReplicationFailureTrendReport,
    )
  }
}
