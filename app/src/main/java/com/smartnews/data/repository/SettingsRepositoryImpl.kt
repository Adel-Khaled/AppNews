package com.smartnews.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartnews.BuildConfig
import com.smartnews.data.remote.BackendConfig
import com.smartnews.domain.model.AppSettings
import com.smartnews.domain.model.Scope
import com.smartnews.domain.model.ThemeMode
import com.smartnews.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backendConfig: BackendConfig,
) : SettingsRepository {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object K {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val scope = stringPreferencesKey("scope")
        val interval = intPreferencesKey("refresh_hours")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val notif = booleanPreferencesKey("notifications")
        val notifBreaking = booleanPreferencesKey("notify_breaking")
        val maxNotif = intPreferencesKey("max_notifications")
        val autoplay = booleanPreferencesKey("autoplay")
        val imagesWifi = booleanPreferencesKey("images_wifi_only")
        val translate = booleanPreferencesKey("translate")
        val backendUrl = stringPreferencesKey("backend_url")
        val appToken = stringPreferencesKey("app_token")
        val lastSync = longPreferencesKey("last_sync")
    }

    private fun defaults(): AppSettings {
        val loc = Locale.getDefault()
        // Arabic unless the phone is set to English (or another non-Arabic language).
        val lang = if (loc.language == "en") "en" else "ar"
        val scope = if (loc.country == "EG" || loc.language == "ar") Scope.LOCAL else Scope.GLOBAL
        return AppSettings(language = lang, scope = scope, backendUrl = BuildConfig.DEFAULT_BACKEND_URL, appToken = BuildConfig.DEFAULT_APP_TOKEN)
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = defaults()
        return AppSettings(
            themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            language = this[K.language]?.takeIf { it == "ar" || it == "en" } ?: d.language,
            scope = this[K.scope]?.let { runCatching { Scope.valueOf(it) }.getOrNull() } ?: d.scope,
            refreshIntervalHours = this[K.interval] ?: d.refreshIntervalHours,
            wifiOnlySync = this[K.wifiOnly] ?: d.wifiOnlySync,
            notificationsEnabled = this[K.notif] ?: d.notificationsEnabled,
            notifyBreaking = this[K.notifBreaking] ?: d.notifyBreaking,
            maxNotificationsPerDay = this[K.maxNotif] ?: d.maxNotificationsPerDay,
            videoAutoplay = this[K.autoplay] ?: d.videoAutoplay,
            imagesOnWifiOnly = this[K.imagesWifi] ?: d.imagesOnWifiOnly,
            translateNews = this[K.translate] ?: d.translateNews,
            backendUrl = this[K.backendUrl] ?: d.backendUrl,
            appToken = this[K.appToken] ?: d.appToken,
            lastSyncAt = this[K.lastSync] ?: 0L,
        )
    }

    private val flow = context.dataStore.data
        .map { it.toSettings() }
        .onEach { backendConfig.update(it.backendUrl, it.appToken) }

    override val settings: StateFlow<AppSettings> = flow.stateIn(appScope, SharingStarted.Eagerly, defaults())

    override suspend fun snapshot(): AppSettings = flow.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.themeMode.name
            p[K.language] = s.language
            p[K.scope] = s.scope.name
            p[K.interval] = s.refreshIntervalHours
            p[K.wifiOnly] = s.wifiOnlySync
            p[K.notif] = s.notificationsEnabled
            p[K.notifBreaking] = s.notifyBreaking
            p[K.maxNotif] = s.maxNotificationsPerDay
            p[K.autoplay] = s.videoAutoplay
            p[K.imagesWifi] = s.imagesOnWifiOnly
            p[K.translate] = s.translateNews
            p[K.backendUrl] = s.backendUrl.trim()
            p[K.appToken] = s.appToken.trim()
            p[K.lastSync] = s.lastSyncAt
        }
    }

    companion object {
        val SUPPORTED_LANGUAGES = linkedMapOf(
            "en" to "English", "ar" to "العربية", "fr" to "Français", "es" to "Español",
            "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe",
        )
    }
}
