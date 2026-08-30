package com.starwindow.app.ui.planning

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import com.starwindow.app.domain.NightOutlook
import com.starwindow.app.domain.NightVerdict
import com.starwindow.app.ui.components.ScreenHeader
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowSpacing
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

    state.openWatch?.let { watch ->
        WatchSheet(
            watch = watch,
            entry = state.watchlist.firstOrNull { it.watch.id == watch.id },
            notificationsAllowed = notificationsAllowed,
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onChange = viewModel::updateWatch,
            onOpenObject = {
                viewModel.closeWatch()
                onOpenObject(watch.objectId)
            },
            onRemove = { viewModel.removeWatch(watch) },
            onDismiss = viewModel::closeWatch,
        )
    }

    state.openSession?.let { session ->
        SessionSheet(
            session = session,
            zone = state.zone,
            outlook = state.outlookFor(session),
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
        ScreenHeader(
            title = "Kalender",
            subtitle = buildString {
                when {
                    state.upcoming.isEmpty() && state.watchlist.isEmpty() ->
                        append("noch nichts geplant")
                    state.upcoming.isEmpty() -> append("keine Nacht festgelegt")
                    state.upcoming.size == 1 -> append("eine Nacht geplant")
                    else -> append("${state.upcoming.size} Nächte geplant")
                }
                if (state.watchlist.isNotEmpty()) {
                    append(" · ${state.watchlist.size} vorgemerkt")
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = StarWindowSpacing.screen,
                end = StarWindowSpacing.screen,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(StarWindowSpacing.between),
        ) {
            state.next?.let { next ->
                item {
                    NextSessionCard(
                        session = next,
                        daysAway = state.daysUntilNext ?: 0L,
                        zone = state.zone,
                        outlook = state.outlookFor(next),
                        weatherNote = state.weatherNote,
                        onOpen = { viewModel.openSession(next) },
                        onShowPath = { onShowPath(next) },
                    )
                }
            }

            if (state.isEmpty) {
                item { EmptyHint() }
            }

            // Die Liste der gewählten Nacht stand einmal hier, über dem Raster. Sie steht jetzt als
            // Blatt an der Kachel selbst — siehe [DayPopup].
            items(state.months.size) { index ->
                MonthGrid(
                    month = state.months[index],
                    today = state.today,
                    selected = state.selectedDate,
                    onSelectDate = viewModel::selectDate,
                    outlookFor = state::outlookFor,
                    onOpenSession = { session ->
                        // Erst die Auswahl schließen, dann das Blatt öffnen: Sonst stünde das
                        // Popup noch offen, während darunter das Terminblatt hochfährt.
                        viewModel.selectDate(null)
                        viewModel.openSession(session)
                    },
                    onRemoveSession = viewModel::remove,
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
                        outlook = state.outlookFor(state.upcoming[index]),
                        onOpen = { viewModel.openSession(state.upcoming[index]) },
                        onRemove = { viewModel.remove(state.upcoming[index]) },
                    )
                }
            }

            if (state.watchlist.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            "Merkliste",
                            style = MaterialTheme.typography.titleSmall,
                            color = StarWindowColors.AnchorPoint,
                        )
                        Text(
                            text = "Kein Termin, sondern eine Bedingung: Die App meldet sich " +
                                "nachmittags, sobald eines dieser Ziele nachts gut steht und die " +
                                "Vorhersage mitspielt.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StarWindowColors.Muted,
                        )
                    }
                }
                items(state.watchlist.size) { index ->
                    WatchRow(
                        entry = state.watchlist[index],
                        onOpen = { viewModel.openWatch(state.watchlist[index].watch) },
                        onRemove = { viewModel.removeWatch(state.watchlist[index].watch) },
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
                        outlook = null,
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
        Text(
            text = "Wer sich nicht auf eine Nacht festlegen will, nimmt am selben Ort " +
                "**Bescheid geben**. Das Objekt kommt dann auf die Merkliste, und die App meldet " +
                "sich von selbst, sobald es abends hoch genug steht und die Vorhersage für die " +
                "Nacht mitspielt.",
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
    onSelectDate: (LocalDate?) -> Unit,
    outlookFor: (PlannedSession) -> NightOutlook?,
    onOpenSession: (PlannedSession) -> Unit,
    onRemoveSession: (PlannedSession) -> Unit,
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
                        val sessions = month.sessionsByDay[dayNumber].orEmpty()
                        DayCell(
                            date = date,
                            sessions = sessions,
                            isToday = date == today,
                            isSelected = date == selected,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        ) {
                            // Das Blatt hängt **in** der Kachel, damit es sich an ihr ausrichten
                            // kann. Ein Popup kennt nur die Grenzen des Elements, in dem es steht.
                            DayPopup(
                                date = date,
                                sessions = sessions,
                                outlookFor = outlookFor,
                                onOpenSession = onOpenSession,
                                onRemoveSession = onRemoveSession,
                                onDismiss = { onSelectDate(null) },
                            )
                        }
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
    popup: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isSelected -> StarWindowColors.NightSurfaceTop
                    sessions.isNotEmpty() -> StarWindowColors.WindowFill
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .then(
                if (isToday) {
                    Modifier.border(1.dp, StarWindowColors.AnchorPoint, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = sessions.isNotEmpty() || isToday, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            color = when {
                sessions.isNotEmpty() -> StarWindowColors.Starlight
                isToday -> StarWindowColors.AnchorPoint
                else -> StarWindowColors.Muted
            },
            fontWeight = if (sessions.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
        )

        // Die Zahl statt der Punktreihe, die hier stand. Punkte beantworten „ist etwas geplant",
        // aber nicht „wie viel" — und sobald an einem Abend zwei Ziele stehen, ist genau das die
        // Frage, wegen der man die Kachel antippt. Drei Punkte und vier Punkte unterscheidet auf
        // einer Kachel dieser Größe ohnehin niemand.
        if (sessions.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(1.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(StarWindowColors.WindowStroke),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${sessions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = StarWindowColors.Night,
                )
            }
        }

        if (isSelected) popup()
    }
}

/**
 * Was an diesem Abend geplant ist — dort, wo man hingetippt hat.
 *
 * Vorher sprang die Auswahl nach oben über das Raster: Man tippte auf den 14. November, und die
 * Antwort erschien drei Bildschirmhöhen weiter oben, außerhalb des Sichtfelds. Bei einem Kalender,
 * durch den man monatelang scrollt, ist das keine Kleinigkeit — der Finger bleibt am Tag, der Blick
 * muss ihn suchen. Ein Blatt am Tag selbst beantwortet die Frage dort, wo sie gestellt wurde.
 *
 * Es listet **alle** Termine dieses Abends, weil an einer Nacht mehr als ein Ziel hängen kann. Die
 * Auswahl eines davon öffnet dann das gewohnte Blatt mit Bahn und Erinnerung — das Popup entscheidet
 * nur, *welcher* Termin gemeint ist, und übernimmt nichts von dem, was dort schon steht.
 */
@Composable
private fun DayPopup(
    date: LocalDate,
    sessions: List<PlannedSession>,
    outlookFor: (PlannedSession) -> NightOutlook?,
    onOpenSession: (PlannedSession) -> Unit,
    onRemoveSession: (PlannedSession) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val spacing = with(density) { 6.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }

    Popup(
        popupPositionProvider = remember(spacing, margin) {
            DayPopupPositionProvider(spacingPx = spacing, marginPx = margin)
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = StarWindowColors.NightSurfaceHigh,
            border = BorderStroke(1.dp, StarWindowColors.Outline),
            shadowElevation = 12.dp,
            modifier = Modifier.widthIn(min = 220.dp, max = 300.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Nacht auf ${dateFormat.format(date.plusDays(1))}",
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.Starlight,
                )

                if (sessions.isEmpty()) {
                    Text(
                        text = "Für diesen Abend ist nichts geplant. Ein Ziel kommt über das " +
                            "Suchfeld und „Planung“ hierher.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                } else {
                    sessions.forEach { session ->
                        PopupSessionRow(
                            session = session,
                            outlook = outlookFor(session),
                            onOpen = { onOpenSession(session) },
                            onRemove = { onRemoveSession(session) },
                        )
                    }
                    Text(
                        text = if (sessions.size == 1) {
                            "Antippen öffnet Bahn und Erinnerung."
                        } else {
                            "${sessions.size} Ziele in dieser Nacht · antippen öffnet Bahn und Erinnerung."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                }
            }
        }
    }
}

/** Eine Zeile im Blatt: Name, die drei Zahlen der Nacht, das Wetterurteil und der Papierkorb. */
@Composable
private fun PopupSessionRow(
    session: PlannedSession,
    outlook: NightOutlook?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(StarWindowColors.NightSurface)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.objectLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (session.usableMinutes > 0) {
                        append("%.1f h".format(session.usableHours))
                        append(" · bis %.0f°".format(session.bestAltitudeDeg))
                        append(" · ")
                    }
                    append("Mond %.0f %%".format(session.moonIlluminationPercent))
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            if (outlook != null) ForecastLine(outlook, Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "„${session.objectLabel}“ aus dem Kalender nehmen",
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Wo das Blatt landet: unter der Kachel, notfalls darüber, nie über den Bildschirmrand hinaus.
 *
 * Die Zeile mit dem `coerceIn` ist der ganze Punkt: Eine Kachel am rechten Rand hätte ihr mittig
 * gesetztes Blatt sonst zur Hälfte außerhalb des Bildschirms, und der 31. eines Monats liegt oft
 * genug dort.
 */
private class DayPopupPositionProvider(
    private val spacingPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)

        val below = anchorBounds.bottom + spacingPx
        val y = if (below + popupContentSize.height + marginPx <= windowSize.height) {
            below
        } else {
            // Kein Platz darunter: über die Kachel, damit der angetippte Tag sichtbar bleibt.
            (anchorBounds.top - popupContentSize.height - spacingPx).coerceAtLeast(marginPx)
        }
        return IntOffset(x, y)
    }
}

/**
 * Die Wetterlage einer geplanten Nacht, als eine Zeile.
 *
 * Farbe **und** Wort, nicht nur Farbe: Ein grüner und ein roter Punkt sind für einen erheblichen
 * Teil der Leute derselbe Punkt, und das Urteil ist die Kernaussage der Zeile.
 */
@Composable
private fun ForecastLine(outlook: NightOutlook, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(verdictColor(outlook.verdict))
        )
        Text(
            text = "  " + outlook.headline,
            style = MaterialTheme.typography.labelSmall,
            color = verdictColor(outlook.verdict),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun verdictColor(verdict: NightVerdict) = when (verdict) {
    NightVerdict.GOOD -> StarWindowColors.WindowStroke
    NightVerdict.PARTLY -> StarWindowColors.AnchorPoint
    NightVerdict.POOR -> StarWindowColors.Crosshair
    NightVerdict.NO_DARKNESS, NightVerdict.UNKNOWN -> StarWindowColors.Muted
}

/**
 * Ein vorgemerktes Ziel.
 *
 * Gezeigt wird die Bedingung („ab 12. Oktober, dann 3,1 h") und nicht ein Datum: Ein Merklisteneintrag
 * ist kein Termin, und ihn wie einen aussehen zu lassen wäre das Versprechen einer Nacht, die
 * niemand zugesagt hat.
 */
@Composable
private fun WatchRow(
    entry: WatchEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StarWindowColors.NightSurface)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.NotificationsActive,
            contentDescription = null,
            tint = if (entry.hasOpportunity) StarWindowColors.WindowStroke else StarWindowColors.Muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.watch.label,
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = watchStatus(entry),
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Vormerkung entfernen",
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Ab wann sich das Warten lohnen könnte — ohne Wetter, das kommt am Tag selbst dazu. */
private fun watchStatus(entry: WatchEntry): String {
    val next = entry.nextNight ?: return "steht in den nächsten Monaten nicht hoch genug"
    val today = LocalDate.now()
    val days = next.toEpochDay() - today.toEpochDay()
    val hours = "%.1f h".format(Locale.GERMAN, entry.nextUsableHours)
    return when {
        days <= 0L -> "heute Nacht $hours über ${entry.watch.minAltitudeDeg.toInt()}°"
        days == 1L -> "morgen Nacht $hours über ${entry.watch.minAltitudeDeg.toInt()}°"
        else -> "ab ${dateFormat.format(next)} · $hours über ${entry.watch.minAltitudeDeg.toInt()}°"
    }
}

@Composable
private fun SessionRow(
    session: PlannedSession,
    outlook: NightOutlook?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
            if (outlook != null) ForecastLine(outlook, Modifier.padding(top = 2.dp))
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
    /** Die Vorhersage für diese Nacht, wenn sie in Reichweite liegt. */
    outlook: NightOutlook?,
    /** Warum keine dasteht, falls keine dasteht. */
    weatherNote: String?,
    onOpen: () -> Unit,
    onShowPath: () -> Unit,
) {
    val reminder = session.nextReminder(zone)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
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
        when {
            outlook != null -> {
                ForecastLine(outlook)
                Text(
                    text = outlook.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }
            weatherNote != null -> Text(
                text = weatherNote,
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            // Ein Termin jenseits der Vorhersage bekommt keine Zeile. Eine Kachel „keine Daten"
            // stünde dort dann bis zu elf Monate lang und sagte in keinem davon etwas.
        }
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
