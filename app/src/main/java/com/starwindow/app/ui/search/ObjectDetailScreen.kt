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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * "Track" is deliberately the only thing at the bottom of the screen and the only filled button on
 * it: reading about an object is what the screen is for, but *finding it in the sky* is what the
 * app is for, and that action should never have to be hunted for in the dark.
 */
@Composable
fun ObjectDetailScreen(
    viewModel: ObjectDetailViewModel,
    onBack: () -> Unit,
    onStartTracking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val imageLoader = LocalContext.current.appContainer.skyImageLoader

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
                onTrack = {
                    viewModel.track()
                    onStartTracking()
                },
                onUntrack = viewModel::untrack,
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
private fun TrackBar(isTracked: Boolean, onTrack: () -> Unit, onUntrack: () -> Unit) {
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
