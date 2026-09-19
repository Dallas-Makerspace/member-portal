package org.dallasmakerspace.dataviz

import org.dallasmakerspace.core.AppConfig

/**
 * Shared Kibana "Discover" deep-link builder - factored out of `InterlockEventsService` so every
 * report/domain that wants a "view raw data in Kibana" metadata link (interlock's reports, and AD
 * replication health) shares one implementation of Kibana's URL-state encoding instead of each
 * maintaining its own copy.
 *
 * [dataView] is a required, explicit parameter rather than always reading a single global config
 * value: each ES index this app writes to (interlock's `not-john-index`, AD replication's
 * `member-portal-ad-replication-health`, ...) needs its OWN Kibana saved data view - reusing one
 * data view across different indices would produce a link that silently returns zero results,
 * pointed at the wrong index entirely.
 */
object KibanaLinks {
  /**
   * Builds a Kibana Discover URL for the given KQL query, time window, and [dataView] (a Kibana
   * saved data view id/title - NOT the raw ES index name - see the class doc). Returns null if
   * `app.kibana.base-url` or [dataView] is not configured, so callers can safely omit the
   * metadata entry entirely rather than show a dead link.
   */
  fun buildDiscoverUrl(appConfig: AppConfig, kql: String, days: Int, dataView: String): String? {
    if (dataView.isBlank()) return null
    val baseUrl = appConfig.getStringProperty("app.kibana.base-url", "").trimEnd('/')
    if (baseUrl.isBlank()) return null
    val space = appConfig.getStringProperty("app.kibana.space", "").ifBlank { "default" }
    // Selective encoding: leave RISON syntax chars intact; escape only space and double-quote.
    val encodedKql = kql.replace(" ", "%20").replace("\"", "%22")
    return "$baseUrl/s/$space/app/discover#/" +
        "?_g=(time:(from:now-${days}d,to:now))" +
        "&_a=(index:'$dataView',query:(language:kuery,query:'$encodedKql'))"
  }
}
