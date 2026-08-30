package com.starwindow.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Deliberately dark and low on blue: the app is used at night, next to eyes that are trying to stay
 * dark adapted. Red is reserved for the overlay so it never competes with the UI chrome.
 *
 * **Vier Ebenen statt einer Fläche.** Der Hintergrund war einmal fast schwarz, und darauf lagen
 * zwei kaum unterscheidbare Grautöne — auf einem Telefon im Dunkeln sah das aus wie eine einzige
 * schwarze Fläche mit Text darin. Im Dunkelmodus gibt es keine Schatten, mit denen sich Tiefe
 * zeigen ließe: Was näher an der Oberfläche liegt, muss *heller* sein. Deshalb steigen [Night],
 * [NightSurface], [NightSurfaceHigh] und [NightSurfaceTop] in gleichmäßigen Schritten an, und was
 * dazwischen noch getrennt werden muss, bekommt eine Haarlinie in [Outline] statt einen Schatten,
 * den man ohnehin nicht sähe.
 *
 * Der Grundton ist ein tiefes Indigo und kein Neutralgrau. Ein reines Schwarz wirkt auf einem
 * OLED-Bildschirm wie ein Loch, an dessen Rändern jede Kante hart abbricht; ein Blaustich hält die
 * Flächen zusammen und passt zu dem, was die App zeigt.
 */
object StarWindowColors {

    /** Der Grund, auf dem alles liegt. */
    val Night = Color(0xFF0A0C16)

    /** Karten und Listenflächen. */
    val NightSurface = Color(0xFF141827)

    /** Was auf einer Karte noch einmal hervorsteht — Bildplätze, Eingabefelder, Kopfzeilen. */
    val NightSurfaceHigh = Color(0xFF1E2338)

    /** Dialoge, Blätter und ausgewählte Schalter: die oberste Ebene. */
    val NightSurfaceTop = Color(0xFF2A3050)

    /**
     * Die Haarlinie um eine Karte.
     *
     * Der Ersatz für den Schlagschatten, den ein dunkles Thema nicht hergibt: gerade hell genug,
     * dass die Kante zu sehen ist, und gerade dunkel genug, dass sie nicht selbst auffällt.
     */
    val Outline = Color(0xFF272D45)

    val Starlight = Color(0xFFE3E7F7)
    val Muted = Color(0xFF919AB9)

    /** Overlay accents, chosen to stay readable on top of a nearly black camera image. */
    val WindowStroke = Color(0xFF62E8B4)
    val WindowFill = Color(0x2262E8B4)
    val AnchorPoint = Color(0xFFFFB74D)
    val Crosshair = Color(0xFFFF6B6B)
    val Graticule = Color(0x55B0BEC5)
    val CatalogMarker = Color(0xFF9FD8FF)

    /**
     * The tracked object and the arrow pointing at it. Magenta because it is the one colour left
     * that none of the others can be mistaken for — the arrow has to be found instantly in a view
     * that already carries a grid, a window outline and a sky full of markers.
     */
    val TrackTarget = Color(0xFFE879F9)

    /**
     * Die gedämpfte Fassung einer Akzentfarbe, für Flächen statt für Schrift.
     *
     * Ein Akzent in voller Sättigung als Kartenhintergrund erschlägt alles darauf; auf zwölf bis
     * achtzehn Prozent gebracht, trägt dieselbe Farbe die Aussage weiter, ohne den Text zu
     * verdrängen. Eine Funktion statt zwölf weiterer Konstanten, damit jede Akzentfarbe
     * automatisch ihre Flächenfassung hat.
     */
    fun tint(accent: Color, alpha: Float = 0.14f): Color = accent.copy(alpha = alpha)
}

/**
 * Ein Radius je Größenordnung, damit nicht jeder Bildschirm seinen eigenen erfindet.
 *
 * Vorher standen im Quelltext acht verschiedene Werte zwischen 3 und 50 Punkten, und man sah es:
 * Nebeneinanderliegende Elemente hatten unterschiedlich runde Ecken, was wie ein Versehen aussieht,
 * weil es eines war. Drei Stufen reichen — Chip, Karte, Blatt.
 */
val StarWindowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Die Abstände, in denen die Oberfläche gebaut ist. */
object StarWindowSpacing {
    /** Seitenrand aller Bildschirme. Einmal festgelegt, damit Karten untereinander fluchten. */
    val screen = 16.dp

    /** Innenabstand einer Karte. */
    val card = 16.dp

    /** Zwischen zwei Karten. */
    val between = 12.dp

    /** Zwischen Zeilen innerhalb einer Karte. */
    val row = 8.dp
}

/**
 * Typografie mit etwas mehr Gewicht in den Überschriften.
 *
 * Die Voreinstellung von Material behandelt Titel und Fließtext fast gleich, was auf einer Seite
 * voller Zahlen dazu führt, dass nichts heraussticht. Hier tragen Titel Halbfett und etwas engere
 * Laufweite, Zahlenwerte bekommen eine eigene große Stufe, und die kleinen Beschriftungen laufen
 * weiter auseinander — das ist der Unterschied zwischen „dicht" und „gedrängt".
 */
private val StarWindowTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.3.sp),
    )
}

/** Die große Zahl auf einer Kachel — der einzige Stil, der nicht aus Material kommt. */
val MetricTextStyle = TextStyle(
    fontSize = 30.sp,
    lineHeight = 34.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-1).sp,
)

private val DarkScheme = darkColorScheme(
    primary = StarWindowColors.WindowStroke,
    onPrimary = StarWindowColors.Night,
    secondary = StarWindowColors.AnchorPoint,
    onSecondary = StarWindowColors.Night,
    background = StarWindowColors.Night,
    onBackground = StarWindowColors.Starlight,
    surface = StarWindowColors.NightSurface,
    onSurface = StarWindowColors.Starlight,
    surfaceVariant = StarWindowColors.NightSurfaceHigh,
    onSurfaceVariant = StarWindowColors.Muted,
    surfaceContainerHigh = StarWindowColors.NightSurfaceHigh,
    surfaceContainerHighest = StarWindowColors.NightSurfaceTop,
    outline = StarWindowColors.Outline,
    outlineVariant = StarWindowColors.Outline,
    error = StarWindowColors.Crosshair,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFFB26500),
)

@Composable
fun StarWindowTheme(
    // The app defaults to its dark scheme even in a light system theme; see the note above.
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = forceDark || isSystemInDarkTheme()
    val colorScheme = if (useDark) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StarWindowTypography,
        shapes = StarWindowShapes,
        content = content,
    )
}
