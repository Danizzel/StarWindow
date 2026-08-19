package com.starwindow.app.ui.components

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The current display rotation in degrees (0, 90, 180, 270).
 *
 * Reads [LocalConfiguration] so that turning the phone recomposes whoever calls this. The activity
 * declares `configChanges` for orientation and therefore is not recreated, so nothing else would
 * notice the change — and the field of view calculation depends on it.
 */
@Composable
fun rememberDisplayRotationDegrees(): Int {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(configuration) { context.displayRotationDegrees() }
}

@Suppress("DEPRECATION")
fun Context.displayRotationDegrees(): Int {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
