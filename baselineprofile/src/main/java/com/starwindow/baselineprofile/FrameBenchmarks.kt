package com.starwindow.baselineprofile

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bildzeiten an den drei Stellen, an denen die App flüssig wirken muss.
 *
 * Das sind die Ausgangswerte für die offenen Performance-Punkte im DEV_PLAN: Sensorrate und
 * Vorberechnung im Sucher, Übergänge zwischen den Reitern, Listen. Eine Änderung dort sollte sich in
 * `frameDurationCpuMs` und `frameOverrunMs` hier zeigen — sonst war sie keine.
 */
@RunWith(AndroidJUnit4::class)
class FrameBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Fünf Sekunden ruhiger Sucher.
     *
     * Das Overlay zeichnet bei jedem Sensorwert neu, auch wenn das Telefon still liegt — gemessen wird
     * also genau die Arbeit pro Bild: Projektion, Katalog, Gitter, Beschriftungen.
     */
    @Test
    fun viewfinder() = measureFrames(setup = { startInViewfinder() }) {
        Thread.sleep(5_000)
    }

    /** Einmal durch alle Reiter der Leiste: die Übergänge zwischen den Bildschirmen. */
    @Test
    fun switchTabs() = measureFrames(setup = { startInViewfinder() }) {
        BOTTOM_TABS.forEach { openTab(it) }
    }

    /** Der Kalender einmal hinunter und wieder hinauf: zwölf Monatsraster in einer Lazy-Liste. */
    @Test
    fun scrollCalendar() = measureFrames(
        setup = {
            startInViewfinder()
            openTab("Kalender")
        },
    ) {
        flingCurrentList()
    }

    private fun measureFrames(
        setup: MacrobenchmarkScope.() -> Unit,
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        setupBlock = {
            grantPermissions()
            setup()
        },
        measureBlock = measure,
    )
}
