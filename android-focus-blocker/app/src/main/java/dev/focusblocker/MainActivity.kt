package dev.focusblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.focusblocker.ui.HomeScreen
import dev.focusblocker.ui.theme.FocusBlockerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusBlockerTheme {
                HomeScreen()
            }
        }
    }
}
