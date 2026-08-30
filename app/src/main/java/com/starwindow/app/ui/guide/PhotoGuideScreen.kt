package com.starwindow.app.ui.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.appContainer
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.gear.SmartTelescope
import com.starwindow.app.data.gear.SmartTelescopes
import com.starwindow.app.domain.BortleScale
import com.starwindow.app.domain.DewRisk
import com.starwindow.app.domain.FramingVerdict
import com.starwindow.app.domain.GuideVerdict
import com.starwindow.app.domain.PhotoPlan
import com.starwindow.app.ui.components.ObjectSymbol
import com.starwindow.app.ui.components.ScreenHeader
import com.starwindow.app.ui.components.rememberSkyImage
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Der Fotoguide: welche Schalter für dieses Motiv, dieses Gerät und diese Nacht.
 *
 * Der Hub beantwortet „worauf richte ich die Kamera", dieser Bildschirm die Frage danach: „und wie
 * stelle ich sie ein". Beide sind gleich gebaut — Kopfzeile, Karten, Bildmotiv oben —, weil sie
 * dieselbe Handbewegung bedienen: durchblättern, nicht ausfüllen. Der Unterschied ist, dass hier
 * drei Angaben vom Nutzer kommen müssen, und deshalb steht oben ein Formular und darunter das
 * Ergebnis, das sich mit jeder Änderung sofort mitbewegt.
 *
 * Kein „Berechnen"-Knopf: Die Rechnung dauert Millisekunden, und ein Knopf würde nur verstecken,
 * welche der drei Angaben das Ergebnis gerade verändert hat. Genau das will man aber sehen — dass
 * eine Stufe mehr Bortle die Belichtungszeit verdoppelt, ist die eigentliche Lehre dieses
 * Bildschirms.
 */
@Composable
fun PhotoGuideScreen(
    viewModel: PhotoGuideViewModel,
    onOpenObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val imageLoader = LocalContext.current.appContainer.skyImageLoader

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Fotoguide",
            subtitle = "${state.telescope.name}  ·  Bortle ${state.bortle}  ·  ${state.placeLabel}",
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                StepBlock(number = 1, title = "Was willst du fotografieren?") {
                    TargetPicker(
                        state = state,
                        loader = imageLoader,
                        onQueryChange = viewModel::setQuery,
                        onSelect = viewModel::selectTarget,
                        onClear = viewModel::clearTarget,
                        onOpenObject = onOpenObject,
                    )
                }
            }

            item {
                StepBlock(number = 2, title = "Womit?") {
                    TelescopePicker(
                        selected = state.telescope,
                        onSelect = viewModel::selectTelescope,
                    )
                }
            }

            item {
                StepBlock(number = 3, title = "Wie dunkel ist es bei dir?") {
                    BortlePicker(state = state, onChange = viewModel::setBortle)
                }
            }

            when {
                state.isLoading -> item { GuideLoading() }
                state.target == null -> item { GuideHint(WAITING_FOR_TARGET) }
                !state.hasLocation -> item { GuideHint(NO_LOCATION) }
                else -> state.plan?.let { plan ->
                    item { VerdictCard(plan) }
                    item { FilterCard(plan) }
                    item { ExposureCard(plan, state) }
                    item { FramingCard(plan) }
                    item { DewCard(plan, state.dewSource) }
                }
            }
        }
    }
}

/** Eine nummerierte Eingabe. Die Ziffer macht sichtbar, dass es genau drei sind und keine mehr. */
@Composable
private fun StepBlock(number: Int, title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(50),
                color = StarWindowColors.WindowStroke.copy(alpha = 0.18f),
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StarWindowColors.WindowStroke,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = StarWindowColors.Starlight,
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TargetPicker(
    state: PhotoGuideUiState,
    loader: com.starwindow.app.data.images.SkyImageLoader,
    onQueryChange: (String) -> Unit,
    onSelect: (SkyObject) -> Unit,
    onClear: () -> Unit,
    onOpenObject: (String) -> Unit,
) {
    val target = state.target
    if (target != null) {
        val image by rememberSkyImage(target, loader, sizePx = 512, widen = 1.5)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = StarWindowColors.NightSurface,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                image.bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.2f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
                )
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    IconButton(onClick = { onOpenObject(target.id) }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Infos zu ${target.name.ifBlank { target.id }}",
                            tint = StarWindowColors.CatalogMarker,
                        )
                    }
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Anderes Ziel wählen",
                            tint = StarWindowColors.Starlight,
                        )
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                ) {
                    Text(
                        text = target.name.ifBlank { target.id },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = StarWindowColors.Starlight,
                    )
                    Text(
                        text = "${target.id}  ·  ${target.type.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StarWindowColors.CatalogMarker,
                    )
                }
            }
        }
        return
    }

    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        placeholder = { Text("Name oder Katalognummer", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = StarWindowColors.NightSurface,
            unfocusedContainerColor = StarWindowColors.NightSurface,
        ),
    )

    Spacer(Modifier.height(8.dp))
    Text(
        text = if (state.favoritesShown) {
            "Vorschläge – deine Favoriten zuerst"
        } else {
            "${state.suggestions.size} Treffer"
        },
        style = MaterialTheme.typography.labelSmall,
        color = StarWindowColors.Muted,
    )
    Spacer(Modifier.height(6.dp))

    val favorites = LocalContext.current.appContainer.favoritesRepository
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.suggestions.forEach { obj ->
            SuggestionRow(
                obj = obj,
                isFavorite = favorites.isFavorite(obj.id),
                onClick = { onSelect(obj) },
            )
        }
    }
}

@Composable
private fun SuggestionRow(obj: SkyObject, isFavorite: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StarWindowColors.NightSurface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObjectSymbol(obj.type, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.name.ifBlank { obj.id },
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarWindowColors.Starlight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(obj.id)
                        append("  ·  ").append(obj.type.label)
                        obj.sizeArcmin?.let { append("  ·  %.0f'".format(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Favorit",
                    tint = StarWindowColors.TrackTarget,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun TelescopePicker(selected: SmartTelescope, onSelect: (SmartTelescope) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SmartTelescopes.ALL, key = { it.id }) { telescope ->
            val isSelected = telescope.id == selected.id
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isSelected) {
                    StarWindowColors.WindowStroke.copy(alpha = 0.16f)
                } else {
                    StarWindowColors.NightSurface
                },
                modifier = Modifier
                    .width(150.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelect(telescope) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = telescope.model,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) {
                            StarWindowColors.WindowStroke
                        } else {
                            StarWindowColors.Starlight
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = telescope.brand,
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "%.0f mm  ·  f/%.1f".format(
                            telescope.apertureMm,
                            telescope.focalLengthMm / telescope.apertureMm,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.CatalogMarker,
                    )
                    Text(
                        text = "Bildfeld %.1f° × %.1f°".format(
                            telescope.fovLongDeg,
                            telescope.fovShortDeg,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                }
            }
        }
    }
}

/**
 * Die Bortle-Stufe.
 *
 * Der Vorschlag steht als Knopf da und ist nicht vorausgewählt-und-fertig: Die Schätzung kommt aus
 * Ortsgröße und Entfernung und kennt die Lichtglocke hinter dem Hügel nicht. Wer seinen Platz
 * kennt, weiß es besser — und der Regler daneben ist die einzige ehrliche Art, das zu sagen.
 */
@Composable
private fun BortlePicker(state: PhotoGuideUiState, onChange: (Int?) -> Unit) {
    val bortleClass = BortleScale.CLASSES.firstOrNull { it.level == state.bortle }

    Row(verticalAlignment = Alignment.CenterVertically) {
        state.suggestedBortle?.let { suggested ->
            FilterChip(
                selected = !state.bortleIsManual,
                onClick = { onChange(null) },
                label = { Text("Vorschlag: $suggested") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StarWindowColors.WindowStroke.copy(alpha = 0.22f),
                    selectedLabelColor = StarWindowColors.WindowStroke,
                ),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "Bortle ${state.bortle}",
            style = MaterialTheme.typography.titleSmall,
            color = if (state.bortleIsManual) {
                StarWindowColors.AnchorPoint
            } else {
                StarWindowColors.Starlight
            },
        )
        if (state.bortleIsManual && state.suggestedBortle != null) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(null) }) { Text("zurücksetzen") }
        }
    }

    Slider(
        value = state.bortle.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = 1f..9f,
        steps = 7,
    )

    bortleClass?.let {
        Text(
            text = "${it.title} – ${it.consequence}",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
    if (state.suggestedBortle == null) {
        Text(
            text = "Für diesen Ort lässt sich nichts schätzen – wähle unter „Wetter\" einen Ort " +
                "oder trage die Stufe selbst ein.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.AnchorPoint,
        )
    }
}

/** Das Urteil, groß und farbig — die eine Zeile, für die der Bildschirm da ist. */
@Composable
private fun VerdictCard(plan: PhotoPlan) {
    val color = when (plan.verdict) {
        GuideVerdict.GOOD -> StarWindowColors.WindowStroke
        GuideVerdict.MARGINAL -> StarWindowColors.AnchorPoint
        GuideVerdict.POOR -> StarWindowColors.Crosshair
    }
    GuideCard(
        icon = when (plan.verdict) {
            GuideVerdict.GOOD -> Icons.Filled.CheckCircle
            else -> Icons.Filled.ErrorOutline
        },
        tint = color,
        title = plan.verdict.label,
    ) {
        Text(
            text = verdictExplanation(plan),
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { plan.nightCoverage.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = StarWindowColors.NightSurfaceHigh,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Die Nacht gibt ${formatMinutes(plan.availableMinutes)} her, " +
                "empfohlen sind ${formatMinutes(plan.recommendedMinutes)}.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

@Composable
private fun FilterCard(plan: PhotoPlan) {
    GuideCard(
        icon = Icons.Filled.FilterAlt,
        tint = if (plan.useFilter) StarWindowColors.WindowStroke else StarWindowColors.Muted,
        title = if (plan.useFilter) "LP-Filter einschalten" else "Ohne Filter aufnehmen",
    ) {
        Text(
            // Der Objektname statt der Typbezeichnung, weil die im Deutschen nicht in jeden Satz
            // passt: „Emissionsnebel strahlen" geht, „Planetarischer Nebel strahlen" nicht.
            text = if (plan.useFilter) {
                "${plan.obj.name.ifBlank { plan.obj.id }} strahlt fast sein ganzes Licht in den " +
                    "zwei Wellenlänge ab, die der ${plan.telescope.builtInFilter.label} durchlässt – " +
                    "dadurch dunkelt der Filter den Himmelshintergrund deutlich stärker ab als das Motiv " +
                    "und verkürzt die nötige Belichtungszeit um etwa das %.1f-Fache.".format(plan.filterSpeedup)
            } else {
                "Das Licht dieses Objekts verteilt sich über das gesamte sichtbare Spektrum. Der " +
                    "${plan.telescope.builtInFilter.label} würde den größten Teil davon blockieren " +
                    "und die nötige Belichtungszeit um das %.0f-Fache verlängern."
                        .format(1.0 / plan.filterSpeedup)
            },
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
    }
}

@Composable
private fun ExposureCard(plan: PhotoPlan, state: PhotoGuideUiState) {
    GuideCard(
        icon = Icons.Filled.Timer,
        tint = StarWindowColors.CatalogMarker,
        title = "${plan.subExposureSec} s je Bild  ·  ${formatMinutes(plan.recommendedMinutes)} gesamt",
    ) {
        Text(
            text = "Nach etwa ${formatMinutes(plan.minimumMinutes)} entsteht ein solides Grundbild. " +
                "Das Optimum liegt bei ${formatMinutes(plan.recommendedMinutes)} (ca. ${plan.subCount} Einzelbilder) – " +
                "ab diesem Punkt bringt weiteres Belichten kaum noch sichtbare Verbesserungen.",
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
        // Eine Empfehlung, die in keine Nacht passt, liest sich als Rechenfehler, wenn nicht
        // dabeisteht, dass sie auch nicht in eine passen soll. Über Nächte zu stapeln ist der
        // übliche Weg zu einem tiefen Bild und keine Notlösung.
        plan.nightsNeeded?.let { nights ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Die empfohlene Belichtungszeit passt nicht in eine einzelne Nacht. " +
                    "Bei ${formatMinutes(plan.availableMinutes)} nutzbarer Zeit pro Nacht sind dafür " +
                    "$nights Nächte nötig, deren Aufnahmen später kombiniert werden.",
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.AnchorPoint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.NightlightRound,
                contentDescription = null,
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (state.moonAltitudeDeg <= 0.0) {
                    "Der Mond steht unter dem Horizont und beeinflusst die Belichtungszeit nicht."
                } else {
                    "Mond (%.0f %% beleuchtet, %.0f° hoch) ist in den obigen Zeiten bereits berücksichtigt."
                        .format(state.moonIlluminationPercent, state.moonAltitudeDeg)
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

@Composable
private fun FramingCard(plan: PhotoPlan) {
    val tint = when (plan.framing) {
        FramingVerdict.TOO_SMALL -> StarWindowColors.Crosshair
        FramingVerdict.NEEDS_MOSAIC, FramingVerdict.TIGHT -> StarWindowColors.AnchorPoint
        else -> StarWindowColors.WindowStroke
    }
    GuideCard(
        icon = if (plan.needsMosaic) Icons.Filled.GridView else Icons.Filled.CropFree,
        tint = tint,
        title = when {
            plan.framing == FramingVerdict.NEEDS_MOSAIC -> "Mosaik erforderlich"
            plan.framing == FramingVerdict.TIGHT -> "Knapper Ausschnitt – Mosaik empfohlen"
            else -> plan.framing.label
        },
    ) {
        Text(
            text = framingExplanation(plan),
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Sichtfeld: %.2f° × %.2f°  ·  Füllt %.0f %% der kurzen Kante  ·  Größe: %.0f px"
                .format(
                    plan.telescope.fovLongDeg,
                    plan.telescope.fovShortDeg,
                    plan.fillFraction * 100,
                    plan.objectPixels,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

@Composable
private fun DewCard(plan: PhotoPlan, dewSource: String?) {
    GuideCard(
        icon = Icons.Filled.WaterDrop,
        tint = when (plan.dew) {
            DewRisk.CERTAIN, DewRisk.LIKELY -> StarWindowColors.AnchorPoint
            DewRisk.POSSIBLE -> StarWindowColors.CatalogMarker
            DewRisk.NONE -> StarWindowColors.Muted
        },
        title = plan.dew.label.replaceFirstChar { it.uppercase() },
    ) {
        Text(
            text = plan.dewAction ?: "Die Temperatur bleibt voraussichtlich deutlich über dem " +
                "Taupunkt. Weder Taukappe noch Heizmodus sind erforderlich.",
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dewSource ?: "Diese Schätzung basiert lediglich auf der Jahreszeit. " +
                "Lege unter „Wetter“ einen Ort fest, um eine präzise Prognose zu erhalten.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

@Composable
private fun GuideCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StarWindowColors.NightSurface,
        border = BorderStroke(1.dp, StarWindowColors.Outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun GuideLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GuideHint(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StarWindowColors.NightSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private const val WAITING_FOR_TARGET =
    "Wähle oben ein Motiv aus. Anschließend siehst du hier, welcher Filter, welche " +
        "Belichtungszeit und welcher Modus für diese Nacht empfohlen werden."

private const val NO_LOCATION =
    "Ohne Standort lässt sich die Sichtbarkeit des Objekts für heute Nacht nicht berechnen. " +
        "Wähle unter „Wetter“ einen Ort aus oder erlaube den Standortzugriff."

private fun verdictExplanation(plan: PhotoPlan): String = when {
    plan.framing == FramingVerdict.TOO_SMALL ->
        "Mit einer Größe von nur %.0f Pixeln wirkt ${plan.obj.name.ifBlank { plan.obj.id }} " +
            "auf dem Bild winzig. Dem ${plan.telescope.model} fehlt hierfür die nötige " +
            "Brennweite – das lässt sich auch mit mehr Belichtungszeit nicht ausgleichen."
            .format(plan.objectPixels)

    plan.framing == FramingVerdict.NEEDS_MOSAIC && !plan.telescope.hasMosaic ->
        "Das Motiv übersteigt das Sichtfeld, aber das ${plan.telescope.model} unterstützt " +
            "keine Mosaik-Aufnahmen. Es wird daher nur ein Teilausschnitt abgebildet."

    // Null Minuten heißt nicht „wenig Zeit", sondern „steht heute Nacht gar nicht da" — und das
    // ist eine andere Auskunft, aus der auch etwas anderes folgt.
    plan.availableMinutes == 0 ->
        "${plan.obj.name.ifBlank { plan.obj.id }} erreicht heute Nacht nicht die nötigen 20° " +
            "Höhe oder ist nur bei Tageslicht am Himmel. Der Plan unten wird dennoch für " +
            "künftige Nächte berechnet. Einen günstigeren Zeitpunkt zeigt der Kalender."

    !plan.fitsInTonight ->
        "Heute Nacht verbleiben nur ${formatMinutes(plan.availableMinutes)} nutzbare Zeit, " +
            "benötigt werden jedoch mindestens ${formatMinutes(plan.minimumMinutes)}. Es empfiehlt " +
            "sich, auf eine Nacht mit höherem Objektstand oder weniger Mondlicht zu warten."

    else ->
        "Die verfügbare Zeit von ${formatMinutes(plan.availableMinutes)} deckt die geforderte " +
            "Mindestbelichtung (${formatMinutes(plan.minimumMinutes)}) problemlos ab."
}

private fun framingExplanation(plan: PhotoPlan): String = when (plan.framing) {
    FramingVerdict.TOO_SMALL ->
        "Bei einer Auflösung von %.1f\"/Pixel erreicht das Objekt nur eine Größe von %.0f Pixeln. " +
            "Unterhalb von etwa 40 Pixeln lassen sich kaum noch Strukturen auflösen – auch nicht " +
                "mit längerer Belichtungszeit."
            .format(plan.telescope.pixelScaleArcsec, plan.objectPixels)

    FramingVerdict.SMALL ->
        "Das Motiv passt problemlos in das Bildfeld, fällt aber relativ klein aus. Ein späterer " +
            "Zuschnitt (Crop) in der Bildbearbeitung ist hier das übliche Vorgehen."

    FramingVerdict.GOOD ->
        "Das Motiv füllt das Bildfeld optimal aus. Eine Einzelaufnahme genügt, ein Mosaik " +
                "ist nicht erforderlich."

    FramingVerdict.TIGHT ->
        "Das Objekt reicht fast bis an den Rand des Bildfelds. Es gibt wenig Spielraum für die " +
                "Bildbearbeitung; " +
            if (plan.telescope.hasMosaic) {
                "ein Mosaik sorgt hier für den nötigen Rahmen."
            } else {
                "eine exakte Ausrichtung ist daher unerlässlich."
            }

    FramingVerdict.NEEDS_MOSAIC ->
        "Das Motiv ist größer als das Sichtfeld. " +
            if (plan.telescope.hasMosaic) {
                "Im Mosaikmodus kombiniert das Gerät mehrere Kacheln. Das vervielfacht zwar " +
                    "die Belichtungszeit, ist aber der einzige Weg zum vollständigen Objekt."
            } else {
                "Da das Gerät keine Mosaike unterstützt, kann nur ein Ausschnitt aufgenommen werden."
            }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "%d:%02d h".format(minutes / 60, minutes % 60)
}
