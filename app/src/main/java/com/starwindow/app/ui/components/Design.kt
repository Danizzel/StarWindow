package com.starwindow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starwindow.app.ui.theme.MetricTextStyle
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowSpacing

/**
 * Die Karte, aus der die Oberfläche besteht.
 *
 * Vorher war fast jeder Bildschirm eine lange Spalte aus Text mit Trennlinien dazwischen. Das ist
 * dicht, aber es beantwortet nicht die Frage, die man beim Draufschauen zuerst stellt: *was gehört
 * zusammen.* Eine Trennlinie sagt „hier endet etwas", eine Karte sagt „das hier ist eine Einheit",
 * und nur die zweite Aussage lässt sich mit einem Blick erfassen.
 *
 * Fläche plus Haarlinie statt Schlagschatten: Im Dunkeln ist ein Schatten unsichtbar, eine um zwei
 * Prozent hellere Fläche mit heller Kante dagegen sofort als eigene Ebene lesbar.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = StarWindowColors.NightSurface,
    border: Color? = StarWindowColors.Outline,
    contentPadding: androidx.compose.ui.unit.Dp = StarWindowSpacing.card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        color = color,
        shape = shape,
        border = border?.let { BorderStroke(1.dp, it) },
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(StarWindowSpacing.row),
            content = content,
        )
    }
}

/**
 * Die Überschrift über einer Karte oder Reihe.
 *
 * Versalien in kleinem Grad und weiter Laufweite: Das trennt die Überschrift vom Inhalt, ohne dafür
 * Größe zu verbrauchen — auf einem Telefon ist der Platz senkrecht knapp, und eine Überschrift, die
 * so groß ist wie das, was sie überschreibt, kostet mehr, als sie einbringt.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = StarWindowColors.Starlight,
                letterSpacing = 1.sp,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * Die Kopfzeile eines Bildschirms: Zurück, Titel, Unterzeile, Aktionen.
 *
 * Neun Bildschirme hatten neun leicht verschiedene Kopfzeilen — mal 4 Punkte Abstand, mal 8, mal
 * mit Untertitel, mal ohne. Zusammengefasst, weil eine Kopfzeile das Erste ist, was man sieht, und
 * ein Wackeln von einem Punkt beim Wechsel zwischen zwei Bildschirmen genau dort auffällt.
 *
 * **[onBack] gehört nur auf Bildschirme, zu denen man hingegangen ist.** Die sechs Ziele der
 * unteren Leiste haben keinen Rückweg, sondern Nachbarn: Man verlässt den Kalender, indem man auf
 * „Sucher" tippt, nicht indem man ihn schließt. Ein Pfeil daneben wäre ein zweiter Ausgang mit
 * einem anderen Ziel als dem, das er suggeriert — und die Leiste steht ohnehin schon da. Auf einem
 * Objektblatt, der Suche oder der Kalibrierung ist er dagegen der einzige Weg zurück und gehört
 * hin.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) 4.dp else StarWindowSpacing.screen,
                end = 8.dp,
                top = 4.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = StarWindowColors.Starlight,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = StarWindowColors.Starlight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions?.invoke(this)
    }
}

/**
 * Eine große Zahl mit ihrer Beschriftung.
 *
 * Für die Werte, die man aus zwei Metern Entfernung im Dunkeln ablesen will — Stunden, Prozent,
 * Grad. Die Zahl trägt die Farbe, die Beschriftung bleibt grau: Wer nur die Farbe wahrnimmt, hat
 * die Aussage schon.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = StarWindowColors.Starlight,
    icon: ImageVector? = null,
) {
    Surface(
        color = StarWindowColors.NightSurfaceHigh,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MetricTextStyle,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * Die kleine farbige Plakette: ein Zustand in zwei Wörtern.
 *
 * Die Farbe ist die eigentliche Nachricht, der Text die Begründung. Deshalb bekommt die Plakette
 * eine getönte Fläche in derselben Farbe und keine graue — grün auf Grau muss man lesen, grün auf
 * Grün erkennt man.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = StarWindowColors.Muted,
    icon: ImageVector? = null,
) {
    Surface(
        color = StarWindowColors.tint(tint, 0.16f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Beschriftung links, Wert rechts — die Zeile, aus der jede Datentafel besteht. */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = StarWindowColors.Starlight,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.weight(1.4f),
        )
    }
}
