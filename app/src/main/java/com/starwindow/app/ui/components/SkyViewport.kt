package com.starwindow.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.starwindow.app.core.camera.PreviewFit
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.ui.capture.PreviewStreamInfo
import kotlin.math.atan

/**
 * Everything the sky overlay and the tap handling need to turn pixels into directions, in one place
 * so the capture screen and the calibration screen cannot drift apart on it.
 *
 * @param focalPx focal length of the displayed image in view pixels; zero until the camera reports.
 * @param baselineFocalPx the same without the calibration factor — what the pan sweep measures
 *   against.
 */
data class SkyViewport(
    val focalPx: Double,
    val baselineFocalPx: Double,
    val viewSize: IntSize,
) {
    val isReady: Boolean get() = focalPx > 1.0 && viewSize != IntSize.Zero

    /** Horizontal field of view actually visible, in degrees, or null before the camera is ready. */
    val visibleFovDeg: Double?
        get() = if (!isReady) null else 2.0 * Math.toDegrees(atan(viewSize.width / (2.0 * focalPx)))
}

@Composable
fun rememberSkyViewport(
    streamInfo: PreviewStreamInfo?,
    viewSize: IntSize,
    fovScale: Double,
): SkyViewport {
    val displayRotation = rememberDisplayRotationDegrees()
    return remember(streamInfo, viewSize, fovScale, displayRotation) {
        if (streamInfo == null || viewSize == IntSize.Zero) {
            SkyViewport(0.0, 0.0, viewSize)
        } else {
            fun focalFor(scale: Double) = SkyProjection.focalLengthInViewPixels(
                intrinsics = streamInfo.intrinsics,
                streamWidth = streamInfo.streamWidth,
                streamHeight = streamInfo.streamHeight,
                rotationDegrees = streamInfo.rotationDegreesFor(displayRotation),
                viewWidthPx = viewSize.width.toFloat(),
                viewHeightPx = viewSize.height.toFloat(),
                fit = PreviewFit.FIT_CENTER,
                fovScale = scale,
            )
            SkyViewport(
                focalPx = focalFor(fovScale),
                baselineFocalPx = focalFor(1.0),
                viewSize = viewSize,
            )
        }
    }
}
