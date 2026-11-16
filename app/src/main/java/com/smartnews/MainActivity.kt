package com.smartnews

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.ui.navigation.AppRoot
import com.smartnews.ui.navigation.LaunchRequest
import com.smartnews.ui.theme.SmartNewsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchRequest = parse(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            SmartNewsTheme(themeMode = settings.themeMode) {
                AppRoot(launchRequest = launchRequest, onLaunchHandled = { launchRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = parse(intent)
    }

    private fun parse(intent: Intent?): LaunchRequest? {
        intent ?: return null
        intent.getStringExtra(EXTRA_STORY_ID)?.let { return LaunchRequest.OpenStory(it) }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            val url = Regex("https?://\\S+").find(text)?.value ?: return null
            return LaunchRequest.AddSource(url)
        }
        return null
    }

    companion object { const val EXTRA_STORY_ID = "story_id" }
}
