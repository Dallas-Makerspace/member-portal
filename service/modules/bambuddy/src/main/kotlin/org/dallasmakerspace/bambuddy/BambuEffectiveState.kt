package org.dallasmakerspace.bambuddy

/** Printer states in which a job is (or is about to be) printing — a printer-available watch is
 * only meaningful while one of these holds. Shared by the status page and the watch domain. */
val BAMBU_PRINTING_STATES = setOf("RUNNING", "PREPARE", "SLICING", "PAUSE")

/**
 * Effective state, folding "Bambuddy couldn't reach the printer at all" (no status / a
 * connected=false status) into OFFLINE regardless of what a stale `state` value might say.
 */
fun bambuEffectiveState(status: BambuPrinterStatus?): String =
    when {
      status == null -> "OFFLINE"
      !status.connected -> "OFFLINE"
      else -> status.state
    }
