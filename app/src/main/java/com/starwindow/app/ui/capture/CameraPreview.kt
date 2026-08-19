package com.starwindow.app.ui.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.starwindow.app.core.camera.CameraIntrinsics
import com.starwindow.app.core.camera.ExposureCapabilities
import com.starwindow.app.core.camera.ExposureSettings
import com.starwindow.app.core.camera.NightVisionController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the sky projection needs to know about the running preview stream. */
data class PreviewStreamInfo(
    val intrinsics: CameraIntrinsics,
    /** Buffer size in sensor orientation. */
    val streamWidth: Int,
    val streamHeight: Int,
    /** How far the sensor is mounted rotated relative to the device's natural orientation. */
    val sensorOrientationDegrees: Int,
    val exposureCapabilities: ExposureCapabilities,
) {
    /**
     * Rotation applied to the buffer to make it upright for the given display rotation.
     *
     * Computed live rather than cached: the activity handles configuration changes itself, so
     * nothing gets rebuilt when the phone is turned. A cached value would keep the portrait
     * rotation while the view size switches to landscape, and every angle-per-pixel calculation
     * downstream would be wrong.
     */
    fun rotationDegreesFor(displayRotationDegrees: Int): Int =
        ((sensorOrientationDegrees - displayRotationDegrees) % 360 + 360) % 360
}

/**
 * The viewfinder.
 *
 * Uses [PreviewView.ScaleType.FIT_CENTER] on purpose: FILL_CENTER crops the stream by an amount
 * that depends on the device's aspect ratio, and every pixel of that crop is an error in the
 * angle-per-pixel scale. Letterbox bars are a small price for a viewfinder whose geometry is known
 * exactly.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    exposure: ExposureSettings,
    onStreamInfo: (PreviewStreamInfo) -> Unit,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStreamInfo by rememberUpdatedState(onStreamInfo)
    val currentOnError by rememberUpdatedState(onError)
    var nightVision by remember { mutableStateOf<NightVisionController?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // TextureView keeps the preview in the regular view hierarchy, so the Compose overlay
            // drawn on top of it is guaranteed to stay on top.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(previewView) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).awaitResult()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
            )

            val resolution = preview.resolutionInfo
            if (resolution == null) {
                currentOnError("Die Kamera hat keine Auflösung gemeldet.")
                return@LaunchedEffect
            }

            // Ask CameraX which camera it actually bound, instead of guessing the first
            // back-facing one — phones with several rear lenses would otherwise have us read the
            // field of view of a lens that is not in use.
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val characteristics = cameraCharacteristics(context, cameraId)

            val capabilities = characteristics
                ?.let { ExposureCapabilities.fromCharacteristics(it) }
                ?: ExposureCapabilities.NONE

            nightVision = NightVisionController(camera, capabilities)

            currentOnStreamInfo(
                PreviewStreamInfo(
                    intrinsics = characteristics
                        ?.let { CameraIntrinsics.fromCharacteristics(cameraId, it) }
                        ?: CameraIntrinsics.FALLBACK,
                    streamWidth = resolution.resolution.width,
                    streamHeight = resolution.resolution.height,
                    sensorOrientationDegrees = characteristics
                        ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    exposureCapabilities = capabilities,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Kamera konnte nicht gestartet werden", e)
            currentOnError(e.message ?: "Kamera konnte nicht gestartet werden")
        }
    }

    // Applied to the running camera, so moving a slider changes the image straight away instead of
    // tearing the preview down and building it again.
    LaunchedEffect(nightVision, exposure) {
        nightVision?.apply(exposure)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun cameraCharacteristics(context: Context, cameraId: String): CameraCharacteristics? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return runCatching { manager.getCameraCharacteristics(cameraId) }
        .onFailure { Log.w(TAG, "Kameradaten für $cameraId nicht lesbar", it) }
        .getOrNull()
}

/** Suspends until a [ListenableFuture] completes, without pulling in the Guava coroutine adapter. */
private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        Runnable::run,
    )
    cont.invokeOnCancellation { cancel(false) }
}

private const val TAG = "CameraPreview"
