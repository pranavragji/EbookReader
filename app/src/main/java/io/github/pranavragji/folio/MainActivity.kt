package io.github.pranavragji.folio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.pranavragji.folio.ui.AppNav
import io.github.pranavragji.folio.ui.theme.FolioTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            lifecycleScope.launch { app.scanner.scanAll() }
            app.sync.requestSync()
        }
        setContent { FolioTheme { AppNav() } }
    }

    override fun onStop() {
        super.onStop()
        app.sync.requestSync()
    }
}
