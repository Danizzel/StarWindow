package com.starwindow.app.ui.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.starwindow.app.core.camera.CameraIntrinsics
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the sky projection needs to know about the running preview stream. */
data class PreviewStreamInfo(
    val intrinsics: CameraIntrinsics,
    /** Buffer size in sensor orientation. */
    val streamWidth: Int,
    val streamHeight: Int,
    /** Rotation CameraX applies to make the buffer upright on screen. */
    val rotationDegrees: Int,
)

/**
 * The viewfinder.
 *
 * Uses [PreviewView.ScaleType.FIT_CENTER] on purpose: FILL_CENTER crops the stream by an amount
 * that depends on the device's aspect ratio, and every pixel of that crop is an error in the
 * angle-per-pixel scale. Letterbox bars are a small price for a viewfinder whose geometry is known
 * exactly.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onStreamInfo: (PreviewStreamInfo) -> Unit,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStreamInfo by rememberUpdatedState(onStreamInfo)
    val currentOnError by rememberUpdatedState(onError)

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
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
            )

            val resolution = preview.resolutionInfo
            if (resolution == null) {
                currentOnError("Die Kamera hat keine Auflösung gemeldet.")
                return@LaunchedEffect
            }

            val cameraId = backCameraId(context)
            currentOnStreamInfo(
                PreviewStreamInfo(
                    intrinsics = readIntrinsics(context, cameraId),
                    streamWidth = resolution.resolution.width,
                    streamHeight = resolution.resolution.height,
                    rotationDegrees = resolution.rotationDegrees,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Kamera konnte nicht gestartet werden", e)
            currentOnError(e.message ?: "Kamera konnte nicht gestartet werden")
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The camera id CameraX bound to.
 *
 * CameraX does not expose the id through a stable API, so we match on the lens facing and take the
 * first back-facing camera — which is what [CameraSelector.DEFAULT_BACK_CAMERA] selects too.
 * If a device ever hands CameraX a different physical camera, the field of view read here would be
 * wrong; the manual FOV calibration in the settings is the escape hatch for that.
 */
private fun backCameraId(context: Context): String? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
}

private fun readIntrinsics(context: Context, cameraId: String?): CameraIntrinsics {
    if (cameraId == null) return CameraIntrinsics.FALLBACK
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return CameraIntrinsics.FALLBACK
    return runCatching {
        CameraIntrinsics.fromCharacteristics(cameraId, manager.getCameraCharacteristics(cameraId))
    }.getOrElse {
        Log.w(TAG, "Kameradaten nicht lesbar, Standardwerte werden benutzt", it)
        CameraIntrinsics.FALLBACK
    }
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
