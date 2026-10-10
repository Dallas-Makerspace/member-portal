package org.dallasmakerspace.adreplication

import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.KibanaLinks

// No per-item KQL filter - unlike interlock's per-tool/committee host filter, all three AD
// replication reports show every configured DC, so there's nothing to filter by; the link just
// scopes to this index's own data view and the report's time window.
private const val AD_REPLICATION_KQL = ""

/**
 * Builds the "view in Kibana" metadata link shared by [AdReplicationHealthReport],
 * [AdReplicationLatencyTrendReport], and [AdReplicationFailureTrendReport] - kept in one place so
 * the three reports can't drift on data-view config key or KQL. See [KibanaLinks] for the actual
 * URL-encoding logic this delegates to.
 */
fun adReplicationKibanaLink(appConfig: AppConfig, days: Int): String? {
  val dataView = appConfig.getStringProperty("app.kibana.ad-replication-data-view", "")
  return KibanaLinks.buildDiscoverUrl(appConfig, AD_REPLICATION_KQL, days, dataView)
}
