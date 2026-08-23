package com.starwindow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.ui.theme.ObjectPalette
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * One catalogue entry as a scannable row.
 *
 * Built the way a list is meant to be read rather than as a paragraph of facts: the symbol says
 * *what kind of thing* it is before a single word is read, the designation and name carry the
 * identity, the grey line underneath carries the details nobody scans for, and the right-hand
 * column carries the one number that decides whether it is worth walking outside — how high it
 * stands right now.
 *
 * Every column is at a fixed position, so forty rows can be skimmed down a column instead of read
 * one at a time. That is the whole difference between a list and a wall of text.
 */
@Composable
fun ObjectRow(
    obj: SkyObject,
    altitudeDeg: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) StarWindowColors.NightSurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            ObjectSymbol(obj.type, size = 20.dp)
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = obj.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ObjectPalette.colorFor(obj.type),
                    maxLines = 1,
                )
                val name = obj.name
                if (name.isNotBlank() && name != obj.id) {
                    Text(
                        text = "  $name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarWindowColors.Starlight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = objectSubtitle(obj),
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            AltitudeBadge(altitudeDeg)
        }
    }
}

/** Type, constellation, size and brightness in one grey line — the details, out of the way. */
fun objectSubtitle(obj: SkyObject): String = buildString {
    append(obj.type.label)
    obj.constellation?.let { append(" · ").append(it) }
    obj.sizeArcmin?.let { append(" · ").append(formatArcmin(it)) }
    obj.magnitude?.let { append(" · %.1f mag".format(it)) }
}

/**
 * How high the object stands right now.
 *
 * Colour-coded because this is the one value that decides whether an entry matters tonight: green
 * for high enough to actually photograph, amber for low in the haze near the horizon, grey for
 * below it. The colour repeats what the number says rather than replacing it, so it still works in
 * the dark and for a colour-blind eye.
 */
@Composable
fun AltitudeBadge(altitudeDeg: Double?, modifier: Modifier = Modifier) {
    if (altitudeDeg == null) {
        Text(
            text = "–",
            modifier = modifier.width(66.dp),
            style = MaterialTheme.typography.labelMedium,
            color = StarWindowColors.Muted,
        )
        return
    }

    val color = when {
        altitudeDeg >= 25.0 -> StarWindowColors.WindowStroke
        altitudeDeg > 0.0 -> StarWindowColors.AnchorPoint
        else -> StarWindowColors.Muted
    }
    Column(modifier = modifier.width(66.dp), horizontalAlignment = Alignment.End) {
        Text(
            text = "%.0f°".format(altitudeDeg),
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
        Text(
            text = if (altitudeDeg > 0.0) "über Hor." else "unter Hor.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/** Arcminutes as an observer writes them: degrees once they get large. */
fun formatArcmin(sizeArcmin: Double): String =
    if (sizeArcmin >= 60.0) "%.1f°".format(sizeArcmin / 60.0) else "%.0f'".format(sizeArcmin)
