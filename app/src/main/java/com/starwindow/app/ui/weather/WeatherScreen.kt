package com.starwindow.app.ui.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.data.weather.OpenMeteo
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.domain.AstroHour
import com.starwindow.app.domain.AstroNight
import com.starwindow.app.domain.AstroWeather
import com.starwindow.app.domain.BortleScale
import com.starwindow.app.domain.NightVerdict
import com.starwindow.app.ui.components.SectionCard
import com.starwindow.app.ui.components.SectionHeader
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowSpacing
import com.starwindow.app.ui.windows.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wetter für die Nacht.
 *
 * Der Aufbau folgt der Frage, mit der man die Ansicht öffnet, und zwar in dieser Reihenfolge:
 * **Lohnt sich heute Abend?** — Urteil und Kurve ganz oben. **Woran liegt es?** — die Zahlen
 * darunter, jede mit ihrer Einheit und ohne Umrechnung im Kopf. **Und wann sonst?** — die Liste der
 * nächsten Nächte, zunächst nur mit Tag, Datum und Haken, aufklappbar zu derselben Kurve und
 * denselben Zahlen.
 *
 * Die Liste zeigt bewusst erst wenig: Fünfzehn vollständige Nachtberichte übereinander wären
 * unlesbar, während „Do 28.08. ✓" in einer halben Sekunde die Frage beantwortet, an welchem Abend
 * man sich den Wecker stellt.
 */
@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var bortleDialogVisible by remember { mutableStateOf(false) }
    var modelDialogVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        PlaceBar(
            query = state.query,
            place = state.place,
            onQueryChange = viewModel::setQuery,
            onClear = viewModel::clearQuery,
            onUseCurrentLocation = {
                keyboard?.hide()
                viewModel.useCurrentLocation()
            },
            onRefresh = {
                keyboard?.hide()
                viewModel.refresh()
            },
        )

        // Nur einblenden, solange nicht gerade nach einem Ort gesucht wird: Während die Trefferliste
        // steht, gilt die Modellzeile noch für den alten Ort und wäre irreführend.
        if (!state.hasSuggestions && !state.isSearching) {
            ModelRow(
                state = state,
                onSelect = {
                    keyboard?.hide()
                    viewModel.selectModel(it)
                },
                onOpenDetails = {
                    keyboard?.hide()
                    modelDialogVisible = true
                },
            )
            Spacer(Modifier.height(4.dp))
        }

        when {
            state.hasSuggestions || state.isSearching -> SuggestionList(
                suggestions = state.suggestions,
                isSearching = state.isSearching,
                onSelect = {
                    keyboard?.hide()
                    viewModel.selectPlace(it)
                },
            )

            state.isLoading && state.nights.isEmpty() -> Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.nights.isEmpty() -> EmptyState(state.error)

            else -> NightList(
                state = state,
                onToggleDay = viewModel::toggleDay,
                onEditBortle = { bortleDialogVisible = true },
            )
        }
    }

    if (modelDialogVisible) {
        ModelDialog(
            state = state,
            onSelect = {
                viewModel.selectModel(it)
                modelDialogVisible = false
            },
            onDismiss = { modelDialogVisible = false },
        )
    }

    if (bortleDialogVisible) {
        BortleDialog(
            place = state.place,
            selected = state.bortleLevel,
            isManual = state.bortleIsManual,
            onSelect = {
                viewModel.setBortleOverride(it)
                bortleDialogVisible = false
            },
            onDismiss = { bortleDialogVisible = false },
        )
    }
}

/** Ort eingeben, eigenen Standort nehmen, neu laden — drei Wege zum selben Ziel, in einer Zeile. */
@Composable
private fun PlaceBar(
    query: String,
    place: WeatherPlace?,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = StarWindowSpacing.screen,
            end = 4.dp,
            top = 6.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            // Der gewählte Ort steht als Platzhalter im Feld: Er sagt, wofür die Zahlen unten
            // gelten, und verschwindet von selbst, sobald jemand einen neuen eintippt.
            placeholder = {
                Text(text = place?.label ?: "Ort eingeben, z. B. Davos", maxLines = 1)
            },
            leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StarWindowColors.NightSurface,
                unfocusedContainerColor = StarWindowColors.NightSurface,
            ),
        )
        IconButton(onClick = onUseCurrentLocation) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Eigenen Standort verwenden")
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Vorhersage neu laden")
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: List<WeatherPlace>,
    isSearching: Boolean,
    onSelect: (WeatherPlace) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (isSearching && suggestions.isEmpty()) {
            item {
                Text(
                    "Orte werden gesucht …",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(suggestions, key = { "${it.name}_${it.latitudeDeg}_${it.longitudeDeg}" }) { place ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(place) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(place.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = buildString {
                        append("%.3f°, %.3f°".format(place.latitudeDeg, place.longitudeDeg))
                        place.elevationM.takeIf { it > 0.5 }
                            ?.let { append(" · ${it.roundToInt()} m") }
                        place.population?.let { append(" · ${formatPopulation(it)} Einw.") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }
            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)
        }
    }
}

@Composable
private fun EmptyState(error: String?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.WbTwilight,
            contentDescription = null,
            tint = StarWindowColors.Muted,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = error ?: "Noch keine Vorhersage – bitte oben einen Ort eingeben.",
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NightList(
    state: WeatherUiState,
    onToggleDay: (LocalDate) -> Unit,
    onEditBortle: () -> Unit,
) {
    val tonight = state.tonight ?: return

    // Die heutige Nacht als eine Karte, jede weitere als eigene. Vorher lief alles als eine
    // durchgehende Spalte mit Trennlinien, und die Frage „gehört das Diagramm noch zu heute Abend
    // oder schon zur Liste darunter" ließ sich nur durch Lesen beantworten.
    LazyColumn(
        contentPadding = PaddingValues(
            start = StarWindowSpacing.screen,
            end = StarWindowSpacing.screen,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(StarWindowSpacing.between),
    ) {
        // Erst die Antwort, dann die Begründung: Bewertung, Mond und Dämmerungsband oben, die
        // Kurve und die Zahlen darunter für alle, die der Bewertung nicht glauben.
        item(key = "rating") {
            NightRatingCard(
                night = tonight,
                bortleLevel = state.bortleLevel,
                bortleIsManual = state.bortleIsManual,
                onEditBortle = onEditBortle,
            )
        }

        item(key = "tonight") {
            SectionCard(contentPadding = 0.dp) {
                AstroWeatherChart(
                    night = tonight,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
                )
                NightDetails(
                    night = tonight,
                    place = state.place,
                    bortleLevel = state.bortleLevel,
                    bortleIsManual = state.bortleIsManual,
                    onEditBortle = onEditBortle,
                    modifier = Modifier.padding(
                        start = StarWindowSpacing.card,
                        end = StarWindowSpacing.card,
                        bottom = StarWindowSpacing.card,
                    ),
                )
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming_title") {
                SectionHeader(
                    title = "Die nächsten Nächte",
                    subtitle = "Antippen öffnet Diagramm und Zahlen",
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        items(state.upcoming, key = { it.date.toString() }) { night ->
            val expanded = state.expandedDate == night.date
            SectionCard(
                contentPadding = 0.dp,
                color = if (expanded) {
                    StarWindowColors.NightSurfaceHigh
                } else {
                    StarWindowColors.NightSurface
                },
                onClick = { onToggleDay(night.date) },
            ) {
                NightRow(night = night, expanded = expanded)
                AnimatedVisibility(visible = expanded) {
                    Column {
                        AstroWeatherChart(
                            night = night,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        NightDetails(
                            night = night,
                            place = state.place,
                            bortleLevel = state.bortleLevel,
                            bortleIsManual = state.bortleIsManual,
                            onEditBortle = onEditBortle,
                            modifier = Modifier.padding(
                                start = StarWindowSpacing.card,
                                end = StarWindowSpacing.card,
                                bottom = StarWindowSpacing.card,
                            ),
                        )
                    }
                }
            }
        }

        item(key = "source") {
            Footer(state)
        }
    }
}

/**
 * Die Überschrift: welcher Abend, und in einem Satz, was daraus wird.
 *
 * „Heute Abend" stimmt nur, solange es noch derselbe Tag ist. Wer um zwei Uhr nachts hinschaut,
 * bekommt dieselbe Nacht zu sehen — angeschrieben als „Diese Nacht", weil ihr Abend gestern war.
 */
@Composable
private fun NightHeadline(night: AstroNight) {
    val today = LocalDate.now(night.zone)
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = StarWindowSpacing.card,
            end = StarWindowSpacing.card,
            top = StarWindowSpacing.card,
            bottom = 4.dp,
        )
    ) {
        Text(
            text = if (night.date == today) "Heute Abend" else "Diese Nacht",
            style = MaterialTheme.typography.labelLarge,
            color = StarWindowColors.WindowStroke,
        )
        Text(
            text = headlineFormatter.format(night.date),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VerdictIcon(night.verdict, size = 20)
            Spacer(Modifier.width(8.dp))
            Text(
                text = summaryOf(night),
                style = MaterialTheme.typography.bodyMedium,
                color = verdictColor(night.verdict),
            )
        }
    }
}

/**
 * Eine Zeile der Nächteliste.
 *
 * Wochentag, Datum, Haken — mehr steht hier nicht, weil mehr die Übersicht kostet, um derentwillen
 * die Liste existiert. Der Rest kommt beim Antippen.
 */
@Composable
private fun NightRow(night: AstroNight, expanded: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StarWindowSpacing.card, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = weekdayFormatter.format(night.date),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(46.dp),
        )
        Text(
            text = dateFormatter.format(night.date),
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Muted,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortSummaryOf(night),
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Starlight,
            )
            night.moonIllumination?.let {
                Text(
                    text = "Mond ${it.percent.roundToInt()} % · ${it.phaseName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }
        }
        VerdictIcon(night.verdict, size = 24)
    }
}

@Composable
private fun VerdictIcon(verdict: NightVerdict, size: Int) {
    val (icon, tint) = when (verdict) {
        NightVerdict.GOOD -> Icons.Filled.CheckCircle to StarWindowColors.WindowStroke
        NightVerdict.PARTLY -> Icons.Outlined.CheckCircle to StarWindowColors.AnchorPoint
        NightVerdict.POOR -> Icons.Outlined.Cancel to StarWindowColors.Muted
        NightVerdict.NO_DARKNESS -> Icons.Filled.WbTwilight to StarWindowColors.Muted
        NightVerdict.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline to StarWindowColors.Muted
    }
    Icon(
        imageVector = icon,
        contentDescription = verdict.label,
        tint = tint,
        modifier = Modifier.size(size.dp),
    )
}

private fun verdictColor(verdict: NightVerdict): Color = when (verdict) {
    NightVerdict.GOOD -> StarWindowColors.WindowStroke
    NightVerdict.PARTLY -> StarWindowColors.AnchorPoint
    else -> StarWindowColors.Muted
}

/** Die Zahlen zur Nacht, gruppiert nach dem, was sie beantworten. */
@Composable
private fun NightDetails(
    night: AstroNight,
    place: WeatherPlace?,
    bortleLevel: Int?,
    bortleIsManual: Boolean,
    onEditBortle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = night.zone
    val best = night.bestHour

    Column(modifier = modifier.fillMaxWidth()) {
        GroupTitle("Dunkelheit")
        DataRow("Sonnenuntergang", clock(night.times.sunsetMillis, zone))
        DataRow(
            label = "Astronomisch dunkel",
            value = if (night.times.darkDurationMillis > 0) {
                "${clock(night.times.astronomicalDuskMillis, zone)} – " +
                    clock(night.times.astronomicalDawnMillis, zone)
            } else {
                "in dieser Nacht nicht"
            },
            hint = if (night.times.darkDurationMillis > 0) {
                formatDuration(night.times.darkDurationMillis)
            } else {
                "die Sonne bleibt über -18°, tiefster Stand %.0f°".format(night.times.lowestSunAltitudeDeg)
            },
        )
        DataRow("Sonnenaufgang", clock(night.times.sunriseMillis, zone))
        DataRow(
            label = "Klar und dunkel",
            value = if (night.clearDarkMillis > 0) formatDuration(night.clearDarkMillis) else "keine Stunde",
            hint = "höchstens ${AstroWeather.CLEAR_CLOUD_PERCENT.roundToInt()} % Bewölkung, kein Niederschlag",
        )
        // Der Haken oben urteilt nur über die Wolken. Diese Zeile rechnet den Mond mit ein und
        // erklärt damit, warum eine völlig klare Vollmondnacht trotzdem wenig hergibt.
        DataRow(
            label = "Davon brauchbar",
            value = if (night.usableDarkMillis > 0) {
                formatDuration(night.usableDarkMillis)
            } else {
                "keine Stunde"
            },
            hint = "mindestens ${AstroNight.USABLE_SCORE} % Eignung, Mondlicht eingerechnet",
        )

        GroupTitle("Mond")
        night.moonIllumination?.let { moon ->
            DataRow("Phase", moon.phaseName, "${moon.percent.roundToInt()} % beleuchtet")
        }
        DataRow("Aufgang", clock(night.times.moonriseMillis, zone))
        DataRow("Untergang", clock(night.times.moonsetMillis, zone))
        DataRow(
            label = "Höchster Stand",
            value = night.maxMoonAltitudeDeg?.let { "%.0f° über dem Horizont".format(it) }
                ?: "bleibt während der Dunkelheit unten",
            hint = if (night.maxMoonAltitudeDeg != null) "während der astronomischen Dunkelheit" else null,
        )

        GroupTitle("Bewölkung")
        DataRow(
            label = "Während der Dunkelheit",
            value = night.meanCloudDarkPercent?.let { "im Mittel ${it.roundToInt()} %" } ?: "keine Angabe",
            hint = night.minCloudDarkPercent?.let { "bestenfalls ${it.roundToInt()} %" },
        )
        if (best != null) {
            DataRow(
                label = "Schichten",
                value = cloudLayers(best),
                hint = "um ${clock(best.millis, zone)}, der besten Stunde",
            )
        }

        if (best != null) {
            GroupTitle("Luft um ${clock(best.millis, zone)}")
            DataRow("Temperatur", best.weather.temperatureC?.let { "%.1f °C".format(it) })
            DataRow(
                label = "Taupunkt",
                value = best.weather.dewPointC?.let { "%.1f °C".format(it) },
                hint = best.weather.dewSpreadK?.let { spread ->
                    val warning = if (spread < 2.0) " – Taubeschlag erwarten" else ""
                    "%.1f K Abstand%s".format(spread, warning)
                },
            )
            DataRow("Luftfeuchte", best.weather.humidityPercent?.let { "${it.roundToInt()} %" })
            DataRow(
                label = "Wind",
                value = best.weather.windSpeedKmh?.let { "${it.roundToInt()} km/h" },
                hint = best.weather.windGustsKmh?.let { "Böen ${it.roundToInt()} km/h" },
            )
            DataRow("Luftdruck", best.weather.pressureHpa?.let { "${it.roundToInt()} hPa" })
            DataRow(
                label = "Höhenwind",
                value = best.weather.jetStreamKmh?.let { "${it.roundToInt()} km/h auf 250 hPa" },
                hint = best.weather.jetStreamKmh?.let { seeingHint(it) },
            )
            DataRow(
                label = "Begrenzt durch",
                value = best.limiter.label,
                hint = "beste Stunde erreicht ${best.score} %",
            )
        }

        GroupTitle("Ort")
        BortleRow(
            level = bortleLevel,
            isManual = bortleIsManual,
            place = place,
            onClick = onEditBortle,
        )
        place?.let {
            DataRow(
                label = "Position",
                value = "%.4f°, %.4f°".format(it.latitudeDeg, it.longitudeDeg),
                hint = it.elevationM.takeIf { m -> m > 0.5 }?.let { m -> "${m.roundToInt()} m über dem Meer" },
            )
        }
    }
}

/**
 * Die Bortle-Stufe.
 *
 * Antippbar, und der Zusatz sagt immer, woher der Wert kommt. Eine geschätzte Zahl, die sich nicht
 * als Schätzung zu erkennen gibt, ist schlimmer als gar keine.
 */
@Composable
private fun BortleRow(
    level: Int?,
    isManual: Boolean,
    place: WeatherPlace?,
    onClick: () -> Unit,
) {
    val bortle = level?.let { BortleScale.forLevel(it) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onClick,
                label = {
                    Text(
                        text = if (bortle == null) "Bortle-Stufe setzen" else "Bortle ${bortle.level}",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.NightlightRound,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = StarWindowColors.NightSurfaceHigh,
                    labelColor = StarWindowColors.Starlight,
                    leadingIconContentColor = StarWindowColors.AnchorPoint,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    bortle == null -> "unbekannt"
                    isManual -> "selbst gesetzt"
                    else -> "geschätzt"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        if (bortle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${bortle.title} · ${bortle.consequence}",
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Starlight,
            )
            Text(
                text = if (isManual) {
                    "Antippen, um die Stufe zu ändern."
                } else {
                    val from = place?.population?.let { "aus ${formatPopulation(it)} Einwohnern" } ?: ""
                    "Schätzung $from – kein Lichtatlas. Antippen zum Korrigieren."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

@Composable
private fun BortleDialog(
    place: WeatherPlace?,
    selected: Int?,
    isManual: Boolean,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StarWindowColors.NightSurface,
        title = { Text("Bortle-Stufe für ${place?.name ?: "diesen Ort"}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Wie dunkel es an diesem Ort wirklich ist, weiß nur, wer dort war. " +
                        "Die Vorauswahl kommt aus Einwohnerzahl und Entfernung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                BortleScale.CLASSES.forEach { bortle ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(bortle.level) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${bortle.level}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (bortle.level == selected) {
                                StarWindowColors.WindowStroke
                            } else {
                                StarWindowColors.Muted
                            },
                            modifier = Modifier.width(24.dp),
                        )
                        Column {
                            Text(bortle.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${bortle.sky} Grenzgröße ${bortle.nakedEyeLimit}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StarWindowColors.Muted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = {
            if (isManual) {
                TextButton(onClick = { onSelect(null) }) { Text("Schätzung verwenden") }
            }
        },
    )
}

@Composable
private fun Footer(state: WeatherUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            text = "Vorhersage: ${state.model.provider} ${state.model.label} " +
                "(${state.model.resolutionLabel}) über ${OpenMeteo.SOURCE_LABEL}",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
        // Zwei verschiedene Zeitpunkte, und die Unterscheidung ist keine Spitzfindigkeit: Der Lauf
        // kann Stunden alt sein, während die App ihn vor einer Minute geholt hat.
        state.selectedStatus?.availableSinceMillis?.let {
            Text(
                text = "Modelllauf veröffentlicht ${clock(it, state.zone)} · ${formatAge(System.currentTimeMillis() - it)}",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        state.fetchedAtMillis?.let {
            Text(
                text = "Abgerufen ${clock(it, state.zone)} · Zeiten in ${state.zone.id}",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Crosshair,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Surface(color = StarWindowColors.Night) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = StarWindowColors.WindowStroke,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = StarWindowColors.WindowStroke,
    )
    Spacer(Modifier.height(2.dp))
}

/** Wie [com.starwindow.app.ui.windows.ObjectInfoSheet]: Beschriftung links, Wert rechts, Zusatz darunter. */
@Composable
private fun DataRow(label: String, value: String?, hint: String? = null) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = StarWindowColors.Muted,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Column {
            Text(value, style = MaterialTheme.typography.bodySmall)
            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = StarWindowColors.Muted)
            }
        }
    }
}

/**
 * Ein Satz statt einer Zahl: das Urteil, mit dem Grund dahinter.
 *
 * Der Haken urteilt über die Wolken — so war die Frage gestellt, und so ist sie auch richtig
 * gestellt, denn das Wetter ist das Einzige, was sich nicht vorher ausrechnen lässt. Der Mond
 * lässt sich ausrechnen, und deswegen steht er hier im Satz: Eine wolkenlose Vollmondnacht
 * bekommt ihren Haken und dazu die Warnung, dass Deep Sky daraus trotzdem nichts wird.
 */
internal fun summaryOf(night: AstroNight): String = when (night.verdict) {
    NightVerdict.GOOD -> if (night.moonSpoilsTheNight) {
        "${formatDuration(night.clearDarkMillis)} klar – aber der Mond hellt die ganze Nacht auf."
    } else {
        "${formatDuration(night.clearDarkMillis)} klare Dunkelheit – es lohnt sich."
    }
    NightVerdict.PARTLY -> if (night.clearDarkMillis > 0) {
        "Nur ${formatDuration(night.clearDarkMillis)} klar – Lücken abpassen."
    } else {
        "Durchweg bewölkt, aber mit Lücken – Lücken abpassen."
    }
    NightVerdict.POOR -> {
        val cloud = night.meanCloudDarkPercent?.roundToInt()
        if (cloud != null) "Bedeckt ($cloud % im Mittel) – kein Aufbau." else "Kein brauchbares Fenster."
    }
    NightVerdict.NO_DARKNESS ->
        "Keine astronomische Dunkelheit – die Sonne bleibt zu hoch."
    NightVerdict.UNKNOWN -> "Für diese Nacht liegen keine Daten vor."
}

/** Dieselbe Aussage in Listenlänge. */
private fun shortSummaryOf(night: AstroNight): String = when (night.verdict) {
    NightVerdict.GOOD -> if (night.moonSpoilsTheNight) {
        "${formatDuration(night.clearDarkMillis)} klar, aber Mond"
    } else {
        "${formatDuration(night.clearDarkMillis)} klar und dunkel"
    }
    NightVerdict.PARTLY -> if (night.clearDarkMillis > 0) {
        "${formatDuration(night.clearDarkMillis)} klar"
    } else {
        "wechselnd bewölkt"
    }
    NightVerdict.POOR -> night.meanCloudDarkPercent
        ?.let { "${it.roundToInt()} % bewölkt" }
        ?: "ungeeignet"
    NightVerdict.NO_DARKNESS -> "wird nicht richtig dunkel"
    NightVerdict.UNKNOWN -> "keine Daten"
}

private fun cloudLayers(hour: AstroHour): String {
    val parts = listOfNotNull(
        hour.weather.cloudLowPercent?.let { "tief ${it.roundToInt()} %" },
        hour.weather.cloudMidPercent?.let { "mittel ${it.roundToInt()} %" },
        hour.weather.cloudHighPercent?.let { "hoch ${it.roundToInt()} %" },
    )
    return if (parts.isEmpty()) "keine Schichtangabe" else parts.joinToString(" · ")
}

/**
 * Der Höhenwind als Anhaltspunkt fürs Seeing.
 *
 * Kein Modell rechnet Seeing direkt; der Jetstream über dem Standort ist der Hinweis, der frei
 * verfügbar ist, und darf auch nur als solcher auftreten.
 */
private fun seeingHint(jetStreamKmh: Double): String = when {
    jetStreamKmh < 60 -> "ruhige Höhenströmung, Seeing eher gut"
    jetStreamKmh < 120 -> "mäßige Höhenströmung"
    else -> "Jetstream über dem Ort, unruhige Sterne erwarten"
}

private fun formatPopulation(population: Int): String =
    "%,d".format(Locale.GERMAN, population)

private fun clock(millis: Long?, zone: ZoneId): String? =
    millis?.let { clockFormatter.withZone(zone).format(Instant.ofEpochMilli(it)) }

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val headlineFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val weekdayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE", Locale.GERMAN)
private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)
