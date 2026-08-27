package com.starwindow.app.ui.search

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.domain.ObjectSort
import com.starwindow.app.domain.TonightEntry
import com.starwindow.app.ui.components.ObjectRow
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.windows.formatClock

/**
 * Finding something to point at.
 *
 * The screen has two modes, and which one it is in follows from whether the user has asked the
 * catalogue a question yet.
 *
 * **Nothing typed, nothing filtered** — it shows tonight, not the catalogue: three short sections
 * for what is in a saved window right now, what stands high, and what is still coming up. This
 * replaced a flat ranked list, which stopped working the moment the catalogue grew past a few
 * thousand entries: the old ranking rewarded brightness, and nine thousand stars are brighter than
 * almost every deep sky object, so "what should I look at tonight" quietly filled with stars.
 *
 * **Anything typed or filtered** — it shows a result list, with the verdict for tonight in the
 * right-hand column so a long list can be skimmed down its edge instead of read row by row.
 *
 * The controls stay at one row: the filters that are switched on, as chips that can be tapped off,
 * and a button that opens the rest in a sheet. Two permanent chip rows would cost a fifth of the
 * screen for choices most people make once.
 */
@Composable
fun ObjectSearchScreen(
    viewModel: ObjectSearchViewModel,
    onOpenObject: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        SearchField(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            onClear = viewModel::clearQuery,
            onBack = onBack,
            onSearch = { keyboard?.hide() },
            focusRequester = focusRequester,
        )

        FilterRow(
            state = state,
            onOpenSheet = viewModel::openFilterSheet,
            onClearField = viewModel::clearFilterField,
            onClearAll = viewModel::clearAllFilters,
        )

        // Sorting only means something once there is a list to sort.
        if (!state.showsBoard) {
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ObjectSort.entries.toList(), key = { it.name }) { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sort.label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ResultHeader(state)
        HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

        when {
            state.isComputingWindow -> WindowSearchRunning(state)
            state.isEmpty -> EmptyState(state)
            state.showsBoard -> TonightBoardList(state, onOpenObject) { keyboard?.hide() }
            else -> ResultList(state, onOpenObject) { keyboard?.hide() }
        }
    }

    if (state.filterSheetOpen) {
        CatalogFilterSheet(
            filter = state.filter,
            constellations = state.constellations,
            windows = state.windows,
            conditions = state.conditions,
            onChange = viewModel::setFilter,
            onDismiss = viewModel::closeFilterSheet,
        )
    }

    // The field takes focus straight away only when there is nothing to read yet. Once the board
    // has something to offer, throwing up the keyboard would cover the very thing it offers.
    LaunchedEffect(state.showsBoard, state.board.isEmpty()) {
        if (state.board.isEmpty()) focusRequester.requestFocus()
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Name oder Katalognummer, z. B. M 42") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StarWindowColors.NightSurface,
                unfocusedContainerColor = StarWindowColors.NightSurface,
            ),
        )
    }
}

/**
 * The one row of controls: what is switched on, and the way to switch more on.
 *
 * Active filters are chips with an × because a filter that cannot be seen is worse than no filter —
 * it makes the list lie quietly, and the user has no way to find out why.
 */
@Composable
private fun FilterRow(
    state: ObjectSearchUiState,
    onOpenSheet: () -> Unit,
    onClearField: (com.starwindow.app.domain.FilterField) -> Unit,
    onClearAll: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FilterChip(
                selected = state.filter.activeCount > 0,
                onClick = onOpenSheet,
                leadingIcon = {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = {
                    Text(if (state.filter.activeCount > 0) "Filter · ${state.filter.activeCount}" else "Filter")
                },
            )
        }
        items(state.filter.activeLabels, key = { it.field.name }) { chip ->
            InputChip(
                selected = true,
                onClick = { onClearField(chip.field) },
                label = { Text(chip.label) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = "Filter entfernen", modifier = Modifier.size(15.dp))
                },
            )
        }
        if (state.filter.activeCount > 1) {
            item {
                FilterChip(
                    selected = false,
                    onClick = onClearAll,
                    label = { Text("alle zurücksetzen") },
                )
            }
        }
    }
}

/** Says what the list below actually is — hits, a suggestion, or a window's night. */
@Composable
private fun ResultHeader(state: ObjectSearchUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                state.isLoading -> "Katalog wird geladen …"
                state.isComputingWindow -> "Durchgänge werden gerechnet …"
                state.showsBoard -> "Heute Nacht"
                state.hasQuery -> "${state.hits.size} Treffer von ${state.catalogSize}"
                else -> "${state.hits.size} von ${state.catalogSize} Einträgen"
            },
            style = MaterialTheme.typography.labelLarge,
            color = StarWindowColors.WindowStroke,
            modifier = Modifier.weight(1f),
        )
        if (state.observer == null && !state.isLoading) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = null,
                tint = StarWindowColors.Crosshair,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "  ohne Position keine Höhe",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Crosshair,
            )
        } else if (!state.showsBoard && !state.isLoading) {
            // What the verdicts are judged against, so a guess is never mistaken for a measurement.
            Text(
                text = conditionsSummary(state),
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

private fun conditionsSummary(state: ObjectSearchUiState): String = buildString {
    append("Bortle ").append(state.conditions.bortleLevel)
    if (state.conditions.isEstimated) append(" (gesch.)")
    if (state.conditions.moonInterferes) {
        append(" · Mond ").append("%.0f %%".format(state.conditions.moonIlluminationPercent))
    }
}

/** The suggestion board: three short sections instead of one long list. */
@Composable
private fun TonightBoardList(
    state: ObjectSearchUiState,
    onOpenObject: (String) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.board.forEach { group ->
            item(key = "head-${group.section.name}") {
                SectionHeader(
                    title = if (group.section == com.starwindow.app.domain.TonightSection.IN_WINDOW) {
                        state.selectedWindow?.let { "Jetzt im Fenster „${it.name}“" } ?: group.section.title
                    } else {
                        group.section.title
                    },
                    trailing = if (group.moreCount > 0) "+${group.moreCount}" else null,
                )
            }
            items(group.entries, key = { "${group.section.name}-${it.obj.id}" }) { entry ->
                ObjectRow(
                    obj = entry.obj,
                    altitudeDeg = entry.altitudeDeg,
                    selected = entry.obj.id == state.trackedId,
                    feasibility = entry.feasibility,
                    trailing = boardTrailing(entry),
                    onClick = {
                        onHideKeyboard()
                        onOpenObject(entry.obj.id)
                    },
                )
            }
        }
    }
}

/**
 * The right-hand column on the board.
 *
 * Each section answers a different question, so each shows a different number: how much longer it
 * stays in the window, when it gets high enough, or how high it stands and whether it works.
 */
@Composable
private fun boardTrailing(entry: TonightEntry): (@Composable () -> Unit)? {
    val minutes = entry.minutesLeftInWindow
    val rises = entry.risesAtMillis
    return when {
        minutes != null -> {
            {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (minutes >= 60) "%d h %02d".format(minutes / 60, minutes % 60)
                        else "$minutes min",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarWindowColors.WindowStroke,
                    )
                    Text(
                        "noch im Fenster",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        rises != null -> {
            {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatClock(rises),
                        style = MaterialTheme.typography.titleSmall,
                        color = StarWindowColors.AnchorPoint,
                    )
                    Text(
                        "ab dann hoch",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        else -> null
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = StarWindowColors.AnchorPoint,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = StarWindowColors.Muted)
        }
    }
}

@Composable
private fun ResultList(
    state: ObjectSearchUiState,
    onOpenObject: (String) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.hits, key = { it.obj.id }) { hit ->
            ObjectRow(
                obj = hit.obj,
                altitudeDeg = hit.altitudeDeg,
                selected = hit.obj.id == state.trackedId,
                feasibility = hit.feasibility,
                onClick = {
                    onHideKeyboard()
                    onOpenObject(hit.obj.id)
                },
            )
        }
    }
}

/**
 * The window search is the one thing here that takes seconds.
 *
 * It says what it is doing and over which window, because a spinner alone next to an empty list is
 * indistinguishable from a list that found nothing.
 */
@Composable
private fun WindowSearchRunning(state: ObjectSearchUiState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = StarWindowColors.WindowStroke)
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.selectedWindow
                ?.let { "Durchgänge durch „${it.name}“ werden gerechnet" }
                ?: "Durchgänge werden gerechnet",
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Starlight,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Die nächsten zwölf Stunden, für jedes Fotoziel im Katalog.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(state: ObjectSearchUiState) {
    val (headline, hint) = when {
        state.showsBoard && state.observer == null ->
            "Ohne Standort lässt sich nicht sagen, was gerade oben steht." to
                "Standortfreigabe erteilen oder in den Einstellungen Breite und Länge eintragen."

        state.showsBoard ->
            "Im Moment steht nichts aus dem Katalog hoch genug am Himmel." to
                "Später am Abend sieht das anders aus. Über das Suchfeld ist der ganze Katalog erreichbar."

        state.filter.onlyThroughWindow ->
            "Durch dieses Fenster zieht in den nächsten zwölf Stunden nichts." to
                "Ein anderes Fenster wählen, oder den Fensterfilter abschalten."

        state.hasQuery ->
            "Nichts gefunden für \"${state.query}\"." to
                "Es geht mit Katalognummer (M 42, NGC 7000, Sh2-155) genauso wie mit dem Namen " +
                "(Orionnebel, Plejaden, Wega). Vielleicht schränkt auch ein Filter zu stark ein."

        else ->
            "Kein Eintrag passt zu diesen Filtern." to
                "Einen der Chips oben antippen, um ihn zu entfernen."
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Starlight,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}
