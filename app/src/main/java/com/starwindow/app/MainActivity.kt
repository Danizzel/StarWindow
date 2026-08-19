package com.starwindow.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.starwindow.app.ui.nav.StarWindowNavHost
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Nobody wants the screen to sleep while they are lining a window up against the sky.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            StarWindowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StarWindowColors.Night,
                ) {
                    StarWindowNavHost()
                }
            }
        }
    }
}
