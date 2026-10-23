package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

private val UTC_ZONE = ZoneId.of("UTC")
private val CHICAGO_ZONE = ZoneId.of("America/Chicago")

/** "Thu, Oct 22 at 3:42 PM CT" — display-ready Central time for notification payloads. */
private val CENTRAL_FORMATTER =
    java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a 'CT'", Locale.US)

/** Formats a UTC database timestamp for display in a notification payload (America/Chicago). */
fun formatCentralTime(utcTimestamp: LocalDateTime): String =
    CENTRAL_FORMATTER.format(utcTimestamp.atZone(UTC_ZONE).withZoneSameInstant(CHICAGO_ZONE))
