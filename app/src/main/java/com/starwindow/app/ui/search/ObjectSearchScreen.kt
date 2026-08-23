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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.starwindow.app.ui.components.ObjectRow
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Finding an object by name or catalogue number.
 *
 * The field sits at the very top and takes focus straight away, because there is exactly one
 * reason to open this screen. Below it are the two decisions worth offering — what kind of object,
 * and in what order — as single rows of chips rather than a settings page, so the whole control
 * area costs about a fifth of the screen and the results keep the rest.
 *
 * With nothing typed the list is not empty but shows what stands high in the sky right now, which
 * is the answer to "what should I even look at tonight".
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

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ObjectSearchViewModel.FILTERS, key = { it.name }) { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

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

        Spacer(Modifier.height(8.dp))
        ResultHeader(state)
        HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

        if (state.isEmpty) {
            EmptyState(hasQuery = state.hasQuery, query = state.query)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.hits, key = { it.obj.id }) { hit ->
                    ObjectRow(
                        obj = hit.obj,
                        altitudeDeg = hit.altitudeDeg,
                        selected = hit.obj.id == state.trackedId,
                        onClick = {
                            keyboard?.hide()
                            onOpenObject(hit.obj.id)
                        },
                    )
                }
            }
        }
    }

    // One reason to be here, so the keyboard is up before the user has to ask for it.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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

/** Says what the list below actually is — a set of hits, or a suggestion. */
@Composable
private fun ResultHeader(state: ObjectSearchUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                state.isLoading -> "Katalog wird geladen …"
                state.hasQuery -> "${state.hits.size} Treffer"
                state.observer == null -> "Katalog (${state.catalogSize} Einträge)"
                state.sort == ObjectSort.RELEVANCE -> "Jetzt gut sichtbar"
                else -> "Katalog (${state.catalogSize} Einträge)"
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
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean, query: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasQuery) {
                "Nichts gefunden für \"$query\"."
            } else {
                "Im Moment steht nichts aus dem Katalog hoch genug am Himmel."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Starlight,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasQuery) {
                "Es geht mit Katalognummer (M 42, NGC 7000, IC 1396) genauso wie mit dem Namen " +
                    "(Orionnebel, Plejaden). Der Art-Filter oben schränkt zusätzlich ein."
            } else {
                "Andere Art wählen oder einfach einen Namen eingeben."
            },
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}
