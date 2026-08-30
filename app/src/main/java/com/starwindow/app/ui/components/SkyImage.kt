package com.starwindow.app.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.data.images.SkyImageRequest

/** What the picture of one object is currently doing. */
data class SkyImageState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

/**
 * The picture of one object, loaded when it first comes into view.
 *
 * A composable rather than a helper on the loader, because the *when* matters: the hub holds two
 * dozen targets and shows four of them at a time, and starting two dozen downloads to draw four
 * pictures would be both slow and rude to a public service. Tied to the composition, the request
 * starts when a card is scrolled into view and is cancelled when it leaves.
 *
 * @param widen how much wider than the object itself to frame. Cards are wide and short, and a
 *   frame cropped to the object's own extent leaves nothing of the field around it.
 */
@Composable
fun rememberSkyImage(
    obj: SkyObject,
    loader: SkyImageLoader?,
    sizePx: Int = 512,
    widen: Double = 1.0,
): State<SkyImageState> {
    val state = remember(obj.id, sizePx, widen) { mutableStateOf(SkyImageState()) }

    LaunchedEffect(obj.id, loader, sizePx, widen) {
        if (loader == null) {
            state.value = SkyImageState(isLoading = false, failed = true)
            return@LaunchedEffect
        }
        state.value = SkyImageState(isLoading = true)
        val result = loader.load(
            SkyImageRequest(
                raDeg = obj.raDeg,
                decDeg = obj.decDeg,
                fieldOfViewDeg = (SkyImageLoader.frameForObject(obj.sizeArcmin) * widen)
                    .coerceIn(0.15, 6.0),
                sizePx = sizePx,
            )
        )
        state.value = SkyImageState(
            bitmap = result.getOrNull(),
            isLoading = false,
            failed = result.isFailure,
        )
    }

    return state
}

/** Where the pictures come from, for the one line of attribution the survey asks for. */
const val SKY_IMAGE_CREDIT = "Bilder: DSS2 über hips2fits (CDS Strasbourg)"
