package com.banner.hintcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banner.hintcontrol.ui.nav.AppRoot
import com.banner.hintcontrol.ui.theme.HintTheme

class MainActivity : ComponentActivity() {
    private val link = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = HintApp.container(this)
        splash.setKeepOnScreenCondition { !container.profiles.state.value.loaded }
        if (savedInstanceState == null) link.value = intent?.data?.host
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            HintTheme(settings.theme, settings.dynamicColor, settings.pureBlack) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppRoot(container, link.value) { link.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        link.value = intent.data?.host
    }
}
