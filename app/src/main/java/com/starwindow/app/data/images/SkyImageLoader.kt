package com.starwindow.app.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
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
 * Deliberately hand rolled rather than pulling in an image loading library: one picture at a time,
 * cancelled when its sheet closes, is well inside what a hundred lines can do correctly — and it
 * keeps a dependency out of a build that is otherwise free of them. Swap in Coil here if the app
 * ever shows lists of images.
 */
class SkyImageLoader(
    private val cacheDir: File,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val survey: String = DEFAULT_SURVEY,
) {

    /**
     * Loads the image, from disk if it was fetched before.
     *
     * Never throws: a missing picture is a cosmetic loss, and the caller shows the reason instead.
     */
    suspend fun load(request: SkyImageRequest): Result<Bitmap> = withContext(Dispatchers.IO) {
        val cached = cacheFile(request)
        if (cached.exists() && cached.length() > 0) {
            decode(cached)?.let { return@withContext Result.success(it) }
            cached.delete()
        }

        runCatching { download(request, cached) }
            .mapCatching { decode(cached) ?: error("Bilddaten nicht lesbar") }
            .onFailure {
                cached.delete()
                Log.i(TAG, "Himmelsbild nicht abrufbar", it)
            }
    }

    /** The URL that would be fetched. Exposed so it is testable without a network. */
    fun urlFor(request: SkyImageRequest): String = buildString {
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

    private fun download(request: SkyImageRequest, target: File) {
        val connection = (URL(urlFor(request)).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                error("Bilddienst antwortete mit $status")
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun cacheFile(request: SkyImageRequest) =
        File(File(cacheDir, CACHE_DIR_NAME), "${request.cacheKey}.jpg")

    /** Drops the cached pictures. */
    fun clearCache() {
        runCatching { File(cacheDir, CACHE_DIR_NAME).deleteRecursively() }
    }

    private fun format(value: Double) = "%.5f".format(Locale.ROOT, value)

    companion object {
        private const val TAG = "SkyImageLoader"
        private const val CACHE_DIR_NAME = "sky_images"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "StarWindow/0.1 (Android)"

        const val DEFAULT_BASE_URL = "https://alasky.cds.unistra.fr/hips-image-services/hips2fits"

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
