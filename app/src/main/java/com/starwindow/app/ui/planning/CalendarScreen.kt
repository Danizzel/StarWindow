package com.starwindow.app.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The planned nights, as a year.
 *
 * The point of a calendar here rather than a list: astrophotography is seasonal, and a season is a
 * shape. Which weeks are full and which are empty, whether two targets collide on the same night,
 * how long until the next opportunity — those are questions about *layout*, and a list answers none
 * of them. The list underneath answers the other question ("what is next"), which a grid answers
 * badly.
 *
 * A dot on a day means a night is planned; a night belongs to the evening it starts on.
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpenObject: (String) -> Unit,
    onShowPath: (PlannedSession) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 13 onwards a reminder that was never permitted simply never arrives. Asking at the
    // moment the first one is switched on is both the honest and the well-timed place: the user has
    // just said what they want the notification for.
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsAllowed = granted }

    state.openSession?.let { session ->
        SessionSheet(
            session = session,
            zone = state.zone,
            notificationsAllowed = notificationsAllowed,
            onToggleReminder = { lead ->
                if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.toggleReminder(lead)
            },
            onNoteChange = viewModel::setNote,
            onShowPath = {
                viewModel.closeSession()
                onShowPath(session)
            },
            onRemove = { viewModel.remove(session) },
            onDismiss = viewModel::closeSession,
        )
    }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Kalender", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (state.upcoming.isEmpty()) "noch nichts geplant"
                    else "${state.upcoming.size} Nächte geplant",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.Muted,
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.next?.let { next ->
                item {
                    NextSessionCard(
                        session = next,
                        daysAway = state.daysUntilNext ?: 0L,
                        zone = state.zone,
                        onOpen = { viewModel.openSession(next) },
                        onShowPath = { onShowPath(next) },
                    )
                }
            }

            if (state.isEmpty) {
                item { EmptyHint() }
            }

            state.selectedSessions.takeIf { it.isNotEmpty() }?.let { sessions ->
                item {
                    Text(
                        text = "Nacht auf ${dateFormat.format(requireNotNull(state.selectedDate).plusDays(1))}",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarWindowColors.AnchorPoint,
                    )
                }
                items(sessions.size) { index ->
                    SessionRow(
                        session = sessions[index],
                        onOpen = { viewModel.openSession(sessions[index]) },
                        onRemove = { viewModel.remove(sessions[index]) },
                    )
                }
            }

            items(state.months.size) { index ->
                MonthGrid(
                    month = state.months[index],
                    today = state.today,
                    selected = state.selectedDate,
                    onSelectDate = viewModel::selectDate,
                )
            }

            if (state.upcoming.isNotEmpty()) {
                item {
                    Text(
                        "Als Nächstes",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarWindowColors.AnchorPoint,
                    )
                }
                items(state.upcoming.size) { index ->
                    SessionRow(
                        session = state.upcoming[index],
                        onOpen = { viewModel.openSession(state.upcoming[index]) },
                        onRemove = { viewModel.remove(state.upcoming[index]) },
                    )
                }
            }

            if (state.past.isNotEmpty()) {
                item {
                    Text(
                        "Vorbei",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarWindowColors.Muted,
                    )
                }
                items(state.past.size) { index ->
                    SessionRow(
                        session = state.past[index],
                        onOpen = { onOpenObject(state.past[index].objectId) },
                        onRemove = { viewModel.remove(state.past[index]) },
                        dimmed = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StarWindowColors.NightSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Noch keine Nacht vorgemerkt.",
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Starlight,
        )
        Text(
            "Ein Objekt über das Suchfeld heraussuchen, antippen und dort unten auf " +
                "**Planung** gehen. Die App rechnet dann für das ganze Jahr aus, in welchen " +
                "Nächten es hoch genug steht und der Himmel gleichzeitig dunkel genug ist – " +
                "und die besten davon lassen sich hier vormerken.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

/**
 * One month as a seven-column grid.
 *
 * Weeks start on Monday, and days before the first fall into blanks — without that the columns stop
 * meaning weekdays, which is most of what a calendar is for. Clear nights are usually weekends for
 * anyone with a job, so the column matters.
 */
@Composable
private fun MonthGrid(
    month: CalendarMonth,
    today: LocalDate,
    selected: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
) {
    val first = month.yearMonth.atDay(1)
    val blanks = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val days = month.yearMonth.lengthOfMonth()
    val rows = ((blanks + days) + 6) / 7

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StarWindowColors.NightSurface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = month.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.GERMAN) +
                    " " + month.yearMonth.year,
                style = MaterialTheme.typography.titleSmall,
                color = StarWindowColors.Starlight,
                modifier = Modifier.weight(1f),
            )
            if (month.total > 0) {
                Text(
                    text = "${month.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.WindowStroke,
                )
            }
        }

        Row {
            DayOfWeek.entries.forEach { day ->
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        repeat(rows) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(7) { column ->
                    val dayNumber = row * 7 + column - blanks + 1
                    if (dayNumber < 1 || dayNumber > days) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.yearMonth.atDay(dayNumber)
                        DayCell(
                            date = date,
                            sessions = month.sessionsByDay[dayNumber].orEmpty(),
                            isToday = date == today,
                            isSelected = date == selected,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    sessions: List<PlannedSession>,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isSelected -> StarWindowColors.NightSurfaceHigh
                    sessions.isNotEmpty() -> StarWindowColors.WindowFill
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .then(
                if (isToday) {
                    Modifier.border(1.dp, StarWindowColors.AnchorPoint, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = sessions.isNotEmpty() || isToday, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${date.dayOfMonth}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    sessions.isNotEmpty() -> StarWindowColors.Starlight
                    isToday -> StarWindowColors.AnchorPoint
                    else -> StarWindowColors.Muted
                },
                fontWeight = if (sessions.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
            )
            if (sessions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    // One dot per session, capped: three targets in one night is already ambitious.
                    repeat(sessions.size.coerceAtMost(3)) {
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(StarWindowColors.WindowStroke)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: PlannedSession,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(StarWindowColors.NightSurface)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.objectLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) StarWindowColors.Muted else StarWindowColors.Starlight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("Nacht auf ").append(dateFormat.format(session.date.plusDays(1)))
                    if (session.usableMinutes > 0) {
                        append(" · %.1f h".format(session.usableHours))
                        append(" · bis %.0f°".format(session.bestAltitudeDeg))
                    }
                    append(" · Mond %.0f %%".format(session.moonIlluminationPercent))
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Termin entfernen",
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMAN)

private fun DateTimeFormatter.format(date: LocalDate): String = date.format(this)

/**
 * The next planned night, at the very top.
 *
 * A grid is good at showing a season and bad at answering "what is next" — the one question a
 * planning calendar gets asked most. Finding today's row means scrolling, and by October the answer
 * may be three screens down. So the answer is lifted out and stated as a countdown, with the two
 * things worth doing about it: open it, or go outside and check the path against the roofline.
 */
@Composable
private fun NextSessionCard(
    session: PlannedSession,
    daysAway: Long,
    zone: ZoneId,
    onOpen: () -> Unit,
    onShowPath: () -> Unit,
) {
    val reminder = session.nextReminder(zone)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StarWindowColors.NightSurfaceHigh)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = countdown(daysAway),
            style = MaterialTheme.typography.labelMedium,
            color = StarWindowColors.AnchorPoint,
        )
        Text(
            text = session.objectLabel,
            style = MaterialTheme.typography.titleMedium,
            color = StarWindowColors.Starlight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("Nacht auf ").append(dateFormat.format(session.date.plusDays(1)))
                if (session.usableMinutes > 0) {
                    append(" · %.1f h belichtbar".format(session.usableHours))
                    append(" · bis %.0f°".format(session.bestAltitudeDeg))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
        if (session.hasNote) {
            Text(
                text = session.note,
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Starlight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (reminder != null) {
                    Icons.Filled.NotificationsActive
                } else {
                    Icons.Outlined.NotificationsNone
                },
                contentDescription = null,
                tint = if (reminder != null) StarWindowColors.WindowStroke else StarWindowColors.Muted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "  " + (reminder?.label ?: "keine Erinnerung"),
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowPath, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Pfad", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "Heute Nacht", "Morgen Nacht", "In 12 Tagen" — the unit people actually think in. */
private fun countdown(daysAway: Long): String = when {
    daysAway <= 0L -> "Heute Nacht"
    daysAway == 1L -> "Morgen Nacht"
    daysAway < 7L -> "In $daysAway Tagen"
    daysAway < 14L -> "In einer Woche"
    daysAway < 62L -> "In ${daysAway / 7} Wochen"
    else -> "In ${daysAway / 30} Monaten"
}


/** Whether the app may post notifications at all; always true below Android 13. */
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
