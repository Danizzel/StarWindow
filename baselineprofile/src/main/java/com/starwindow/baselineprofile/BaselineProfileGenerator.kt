package com.starwindow.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zeichnet auf, welcher Code auf den üblichen Wegen durch die App läuft.
 *
 * Daraus wird das Baseline Profile: Diese Methoden übersetzt Android schon bei der Installation vorab,
 * statt sie beim ersten Durchlauf zu interpretieren. Das trifft genau die Stellen, an denen eine
 * frisch installierte App sonst ruckelt — Start, erster Tabwechsel, erstes Scrollen.
 *
 * Erzeugt wird mit `./gradlew :app:generateBaselineProfile` bei angeschlossenem Gerät; das Ergebnis
 * landet unter `app/src/release/generated/baselineProfiles/` und gehört ins Repository.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Nur der Kaltstart bis in den laufenden Sucher.
     *
     * Getrennt von den übrigen Wegen, weil nur dieser Teil als Startprofil dienen soll: R8 legt die
     * Klassen daraus nach vorn in die erste DEX-Datei, und alles, was erst später gebraucht wird,
     * würde diesen Vorteil wieder verwässern.
     */
    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        grantPermissions()
        startInViewfinder()
    }

    /** Suche, Objektblatt und jeder Reiter mit einmal Scrollen — das, was nach dem Start kommt. */
    @Test
    fun journeys() = rule.collect(packageName = TARGET_PACKAGE) {
        grantPermissions()
        startInViewfinder()
        searchAndOpenObject()
        BOTTOM_TABS.forEach { tab ->
            openTab(tab)
            flingCurrentList()
        }
    }
}
