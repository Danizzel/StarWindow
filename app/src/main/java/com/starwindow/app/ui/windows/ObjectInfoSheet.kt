package com.starwindow.app.ui.windows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.ui.components.SKY_IMAGE_CREDIT
import com.starwindow.app.ui.components.rememberSkyImage
import com.starwindow.app.domain.ObjectDescription
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.ui.components.SectionCard
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowSpacing

/**
 * Everything the info sheet needs, gathered by the view model so the sheet stays presentational.
 *
 * [passes] and [fillFactor] are only known when the object is being looked at *through a window*;
 * from the search screen there is no window, and those parts of the sheet simply do not appear.
 */
data class ObjectInfo(
    val obj: SkyObject,
    val track: SkyTrack,
    val passes: List<WindowPass> = emptyList(),
    val currentPosition: Horizontal? = null,
    /** Fraction of the window's width the object covers; above 1 it does not fit. */
    val fillFactor: Double? = null,
    /** What the object is and what makes it worth looking at, in plain language. */
    val description: ObjectDescription? = null,
)

@Composable
fun ObjectInfoSheet(
    info: ObjectInfo,
    imageLoader: SkyImageLoader?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ObjectInfoTitle(info.obj) },
        text = {
            ObjectInfoContent(
                info = info,
                imageLoader = imageLoader,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Name over designation, type and constellation — the same heading in the sheet and the screen. */
@Composable
fun ObjectInfoTitle(obj: SkyObject) {
    Column {
        Text(obj.name.ifBlank { obj.id }, style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                append(obj.id)
                append(" · ").append(obj.type.label)
                obj.constellation?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelMedium,
            color = StarWindowColors.Muted,
        )
    }
}

/**
 * Everything known about one object, without deciding where it is shown.
 *
 * Split out of the dialog so the search screen can show exactly the same facts full screen: two
 * places describing the same object differently would be two places to keep in step, and the user
 * would have to learn the layout twice.
 */
@Composable
fun ObjectInfoContent(
    info: ObjectInfo,
    imageLoader: SkyImageLoader?,
    modifier: Modifier = Modifier,
) {
    val obj = info.obj

    // Drei Karten statt einer durchgehenden Spalte mit einer Trennlinie darin: Bild, Erklärung und
    // Zahlen beantworten drei verschiedene Fragen, und wer nur eine davon hat, findet sie so, ohne
    // den Rest zu lesen.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StarWindowSpacing.between),
    ) {
        SkyImageView(obj, imageLoader)

        // Before any number: what the thing actually is. Everything below this point assumes the
        // reader already knows, and for most of the catalogue that is not a safe assumption.
        info.description?.let { description ->
            SectionCard { DescriptionBlock(description) }
        }

        SectionCard {
            Text(
                "Höhe über dem Horizont",
                style = MaterialTheme.typography.titleSmall,
                color = StarWindowColors.Starlight,
            )
            AltitudeCurveChart(track = info.track, passes = info.passes)
            Text(
                if (info.passes.isEmpty()) {
                    "Die Kurve zeigt die nächsten Stunden; die Linie bei 0° ist der Horizont."
                } else {
                    "Grün hinterlegt: die Zeit, in der das Objekt im Fenster steht."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }

        SectionCard {
        InfoRow("Helligkeit", obj.magnitude?.let { "%.1f mag".format(it) })
        InfoRow(
            "Flächenhelligkeit",
            obj.surfaceBrightness?.let { "%.1f mag/□'".format(it) },
            hint = "Sagt für die Fotografie mehr als die Gesamthelligkeit: ein großes " +
                "Objekt verteilt sein Licht.",
        )
        InfoRow("Ausdehnung", formatSize(obj))
        InfoRow("Positionswinkel", obj.positionAngleDeg?.let { "%.0f°".format(it) })
        InfoRow("Morphologie", obj.morphology)
        InfoRow(
            "Rektaszension",
            Angles.formatRa(obj.raDeg) + "  ·  " + Angles.formatDec(obj.decDeg),
        )
        info.currentPosition?.let {
            InfoRow(
                "Jetzt",
                "Az %.1f°  Höhe %.1f°".format(it.azimuthDeg, it.altitudeDeg) +
                    if (it.altitudeDeg <= 0.0) "  (unter dem Horizont)" else "",
            )
        }
        info.fillFactor?.let {
            InfoRow(
                "Im Fenster",
                when {
                    it > 1.0 -> "größer als das Fenster (%.0f %%)".format(it * 100)
                    it > 0.3 -> "füllt %.0f %% der Fensterbreite".format(it * 100)
                    else -> "klein im Fenster (%.0f %%)".format(it * 100)
                },
            )
        }
        // The narrowband hint used to live here; it is part of the description above now, where it
        // sits next to the reason it is true.
        if (obj.catalogIds.isNotEmpty()) {
            InfoRow("Auch bekannt als", obj.catalogIds.take(8).joinToString(", "))
        }
        if (obj.alternativeNames.isNotEmpty()) {
            InfoRow("Weitere Namen", obj.alternativeNames.joinToString(", "))
        }
        InfoRow("Quelle", obj.source)
        }
    }
}

/**
 * What the object is, what makes it special, and what its numbers mean.
 *
 * Ordered by how much of it is about *this* object: the hand-written note first when there is one,
 * because that is the part the reader came for, then the type explained, then the traits read off
 * the catalogue row. Objects nobody has written about start at the second line and still say
 * something useful.
 */
@Composable
private fun DescriptionBlock(description: ObjectDescription) {
    Column {
        description.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = description.whatItIs,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
        )

        if (description.traits.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            description.traits.forEach { trait ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = "· ",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarWindowColors.WindowStroke,
                    )
                    Text(
                        text = trait,
                        style = MaterialTheme.typography.bodySmall,
                        color = StarWindowColors.Muted,
                    )
                }
            }
        }
    }
}

/**
 * The picture of the object.
 *
 * Rendered from a sky survey at the object's own coordinates, so it always matches what the window
 * would actually frame. Without a connection there is simply no picture — nothing else in the sheet
 * depends on it.
 */
@Composable
private fun SkyImageView(obj: SkyObject, imageLoader: SkyImageLoader?) {
    if (imageLoader == null) return

    val image by rememberSkyImage(obj, imageLoader)
    val bitmap = image.bitmap

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = StarWindowColors.NightSurfaceHigh,
        border = BorderStroke(1.dp, StarWindowColors.Outline),
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Aufnahme von ${obj.name.ifBlank { obj.id }}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )

                image.isLoading -> CircularProgressIndicator()

                image.failed -> Text(
                    "Kein Bild verfügbar – keine Verbindung oder der Bilddienst antwortet nicht.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    if (bitmap != null) {
        Text(
            SKY_IMAGE_CREDIT,
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?, hint: String? = null) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = StarWindowColors.Muted,
            modifier = Modifier.fillMaxWidth(0.38f),
        )
        Column {
            Text(value, style = MaterialTheme.typography.bodySmall)
            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = StarWindowColors.Muted)
            }
        }
    }
}

private fun formatSize(obj: SkyObject): String? {
    val major = obj.sizeArcmin ?: return null
    val minor = obj.minorAxisArcmin
    return if (minor != null && minor > 0.0 && minor < major) {
        "%.1f' × %.1f'".format(major, minor)
    } else {
        "%.1f'".format(major)
    }
}
