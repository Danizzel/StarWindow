package com.starwindow.app.ui.windows

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun formatTimestamp(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatClock(epochMillis: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(epochMillis))

/** `2 h 07 min`, `41 min`, `38 s` — whatever fits the magnitude of the duration. */
fun formatDuration(millis: Long): String {
    val duration = Duration.ofMillis(millis)
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    val seconds = duration.seconds % 60
    return when {
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        minutes > 0 -> "%d min".format(minutes)
        else -> "%d s".format(seconds)
    }
}
