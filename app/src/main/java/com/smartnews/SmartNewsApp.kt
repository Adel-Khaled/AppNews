package com.smartnews

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.smartnews.data.repository.Seeder
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.Notifier
import com.smartnews.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class SmartNewsApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var seeder: Seeder
    @Inject lateinit var okHttp: OkHttpClient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        appScope.launch {
            seeder.ensureSeeded()
            val initial = settings.snapshot()
            scheduler.schedule(initial.refreshIntervalHours, initial.wifiOnlySync)
            settings.settings.drop(1)
                .map { it.refreshIntervalHours to it.wifiOnlySync }
                .distinctUntilChanged()
                .collect { (hours, wifi) -> scheduler.schedule(hours, wifi) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(okHttp)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .respectCacheHeaders(false) // keep thumbnails for offline reading
        .build()
}
