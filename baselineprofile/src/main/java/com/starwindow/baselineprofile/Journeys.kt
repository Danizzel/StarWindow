package com.starwindow.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/*
 * Die Wege durch die App, die Profil und Messungen gemeinsam benutzen.
 *
 * Gesucht wird über die sichtbaren Beschriftungen, nicht über Test-Tags: Die App braucht dafür keine
 * eigene Zeile Code, und ein umbenannter Reiter fällt hier sofort als Fehler auf statt still ein
 * anderes Ziel zu treffen.
 */

/** Die vermessene App. Die Benchmark-Buildtypen hängen kein Suffix an die Kennung. */
const val TARGET_PACKAGE = "com.starwindow.app"

/** Die Reiter der unteren Leiste, in ihrer Reihenfolge — der Sucher zuletzt, damit der Weg dort endet. */
val BOTTOM_TABS = listOf("Motive", "Guide", "Kalender", "Wetter", "Fenster", "Sucher")

private const val TIMEOUT_MS = 10_000L

/**
 * Erteilt Kamera und Standort vorab.
 *
 * Der Sucher fragt beim ersten Start danach, und ein Systemdialog über der App hielte jeden Durchlauf
 * an derselben Stelle an. Für die App ist das nicht von einem Nutzer zu unterscheiden, der „Zulassen"
 * getippt hat.
 */
fun MacrobenchmarkScope.grantPermissions() {
    listOf(
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    ).forEach { device.executeShellCommand("pm grant $packageName $it") }
}

/**
 * Startet die App in den Sucher und wartet, bis die Kamera ihr Bildfeld gemeldet hat.
 *
 * Erst dann laufen Kamera, Sensoren und Overlay gemeinsam — vorher würde ein anderer, leererer
 * Bildschirm gemessen als der, den man draußen sieht. Ohne Kamera läuft die Wartezeit einfach ab.
 */
fun MacrobenchmarkScope.startInViewfinder() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.textStartsWith("Bildfeld")), TIMEOUT_MS)
}

/**
 * Wechselt über die untere Leiste zu einem Reiter.
 *
 * Nur anklickbare Treffer zählen: „Kalender" steht auch als Überschrift auf dem Kalender selbst.
 */
fun MacrobenchmarkScope.openTab(label: String) {
    val tab = device.findObject(By.text(label).clickable(true))
        ?: error("Reiter „$label“ nicht gefunden")
    tab.click()
    device.waitForIdle()
}

/** Wischt die Liste des aktuellen Bildschirms einmal nach unten und wieder nach oben. */
fun MacrobenchmarkScope.flingCurrentList() {
    val list = device.findObject(By.scrollable(true)) ?: return
    // Abstand zum Rand, damit die Geste nicht als Zurück-Geste des Systems gilt.
    list.setGestureMargin(device.displayWidth / 5)
    list.fling(Direction.DOWN)
    device.waitForIdle()
    list.fling(Direction.UP)
    device.waitForIdle()
}

/**
 * Sucht vom Sucher aus nach M31, öffnet das Objektblatt und kehrt zurück.
 *
 * Der Weg streift die teuersten Stellen außerhalb des Suchers: die Suche über den ganzen Katalog, die
 * Ergebnisliste und das Objektblatt mit seinem Bild.
 */
fun MacrobenchmarkScope.searchAndOpenObject() {
    device.findObject(By.text("Nebel, Stern oder Galaxie suchen"))?.click() ?: return
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
    if (field != null) {
        field.text = "M31"
        device.wait(Until.findObject(By.textContains("Andromeda")), TIMEOUT_MS)?.click()
        device.waitForIdle()
    }
    backToBottomBar()
}

/** Geht zurück, bis die untere Leiste wieder zu sehen ist — Objektblatt, Tastatur, Suche. */
private fun MacrobenchmarkScope.backToBottomBar() {
    repeat(4) {
        if (device.hasObject(By.text("Sucher").clickable(true))) return
        device.pressBack()
        device.waitForIdle()
    }
}
