package com.starwindow.app.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which patch of sky to render, and how wide. */
data class SkyImageRequest(
    val raDeg: Double,
    val decDeg: Double,
    /** Width of the rendered field in degrees. */
    val fieldOfViewDeg: Double,
    val sizePx: Int = 512,
) {
    /** Stable cache key; rounded so tiny differences do not defeat the cache. */
    val cacheKey: String
        get() = "%.4f_%.4f_%.4f_%d".format(Locale.ROOT, raDeg, decDeg, fieldOfViewDeg, sizePx)
            .replace('.', '-')
}

/**
 * Fetches a picture of a patch of sky.
 *
 * Uses the CDS **hips2fits** service, which renders any sky survey at arbitrary coordinates. That
 * is a better fit than looking up photographs by name: it works for every object rather than only
 * the famous ones, it is framed on the exact coordinates the app already knows, and the result is
 * the real sky in the right orientation instead of a press image at an arbitrary rotation.
 *
 * Deliberately hand rolled rather than pulling in an image loading library: a disk cache, a small
 * decoded cache in front of it, and one request per visible card is well inside what a hundred and
 * fifty lines can do correctly — and it keeps a dependency out of a build that is otherwise free of
 * them. Swap in Coil here if the pictures ever need transformations or animated formats.
 *
 * Der Dienst wird über **beide** von CDS betriebenen Adressen angesprochen, nicht über eine. Das
 * ist keine Vorsichtsmaßnahme auf Verdacht: `alasky` hat den TLS-Handschlag wochenlang mit einem
 * `Connection reset` abgebrochen, während `alaskybis` in einer Sekunde antwortete — und weil eine
 * fest verdrahtete Adresse hieß, dass jedes Bild in der App verschwand, war die eine ausgefallene
 * Maschine als „die Bilder laden nicht" sichtbar. Zwei Adressen, die erste funktionierende gemerkt.
 */
class SkyImageLoader(
    private val cacheDir: File,
    private val baseUrls: List<String> = DEFAULT_BASE_URLS,
    private val survey: String = DEFAULT_SURVEY,
) {

    /**
     * Welche Adresse zuletzt geantwortet hat.
     *
     * Ohne diese Zeile zahlt **jedes** Bild den Zeitausfall der toten Maschine, bevor es die
     * lebende fragt — bei einer Reihe Karten sind das Minuten statt Sekunden. Mit ihr zahlt ihn das
     * erste Bild, und der Rest geht direkt an die Adresse, von der gerade Bilder kommen.
     */
    @Volatile
    private var preferredHost = 0

    /**
     * The last few decoded pictures, newest last.
     *
     * Added when the hub started showing rows of them: scrolling a row back and forth would
     * otherwise decode the same JPEG off the disk every time a card came into view, which is the
     * one part of the path that costs real milliseconds on the main thread's behalf. Small on
     * purpose — a few hundred kilobytes of thumbnails, not an image cache pretending to be a
     * library.
     */
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > MEMORY_CACHE_SIZE
    }

    /**
     * Loads the image, from memory or disk if it was fetched before.
     *
     * Never throws: a missing picture is a cosmetic loss, and the caller shows the reason instead.
     */
    suspend fun load(request: SkyImageRequest): Result<Bitmap> = withContext(Dispatchers.IO) {
        synchronized(memoryCache) { memoryCache[request.cacheKey] }
            ?.let { return@withContext Result.success(it) }

        val cached = cacheFile(request)
        if (cached.exists() && cached.length() > 0) {
            decode(cached)?.let {
                synchronized(memoryCache) { memoryCache[request.cacheKey] = it }
                return@withContext Result.success(it)
            }
            cached.delete()
        }

        runCatching { download(request, cached) }
            .mapCatching { decode(cached) ?: error("Bilddaten nicht lesbar") }
            .onSuccess { synchronized(memoryCache) { memoryCache[request.cacheKey] = it } }
            .onFailure {
                cached.delete()
                Log.i(TAG, "Himmelsbild nicht abrufbar", it)
            }
    }

    /**
     * The URL that would be fetched. Exposed so it is testable without a network.
     *
     * @param baseUrl welche der Adressen; ohne Angabe die zuletzt erfolgreiche.
     */
    fun urlFor(request: SkyImageRequest, baseUrl: String = baseUrls[preferredHost]): String =
        buildString {
            append(baseUrl)
            append("?hips=").append(java.net.URLEncoder.encode(survey, "UTF-8"))
            append("&ra=").append(format(request.raDeg))
            append("&dec=").append(format(request.decDeg))
            append("&fov=").append(format(request.fieldOfViewDeg))
            append("&width=").append(request.sizePx)
            append("&height=").append(request.sizePx)
            append("&projection=TAN")
            append("&format=jpg")
        }

    /**
     * Holt das Bild, notfalls von der zweiten Adresse.
     *
     * Weitergereicht wird nur bei einem **Netzfehler oder 5xx** — bei beidem ist die Maschine das
     * Problem und die andere hat eine Chance. Eine 4xx-Antwort dagegen liegt an der Anfrage selbst,
     * und die zweite Maschine würde exakt dieselbe geben; sie noch einmal zu fragen verdoppelt nur
     * die Wartezeit vor einer Fehlermeldung, die ohnehin kommt.
     */
    private fun download(request: SkyImageRequest, target: File) {
        var lastError: Exception? = null
        for (offset in baseUrls.indices) {
            val host = (preferredHost + offset) % baseUrls.size
            try {
                fetch(urlFor(request, baseUrls[host]), target)
                preferredHost = host
                return
            } catch (error: IOException) {
                // Netzfehler und 5xx; ein 4xx kommt als IllegalStateException durch und beendet
                // den Versuch sofort, weil die zweite Adresse daran nichts ändert.
                lastError = error
            }
        }
        throw lastError ?: IOException("Keine Adresse des Bilddienstes hinterlegt")
    }

    private fun fetch(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val message = "Bilddienst antwortete mit $status"
                if (status >= 500) throw ServiceUnavailable(message) else error(message)
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Die Maschine ist das Problem, nicht die Anfrage — die andere Adresse ist einen Versuch wert. */
    private class ServiceUnavailable(message: String) : IOException(message)

    private fun decode(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun cacheFile(request: SkyImageRequest) =
        File(File(cacheDir, CACHE_DIR_NAME), "${request.cacheKey}.jpg")

    /** Drops the cached pictures. */
    fun clearCache() {
        synchronized(memoryCache) { memoryCache.clear() }
        runCatching { File(cacheDir, CACHE_DIR_NAME).deleteRecursively() }
    }

    private fun format(value: Double) = "%.5f".format(Locale.ROOT, value)

    companion object {
        private const val TAG = "SkyImageLoader"
        private const val CACHE_DIR_NAME = "sky_images"
        /**
         * Kurz, weil er im Fehlerfall zweimal anfällt.
         *
         * Eine Maschine, die in fünf Sekunden keine Verbindung zustande bringt, wird das Bild auch
         * danach nicht liefern — und solange sie es versucht, sieht der Nutzer einen Kringel.
         */
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "StarWindow/0.1 (Android)"

        /** Decoded pictures kept in memory. Enough for two rows of hub cards plus a sheet. */
        private const val MEMORY_CACHE_SIZE = 32

        /**
         * Die beiden Adressen, unter denen CDS hips2fits betreibt.
         *
         * Der Dienst ist ausdrücklich als zwei unabhängige Endpunkte dokumentiert, und sie fallen
         * unabhängig voneinander aus: `alasky` brach den TLS-Handschlag ab, während `alaskybis`
         * dieselbe Anfrage in einer Sekunde beantwortete. Die Reihenfolge ist die dokumentierte;
         * welche tatsächlich benutzt wird, entscheidet der erste erfolgreiche Abruf.
         */
        val DEFAULT_BASE_URLS = listOf(
            "https://alasky.cds.unistra.fr/hips-image-services/hips2fits",
            "https://alaskybis.cds.unistra.fr/hips-image-services/hips2fits",
        )

        /** Colour DSS2, the widest all-sky survey with usable colour. */
        const val DEFAULT_SURVEY = "CDS/P/DSS2/color"

        /**
         * A sensible frame for an object: a little wider than the object itself, and never so
         * narrow that a small object is rendered as a blur of a few pixels.
         */
        fun frameForObject(sizeArcmin: Double?): Double =
            ((sizeArcmin ?: 10.0) * 2.2 / 60.0).coerceIn(0.15, 5.0)
    }
}
