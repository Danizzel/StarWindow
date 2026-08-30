package com.starwindow.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.appContainer
import com.starwindow.app.ui.components.ObjectSymbol
import com.starwindow.app.ui.theme.ObjectPalette
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.windows.ObjectInfoContent
import com.starwindow.app.ui.windows.ObjectInfoTitle

/**
 * One object in full, with the button that turns it into a target.
 *
 * Two actions at the bottom, and they answer two different questions. **Track** answers "where is
 * it right now" and is the filled button, because finding a thing in the sky is what the app is
 * for. **Planung** answers "when is it worth going out for" — the seasonal question, which for a
 * faint target is the one that actually decides whether the photograph ever happens.
 */
@Composable
fun ObjectDetailScreen(
    viewModel: ObjectDetailViewModel,
    onBack: () -> Unit,
    onStartTracking: () -> Unit,
    onOpenPlanning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = context.appContainer.skyImageLoader
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Abgelehnt heißt: der Eintrag bleibt, er kann sich nur nicht melden. */ }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            state.obj?.let { obj ->
                ObjectSymbol(obj.type, size = 22.dp, modifier = Modifier.padding(end = 10.dp))
                Box(modifier = Modifier.weight(1f)) { ObjectInfoTitle(obj) }
                // Oben neben dem Namen und nicht unten bei den Knöpfen: Das Herz sagt etwas über
                // das Objekt, während die Leiste am Fuß sagt, was mit ihm geschehen soll.
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (state.isFavorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = if (state.isFavorite) {
                            "Aus den Favoriten entfernen"
                        } else {
                            "Zu den Favoriten hinzufügen"
                        },
                        tint = if (state.isFavorite) {
                            StarWindowColors.TrackTarget
                        } else {
                            StarWindowColors.Muted
                        },
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null -> Text(
                    text = requireNotNull(state.error),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = StarWindowColors.Crosshair,
                    textAlign = TextAlign.Center,
                )

                state.info != null -> ObjectInfoContent(
                    info = requireNotNull(state.info),
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                )

                // Known object, unknown observer: what it *is* still holds, only where it stands
                // does not — so the description stays and the curve goes.
                else -> Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    NoPositionNotice(Modifier.align(Alignment.CenterHorizontally))
                    state.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it.note ?: it.whatItIs,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarWindowColors.Starlight,
                        )
                        if (it.note != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = it.whatItIs,
                                style = MaterialTheme.typography.bodySmall,
                                color = StarWindowColors.Muted,
                            )
                        }
                    }
                }
            }
        }

        if (state.obj != null) {
            TrackBar(
                isTracked = state.isTracked,
                isWatched = state.isWatched,
                // Sonne und Mond lassen sich nicht vormerken: Die Merkliste hält die Koordinaten
                // ihrer Einträge fest, damit die nächtliche Prüfung ohne den Katalog auskommt —
                // und genau die haben diese beiden nicht. Ein Knopf, der stillschweigend etwas
                // anderes täte, wäre schlechter als keiner.
                canWatch = state.obj?.isMoving != true,
                onTrack = {
                    viewModel.track()
                    onStartTracking()
                },
                onUntrack = viewModel::untrack,
                onPlan = onOpenPlanning,
                onToggleWatch = {
                    // Erst beim Einschalten fragen, und genau dann: Der Nutzer hat gerade gesagt,
                    // wofür er die Benachrichtigung haben will.
                    if (!state.isWatched &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.toggleWatch()
                },
            )
        }
    }
}

/**
 * The bottom bar.
 *
 * Says what will happen rather than only naming the action: someone who taps "Track" for the first
 * time should not have to guess that the app is about to jump back to the camera.
 */
@Composable
private fun TrackBar(
    isTracked: Boolean,
    isWatched: Boolean,
    canWatch: Boolean,
    onTrack: () -> Unit,
    onUntrack: () -> Unit,
    onPlan: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    Surface(color = StarWindowColors.NightSurface, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = if (isTracked) {
                    "Wird im Sucher verfolgt – ein Pfeil am Bildschirmrand zeigt die Richtung."
                } else {
                    "Track öffnet die Kameraansicht und zeigt mit einem Pfeil, wohin du das Handy drehen musst."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onTrack, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (isTracked) "Im Sucher zeigen" else "Track")
                }
                if (isTracked) {
                    OutlinedButton(onClick = onUntrack) {
                        Icon(
                            Icons.Filled.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Beenden")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Planung")
                }
                if (canWatch) {
                    OutlinedButton(onClick = onToggleWatch, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (isWatched) {
                                Icons.Filled.NotificationsActive
                            } else {
                                Icons.Outlined.NotificationsNone
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isWatched) {
                                StarWindowColors.WindowStroke
                            } else {
                                LocalContentColor.current
                            },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (isWatched) "Vorgemerkt" else "Bescheid geben")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !canWatch -> "Planung rechnet für jede Nacht des kommenden Jahres, wie lange " +
                        "das Objekt hoch steht und der Himmel dunkel ist."
                    isWatched -> "Steht auf der Merkliste: Die App meldet sich nachmittags, sobald " +
                        "es nachts hoch genug steht und die Vorhersage mitspielt."
                    else -> "Planung legt eine bestimmte Nacht fest. Bescheid geben wartet " +
                        "stattdessen auf die erste, in der es passt – Stand am Himmel und Wetter " +
                        "zusammen."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

@Composable
private fun NoPositionNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.MyLocation,
            contentDescription = null,
            tint = ObjectPalette.Other,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ohne Standort lässt sich nicht sagen, wo das Objekt gerade steht.",
            style = MaterialTheme.typography.bodyMedium,
            color = StarWindowColors.Starlight,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Standortfreigabe erteilen oder in den Einstellungen der Kameraansicht eine Position " +
                "von Hand eintragen. Verfolgen funktioniert danach sofort.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}
