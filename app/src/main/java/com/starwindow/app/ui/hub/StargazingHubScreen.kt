package com.starwindow.app.ui.hub

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.appContainer
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.domain.Season
import com.starwindow.app.domain.SeasonalTarget
import com.starwindow.app.domain.TargetKind
import com.starwindow.app.ui.components.ObjectSymbol
import com.starwindow.app.ui.components.ScreenHeader
import com.starwindow.app.ui.components.SectionHeader
import com.starwindow.app.ui.components.SKY_IMAGE_CREDIT
import com.starwindow.app.ui.components.rememberSkyImage
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Der Stargazing-Hub: was an diesem Ort in dieser Jahreszeit zu fotografieren ist.
 *
 * Der Rest der App beantwortet Fragen, die man schon gestellt hat — „wo steht M31", „wird es am
 * Freitag klar", „passt der Mond durch mein Fenster". Der Hub beantwortet die Frage davor, die
 * niemand als Suchbegriff eintippen kann: *worauf soll ich überhaupt die Kamera richten.* Deshalb
 * ist er als Blätterwerk gebaut und nicht als Liste: Man liest ihn nicht, man schaut ihn an, und
 * das Bild entscheidet, ob ein Motiv reizt — nicht die Katalognummer.
 *
 * Vier Jahreszeiten stehen zur Wahl, aber der Inhalt hängt nicht an ihnen, sondern an **einer
 * konkreten Nacht** in ihrer Mitte (bzw. an heute, solange die laufende Jahreszeit gezeigt wird).
 * Das ist der Grund, warum sich der Hub von Monat zu Monat verschiebt, obwohl die Reiter dieselben
 * bleiben: Der Himmel dreht sich in vier Wochen um zwei Stunden weiter, und was im September erst
 * gegen Morgen hoch stand, steht im November schon am Abend dort.
 *
 * Reihen statt eines Rasters, weil die Reihe eine Rangfolge zeigt und ein Raster keine: Das erste
 * Bild in jeder Reihe ist das beste, und alles Weitere ist eine Wischbewegung entfernt.
 */
@Composable
fun StargazingHubScreen(
    viewModel: StargazingHubViewModel,
    onOpenObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val imageLoader: SkyImageLoader = LocalContext.current.appContainer.skyImageLoader

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Stargazing Hub",
            subtitle = "${state.placeLabel}  ·  ${state.monthLabel}",
        )

        SeasonTabs(
            selected = state.season,
            current = state.currentSeason,
            onSelect = viewModel::selectSeason,
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { SeasonStory(state) }

            // Während gerechnet wird, steht nur der Hinweis da. Die Karten der vorigen Jahreszeit
            // unter einem Ladekringel stehen zu lassen wäre die schlechtere Lüge von beiden: Sie
            // sehen genauso aus wie ein Ergebnis, sind aber für einen anderen Himmel gerechnet.
            if (state.isLoading) {
                item { LoadingBlock() }
                return@LazyColumn
            }

            if (!state.hasLocation) item { NoLocationHint() }
            if (state.isEmpty) item { NoNightHint(state.season) }

            state.hero?.let { hero ->
                item {
                    HeroCard(
                        target = hero,
                        note = state.notes[hero.obj.id],
                        // „Hauptmotiv" statt „Aufmacher": Der Begriff kommt aus der Fotografie und
                        // meint dort genau das, was die Karte zeigt — das, worauf das Bild
                        // ausgerichtet wird. „Aufmacher" ist eine Zeitungsvokabel und redet über
                        // die Seite statt über das Foto.
                        badge = if (state.isCurrent) {
                            "Motiv im ${state.monthLabel}"
                        } else {
                            "Hauptmotiv im ${state.season.label}"
                        },
                        loader = imageLoader,
                        onClick = { onOpenObject(hero.obj.id) },
                    )
                }
            }

            if (state.topPicks.isNotEmpty()) {
                item {
                    TargetRow(
                        title = if (state.isCurrent) "Am besten in diesen Nächten" else "Am besten",
                        subtitle = "Höhe, Dunkelheit und Mond der Nacht vom " +
                            "${state.date.dayOfMonth}. ${state.monthLabel}",
                        targets = state.topPicks,
                        startRank = 2,
                        loader = imageLoader,
                        onOpenObject = onOpenObject,
                    )
                }
            }

            items(state.rows, key = { it.first.name }) { (kind, targets) ->
                TargetRow(
                    title = kind.label,
                    subtitle = kindSubtitle(kind, targets.size),
                    targets = targets,
                    startRank = null,
                    loader = imageLoader,
                    onOpenObject = onOpenObject,
                )
            }

            if (state.targets.isNotEmpty()) {
                item { CreditLine() }
            }
        }
    }
}

/**
 * Die vier Reiter.
 *
 * Der laufenden Jahreszeit steht „jetzt" bei, statt sie einfach vorauszuwählen: Ohne diese Marke
 * sieht ein Nutzer, der auf „Winter" getippt hat, dieselbe Ansicht wie beim Öffnen und hat keine
 * Möglichkeit zu erkennen, dass er inzwischen ein halbes Jahr in die Zukunft schaut.
 */
@Composable
private fun SeasonTabs(selected: Season, current: Season, onSelect: (Season) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Season.entries.toList(), key = { it.name }) { season ->
            FilterChip(
                selected = season == selected,
                onClick = { onSelect(season) },
                label = {
                    Text(
                        text = if (season == current) "${season.label} · jetzt" else season.label,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StarWindowColors.WindowStroke.copy(alpha = 0.22f),
                    selectedLabelColor = StarWindowColors.WindowStroke,
                ),
            )
        }
    }
}

@Composable
private fun SeasonStory(state: StargazingHubUiState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = state.season.subtitle,
            style = MaterialTheme.typography.titleSmall,
            color = StarWindowColors.AnchorPoint,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.season.story,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
        )
    }
}

/**
 * Das Hauptmotiv: ein Bild, breit genug, dass man es ansieht statt es zu überfliegen.
 *
 * Text steht im Bild und nicht darunter, weil beides zusammen eine Aussage ist — „das hier, und
 * zwar heute Nacht". Ein Verlauf von unten trägt ihn, damit die Schrift auch über einem hellen
 * Nebelkern lesbar bleibt; die Aufnahmen sind Himmelsausschnitte und nicht dafür gemacht worden,
 * eine Bildunterschrift zu tragen.
 */
@Composable
private fun HeroCard(
    target: SeasonalTarget,
    note: String?,
    badge: String,
    loader: SkyImageLoader?,
    onClick: () -> Unit,
) {
    val image by rememberSkyImage(target.obj, loader, sizePx = 768, widen = 1.6)

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StarWindowColors.NightSurfaceHigh,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onClick),
        ) {
            Box {
                image.bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Aufnahme von ${target.obj.name.ifBlank { target.obj.id }}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (image.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.35f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.85f),
                            )
                        )
                )

                Badge(text = badge, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ObjectSymbol(
                            target.obj.type,
                            size = 18.dp,
                            color = StarWindowColors.CatalogMarker,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = target.obj.name.ifBlank { target.obj.id },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = StarWindowColors.Starlight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${target.obj.id}  ·  ${target.obj.type.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StarWindowColors.CatalogMarker,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = target.headline,
                        style = MaterialTheme.typography.labelMedium,
                        color = StarWindowColors.WindowStroke,
                    )
                }
            }
        }

        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Muted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Eine Reihe Motive, nach rechts weiterblätterbar. */
@Composable
private fun TargetRow(
    title: String,
    subtitle: String?,
    targets: List<SeasonalTarget>,
    /** Sichtbare Platzziffer der ersten Karte, oder null für Reihen ohne Rangfolge. */
    startRank: Int?,
    loader: SkyImageLoader?,
    onOpenObject: (String) -> Unit,
) {
    Column {
        SectionHeader(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(targets, key = { _, target -> target.obj.id }) { index, target ->
                TargetCard(
                    target = target,
                    rank = startRank?.let { it + index },
                    loader = loader,
                    onClick = { onOpenObject(target.obj.id) },
                )
            }
        }
    }
}

/**
 * Eine Karte.
 *
 * Bild oben, Name darunter, und **eine** Zeile Zahlen: Stunden und Höhe. Mehr passt nicht auf 190
 * Punkte Breite, ohne dass es zur Tabelle wird — und mehr braucht die Karte auch nicht, denn ihre
 * Aufgabe ist die Vorauswahl. Alles Weitere steht einen Fingertipp entfernt im Objektblatt.
 */
@Composable
private fun TargetCard(
    target: SeasonalTarget,
    rank: Int?,
    loader: SkyImageLoader?,
    onClick: () -> Unit,
) {
    val image by rememberSkyImage(target.obj, loader, sizePx = 384, widen = 1.4)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StarWindowColors.NightSurface,
        border = BorderStroke(1.dp, StarWindowColors.Outline),
        modifier = Modifier.width(190.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .background(StarWindowColors.NightSurfaceHigh),
            ) {
                image.bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Aufnahme von ${target.obj.name.ifBlank { target.obj.id }}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                when {
                    image.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(22.dp),
                        strokeWidth = 2.dp,
                    )

                    image.bitmap == null -> ObjectSymbol(
                        target.obj.type,
                        size = 32.dp,
                        color = StarWindowColors.Muted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                rank?.let {
                    Badge(
                        text = it.toString(),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )
                }
                if (target.isAtItsBest) {
                    Badge(
                        text = "Bestzeit",
                        icon = true,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = target.obj.name.ifBlank { target.obj.id },
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.Starlight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(target.obj.id)
                        target.obj.constellation?.let { append("  ·  ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.CatalogMarker,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.NightlightRound,
                        contentDescription = null,
                        tint = StarWindowColors.WindowStroke,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "%.1f h  ·  %.0f°".format(
                            target.usableHours,
                            target.night.bestAltitudeDeg,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.WindowStroke,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, icon: Boolean = false) {
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = StarWindowColors.AnchorPoint,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (icon) StarWindowColors.AnchorPoint else StarWindowColors.Starlight,
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Der Himmel dieser Jahreszeit wird durchgerechnet …",
                style = MaterialTheme.typography.labelMedium,
                color = StarWindowColors.Muted,
            )
        }
    }
}

@Composable
private fun NoLocationHint() {
    HintBlock(
        "Ohne Ort keine Jahreszeit: Welche Motive hoch stehen und wie lange es dunkel bleibt, " +
            "hängt an der geografischen Breite. Wähle unter „Wetter\" einen Ort oder erlaube den " +
            "Standortzugriff."
    )
}

@Composable
private fun NoNightHint(season: Season) {
    HintBlock(
        "Im ${season.label} wird es an diesem Ort nicht dunkel genug, um etwas zu belichten. " +
            "Weiter oben in den Reitern liegt die nächste Jahreszeit, die etwas hergibt."
    )
}

@Composable
private fun HintBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StarWindowColors.NightSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun CreditLine() {
    Text(
        text = SKY_IMAGE_CREDIT,
        style = MaterialTheme.typography.labelSmall,
        color = StarWindowColors.Muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Die Zeile unter einer Reihenüberschrift.
 *
 * Einzahl und Mehrzahl getrennt, weil eine Reihe durchaus ein einziges Motiv enthalten kann — im
 * Frühling steht regelmäßig nur ein Nebel in der Liste, und „1 Wolken aus Gas und Staub" wäre die
 * Art Fehler, die eine sonst sorgfältige Oberfläche billig aussehen lässt.
 */
private fun kindSubtitle(kind: TargetKind, count: Int): String = when (kind) {
    TargetKind.NEBULA ->
        if (count == 1) "eine Wolke aus Gas und Staub" else "$count Wolken aus Gas und Staub"
    TargetKind.GALAXY ->
        if (count == 1) "eine fremde Sterneninsel" else "$count fremde Sterneninseln"
    TargetKind.CLUSTER ->
        if (count == 1) "ein Sternhaufen" else "$count Sternhaufen"
    TargetKind.OTHER ->
        if (count == 1) "ein weiteres Motiv" else "$count weitere Motive"
}
