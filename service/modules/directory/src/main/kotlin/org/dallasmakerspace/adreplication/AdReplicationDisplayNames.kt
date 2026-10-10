package org.dallasmakerspace.adreplication

import java.util.Locale

/**
 * Strips the DNS domain suffix from a DC hostname for compact display (e.g. "DC1.dms.local" ->
 * "DC1") - the fully-qualified name doesn't add information once the reader knows these are all
 * DMS domain controllers, and its length was part of why the snapshot table used to force
 * horizontal scroll. Display-only: the underlying hostname (used for grouping/matching against
 * `app.ad-replication.dc-hostnames`) is untouched - see `AdReplicationHealthReport`/
 * `pivotTrend` for where this is applied only at the point of building the UI-facing value.
 */
fun shortDcName(hostname: String): String = hostname.substringBefore('.').uppercase(Locale.US)
