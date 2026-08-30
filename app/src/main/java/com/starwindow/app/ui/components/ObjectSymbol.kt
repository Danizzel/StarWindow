package com.starwindow.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.ui.theme.ObjectPalette
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Das kleine Bild vor einem Eintrag.
 *
 * **Vorher standen hier die Zeichen des gedruckten Sternatlas** — ein Quadrat für einen Nebel, ein
 * gestrichelter Kreis für einen offenen Sternhaufen, ein Kreis mit Kreuz für einen Kugelsternhaufen.
 * Die sind exakt und über jede Karte hinweg gleich, aber sie sind **gelernt und nicht erkannt**: Wer
 * nie einen Atlas in der Hand hatte, sieht ein Quadrat und weiß nichts. Und das ist die Mehrheit
 * derer, die eine App aufmachen, um herauszufinden, was da oben steht.
 *
 * Jetzt steht hier, wonach das Objekt aussieht: eine Spirale für eine Galaxie, eine Wolke für einen
 * Nebel, ein Rauchring für einen planetarischen, ein Haufen Punkte für einen Sternhaufen. Das kostet
 * die Anschlussfähigkeit an die Papierkarte — dafür trägt die Zeile ihre Aussage beim ersten
 * Hinsehen, und darum geht es in einer Liste mit vierzig Einträgen.
 *
 * **Gebaut für 16 Punkte, nicht für 64.** Bei dieser Größe entscheidet die Silhouette und sonst
 * nichts: Jedes Zeichen hat höchstens zwei Aussagepunkte, keine Linie ist dünner als ein Elftel der
 * Kantenlänge, und was bei zusammengekniffenen Augen zum Fleck wird, ist hier nicht drin. Deshalb
 * sind die hellen Nebel gefüllte Flächen und nicht Umrisse — eine gefüllte Form überlebt die
 * Verkleinerung, eine Kontur aus Haarlinien nicht.
 *
 * Gezeichnet statt aus einer Symbolschrift geholt, weil dieselben Zeichen auch über dem Kamerabild
 * liegen: Dort müssen sie in beliebiger Größe scharf bleiben und ihre Farbe aus der Palette nehmen.
 */
@Composable
fun ObjectSymbol(
    type: ObjectType,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = ObjectPalette.colorFor(type),
) {
    Canvas(modifier = modifier.size(size)) {
        drawObjectSymbol(
            type = type,
            color = color,
            center = Offset(this.size.width / 2f, this.size.height / 2f),
            extent = kotlin.math.min(this.size.width, this.size.height),
        )
    }
}

/**
 * Dasselbe Zeichen als einfacher Zeichenaufruf, für die Einblendung über dem Kamerabild.
 *
 * @param extent die Kantenlänge des Feldes, in dem gezeichnet wird, zentriert auf [center].
 */
fun DrawScope.drawObjectSymbol(
    type: ObjectType,
    color: Color,
    center: Offset,
    extent: Float,
) {
    val radius = extent * 0.38f
    val line = extent * 0.10f
    val stroke = Stroke(width = line)

    when (type) {
        // Der vierzackige Funke: die Form, die jeder von Hand malt, wenn er „Stern" sagen soll.
        // Eine runde Scheibe wäre astronomisch ehrlicher und in einer Liste nicht von einem
        // Aufzählungspunkt zu unterscheiden.
        ObjectType.STAR -> drawSparkle(center, radius * 0.95f, color)

        ObjectType.DOUBLE_STAR -> {
            drawSparkle(center + Offset(radius * 0.34f, -radius * 0.20f), radius * 0.62f, color)
            drawSparkle(center - Offset(radius * 0.40f, -radius * 0.30f), radius * 0.44f, color)
        }

        // Kern und zwei Arme. Die Spirale ist das Bild, das jeder im Kopf hat, wenn „Galaxie"
        // fällt — auch bei den zwei Dritteln der Katalogeinträge, die in Wahrheit elliptisch sind.
        ObjectType.GALAXY -> drawSpiral(center, radius, color, line)

        // Zwei Scheiben nebeneinander: mehrere Galaxien, ohne zweimal eine Spirale zu zeichnen,
        // die bei dieser Größe zum Knäuel würde.
        ObjectType.GALAXY_GROUP -> {
            drawTiltedDisc(center - Offset(radius * 0.42f, -radius * 0.22f), radius * 0.52f, color, line)
            drawTiltedDisc(center + Offset(radius * 0.46f, -radius * 0.26f), radius * 0.38f, color, line)
        }

        // Eine lockere Streuung. Die Punkte liegen fest und nicht zufällig: Ein Zeichen, das bei
        // jedem Neuzeichnen anders aussieht, ist keines.
        ObjectType.OPEN_CLUSTER -> {
            val dot = radius * 0.19f
            openClusterDots.forEach { (dx, dy) ->
                drawCircle(color, dot, center + Offset(radius * dx, radius * dy))
            }
        }

        // Dieselben Punkte, aber dicht und mit einem hellen Kern dahinter: Ein Kugelsternhaufen
        // ist im Okular genau das — ein Ball, der zum Rand hin in Einzelsterne zerfällt.
        ObjectType.GLOBULAR_CLUSTER -> {
            drawCircle(color.copy(alpha = 0.28f), radius * 0.92f, center)
            val dot = radius * 0.15f
            globularDots.forEach { (dx, dy) ->
                drawCircle(color, dot, center + Offset(radius * dx, radius * dy))
            }
        }

        // Eine Wolke aus Gas und Staub — und genau so sieht das Zeichen aus.
        ObjectType.NEBULA -> drawCloud(center, radius, color)

        // Von innen beleuchtet: Der Funke steckt in der Wolke, weil die jungen Sterne darin sie
        // zum Leuchten bringen.
        ObjectType.EMISSION_NEBULA -> {
            drawCloud(center, radius, color)
            drawSparkle(center + Offset(0f, radius * 0.05f), radius * 0.44f, highlight(color))
        }

        // Von außen beleuchtet: Der Funke steht daneben, denn ein Reflexionsnebel strahlt nicht
        // selbst, sondern wirft das Licht eines benachbarten Sterns zurück.
        ObjectType.REFLECTION_NEBULA -> {
            drawCloud(center + Offset(-radius * 0.10f, radius * 0.12f), radius * 0.90f, color)
            drawSparkle(center + Offset(radius * 0.60f, -radius * 0.64f), radius * 0.44f, highlight(color))
        }

        // Als Einziger ein Umriss statt einer Fläche: Einen Dunkelnebel sieht man nicht, man sieht
        // das Loch, das er in das Sternfeld schneidet. Die Punkte ringsum sind die Sterne, die
        // stehen bleiben.
        ObjectType.DARK_NEBULA -> {
            val dot = radius * 0.13f
            listOf(-0.92f to -0.72f, 0.94f to -0.55f, -0.88f to 0.78f, 0.86f to 0.80f).forEach { (dx, dy) ->
                drawCircle(color, dot, center + Offset(radius * dx, radius * dy))
            }
            drawPath(cloudPath(center, radius * 0.86f), color, style = Stroke(width = line))
        }

        // Ein Ring mit dem übrig gebliebenen Stern in der Mitte. Das ist keine Konvention, sondern
        // das, was im Teleskop zu sehen ist: eine abgestoßene Hülle, durch die man hindurchblickt.
        ObjectType.PLANETARY_NEBULA -> {
            drawCircle(color, radius * 0.74f, center, style = Stroke(width = line * 1.15f))
            drawCircle(color, radius * 0.2f, center)
        }

        // Eine Explosion: Strahlen unterschiedlicher Länge aus einem hellen Kern.
        ObjectType.SUPERNOVA_REMNANT -> {
            val spikes = 8
            repeat(spikes) { index ->
                val angle = index * 2.0 * Math.PI / spikes
                val reach = if (index % 2 == 0) radius else radius * 0.68f
                val from = radius * 0.28f
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    color = color,
                    start = center + direction * from,
                    end = center + direction * reach,
                    strokeWidth = line,
                )
            }
            drawCircle(color, radius * 0.22f, center)
        }

        // Sterne, die noch in ihrer Wolke stecken.
        ObjectType.CLUSTER_NEBULA -> {
            drawCloud(center, radius, color)
            val bright = highlight(color)
            val dot = radius * 0.17f
            listOf(-0.36f to 0.04f, 0.08f to -0.22f, 0.38f to 0.16f).forEach { (dx, dy) ->
                drawCircle(bright, dot, center + Offset(radius * dx, radius * dy))
            }
        }

        // Volle Scheibe mit Strahlenkranz: das eine Zeichen, das für Sonne und Mond zugleich
        // funktioniert. Beide sind am Himmel keine Punkte, sondern Flächen.
        ObjectType.SOLAR_SYSTEM -> {
            drawCircle(color, radius * 0.58f, center)
            val from = radius * 0.78f
            val to = radius * 1.06f
            repeat(8) { index ->
                val angle = index * 2.0 * Math.PI / 8
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(color, center + direction * from, center + direction * to, line)
            }
        }

        ObjectType.OTHER -> rotate(45f, center) {
            val rect = Rect(center = center, radius = radius * 0.66f)
            drawRect(color, rect.topLeft, Size(rect.width, rect.height), style = stroke)
        }
    }
}

/**
 * Die helle Fassung einer Palettenfarbe, für Marken **auf** einer gefüllten Fläche.
 *
 * Eine halbdurchsichtige Wolke mit einem Funken in derselben Farbe darin war der erste Versuch, und
 * am Gerät war das Ergebnis ein brauner Fleck: Die Deckkraft nahm der Wolke ihre Farbe und dem
 * Funken den Kontrast, sodass beide Aussagen zugleich verloren gingen. Volle Fläche plus aufgehellte
 * Marke trägt beide.
 */
private fun highlight(color: Color): Color = lerp(color, Color.White, 0.72f)

/** Vier Zacken, gerade Kanten — bei sechzehn Punkten bleibt davon mehr stehen als von Rundungen. */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.34f
    val path = Path()
    repeat(8) { index ->
        val angle = -Math.PI / 2.0 + index * Math.PI / 4.0
        val r = if (index % 2 == 0) radius else inner
        val point = center + Offset(cos(angle).toFloat() * r, sin(angle).toFloat() * r)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * Zwei logarithmische Spiralarme um einen Kern.
 *
 * Abgetastet statt aus Bézier-Kurven zusammengesetzt: Eine Spirale ist in Polarkoordinaten eine
 * Zeile, als Kurvenzug ein Rätsel — und bei zwanzig Stützpunkten sieht auf einem Telefonbildschirm
 * niemand den Unterschied.
 */
private fun DrawScope.drawSpiral(center: Offset, radius: Float, color: Color, line: Float) {
    val steps = 26
    // Zweihundertvierzig Grad Umlauf, nicht hundertvierzig. Beim ersten Versuch liefen die Arme
    // nach einer Vierteldrehung aus dem Bild, und übrig blieb ein geschwungenes „S" — die Spirale
    // entsteht erst, wenn ein Arm den Kern sichtbar *umrundet*.
    val turns = 4.2
    val growth = 0.30
    val start = radius * 0.22f

    // Die Scheibe darunter, sehr schwach: Sie gibt dem Zeichen einen runden Umriss, sodass es auch
    // dann noch als Objekt liest, wenn die Armlinien bei sechzehn Punkten fast verschwinden.
    drawCircle(color.copy(alpha = 0.16f), radius * 0.94f, center)

    repeat(2) { arm ->
        val path = Path()
        for (step in 0..steps) {
            val theta = turns * step / steps
            val r = start * exp(growth * theta).toFloat()
            val angle = theta + arm * Math.PI
            val point = center + Offset(cos(angle).toFloat() * r, sin(angle).toFloat() * r)
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = line))
    }
    // Zuletzt, damit der Kern über den Armansätzen liegt und der Mittelpunkt eindeutig bleibt.
    drawCircle(color, radius * 0.26f, center)
}

/** Eine geneigte Scheibe: die Galaxie, die zu klein für eine Spirale ist. */
private fun DrawScope.drawTiltedDisc(center: Offset, radius: Float, color: Color, line: Float) {
    rotate(-25f, center) {
        drawOval(
            color = color,
            topLeft = Offset(center.x - radius, center.y - radius * 0.55f),
            size = Size(radius * 2f, radius * 1.1f),
            style = Stroke(width = line * 0.9f),
        )
    }
}

private fun DrawScope.drawCloud(center: Offset, radius: Float, color: Color) {
    drawPath(cloudPath(center, radius), color)
}

/**
 * Der Umriss einer Wolke: drei Buckel über einem flachen Boden.
 *
 * Aus vier Grundformen vereinigt statt als Kurvenzug gezeichnet — die Vereinigung liefert genau die
 * Silhouette, auf die es ankommt, und sie lässt sich sowohl füllen als auch umranden, was der
 * Dunkelnebel braucht.
 */
private fun cloudPath(center: Offset, radius: Float): Path {
    fun blob(dx: Float, dy: Float, r: Float) = Path().apply {
        addOval(Rect(center = center + Offset(radius * dx, radius * dy), radius = radius * r))
    }

    val body = Path().apply {
        addOval(
            Rect(
                left = center.x - radius * 0.98f,
                top = center.y - radius * 0.10f,
                right = center.x + radius * 0.98f,
                bottom = center.y + radius * 0.62f,
            )
        )
    }

    var path = body
    listOf(
        Triple(-0.44f, -0.14f, 0.46f),
        Triple(0.06f, -0.36f, 0.58f),
        Triple(0.54f, -0.06f, 0.42f),
    ).forEach { (dx, dy, r) ->
        path = Path().apply { op(path, blob(dx, dy, r), PathOperation.Union) }
    }
    return path
}

/** Feste Streuung für den offenen Sternhaufen — locker, aber nicht zufällig. */
private val openClusterDots = listOf(
    -0.62f to -0.34f,
    0.02f to -0.66f,
    0.58f to -0.18f,
    -0.28f to 0.34f,
    0.40f to 0.52f,
    -0.70f to 0.48f,
)

/** Und die dichte für den Kugelsternhaufen. */
private val globularDots = listOf(
    0f to 0f,
    -0.38f to -0.26f,
    0.34f to -0.30f,
    -0.44f to 0.30f,
    0.40f to 0.28f,
    0.02f to -0.52f,
    -0.04f to 0.54f,
    0.62f to 0.02f,
    -0.62f to 0.02f,
)
