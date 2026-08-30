package com.starwindow.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Die sechs Orte, an denen die App etwas zu sagen hat.
 *
 * Bewusst sechs und nicht mehr: Alles Weitere — Kalibrierung, Nachtsicht, ein einzelnes Objekt, eine
 * geplante Nacht — sind Dinge, zu denen man aus einem dieser sechs *hingeht* und von denen man
 * zurückkommt. Sie in die Leiste zu heben würde die Unterscheidung zwischen „wo bin ich" und „was
 * mache ich gerade" einebnen, und damit das Einzige, was eine Leiste überhaupt leistet.
 */
enum class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
) {
    CAPTURE(Routes.CAPTURE, "Sucher", Icons.Filled.CameraAlt, "Sucher und Himmelsausschnitt"),
    HUB(Routes.HUB, "Motive", Icons.Filled.AutoAwesome, "Stargazing Hub: Motive der Jahreszeit"),
    GUIDE(Routes.GUIDE, "Guide", Icons.Filled.Tune, "Fotoguide: Einstellungen für dein Teleskop"),
    CALENDAR(Routes.CALENDAR, "Kalender", Icons.Filled.CalendarMonth, "Kalender und Planung"),
    WEATHER(Routes.WEATHER, "Wetter", Icons.Filled.CloudQueue, "Wetter für die Nacht"),
    WINDOWS(Routes.WINDOW_LIST, "Fenster", Icons.AutoMirrored.Filled.ViewList, "Gespeicherte Fenster"),
}

/**
 * Die Leiste am unteren Rand.
 *
 * Sie sitzt unten, weil dort die Daumen sind — die App wird einhändig im Dunkeln bedient, während
 * die andere Hand ein Stativ oder eine Taschenlampe hält, und der obere Bildschirmrand ist von dort
 * aus nicht erreichbar. Oben bleibt nur, was keine Navigation ist: das Suchfeld und die
 * Einstellungen.
 *
 * Selbst gebaut statt `NavigationBar` aus Material 3, aus demselben Grund wie die übrige Chrome
 * dieser App: Die vorgegebene Leiste ist 80 dp hoch und hell umrandet: auf dem Sucher wären das
 * achtzig Punkte Himmel weniger und ein heller Balken neben dunkeladaptierten Augen.
 */
@Composable
fun StarWindowBottomBar(
    current: BottomDestination?,
    onSelect: (BottomDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = StarWindowColors.Night.copy(alpha = 0.94f), modifier = modifier) {
        Column {
            HorizontalDivider(color = StarWindowColors.Outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomDestination.entries.forEach { destination ->
                    BottomBarItem(
                        destination = destination,
                        selected = destination == current,
                        onClick = { onSelect(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: BottomDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) StarWindowColors.WindowStroke else StarWindowColors.Muted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            // Eng, weil sechs Beschriftungen auf eine Telefonbreite müssen: Bei zehn Punkten je
            // Seite bricht „Kalender" auf schmalen Geräten um.
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        // Eine getönte Kapsel hinter dem Symbol statt nur einer anderen Farbe. Farbe allein
        // unterscheidet sechs kleine Symbole in einer dunklen Leiste kaum — eine Fläche schon,
        // und sie trifft auch den, der den Farbunterschied gar nicht sieht.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) {
                        StarWindowColors.tint(StarWindowColors.WindowStroke, 0.16f)
                    } else {
                        Color.Transparent
                    }
                )
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Icon(
                destination.icon,
                contentDescription = destination.description,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
        )
    }
}
