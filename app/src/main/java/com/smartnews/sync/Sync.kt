package com.smartnews.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartnews.MainActivity
import com.smartnews.R
import com.smartnews.domain.model.Story
import com.smartnews.domain.repository.NewsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: NewsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val r = repository.refresh()
        runCatching { repeat(8) { if (repository.translatePending(25) == 0) return@runCatching } }
        // Retry later only if *everything* failed (e.g. flaky network); partial failures are normal.
        if (r.sourcesOk == 0 && r.sourcesFailed > 0) Result.retry() else Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule(intervalHours: Int, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (intervalHours <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun syncNow() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, req)
    }

    private companion object {
        const val PERIODIC = "smartnews-periodic-sync"
        const val ONE_TIME = "smartnews-sync-now"
    }
}

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_BREAKING, "Breaking news", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Followed topics & sources", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private val prefs get() = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    fun remainingToday(maxPerDay: Int): Int {
        val today = LocalDate.now().toString()
        val sent = if (prefs.getString("day", null) == today) prefs.getInt("count", 0) else 0
        return (maxPerDay - sent).coerceAtLeast(0)
    }

    fun showStory(story: Story, lang: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_STORY_ID, story.id)
        }
        val pi = PendingIntent.getActivity(context, story.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ar = lang == "ar"
        val headline = story.headlineFor(lang)
        val text = if (story.sourceCount > 1) (if (ar) "${story.sourceCount} مصادر تنقل الخبر" else "${story.sourceCount} sources are reporting") else story.leadSourceName
        val n = NotificationCompat.Builder(context, if (story.isBreaking) CH_BREAKING else CH_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (story.isBreaking) (if (ar) "عاجل · $headline" else "Breaking · $headline") else headline)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(headline))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(story.id.hashCode(), n) }
        val today = LocalDate.now().toString()
        val sent = if (prefs.getString("day", null) == today) prefs.getInt("count", 0) else 0
        prefs.edit().putString("day", today).putInt("count", sent + 1).apply()
    }

    companion object {
        const val CH_BREAKING = "breaking"
        const val CH_UPDATES = "updates"
    }
}

@Singleton
class ConnectivityObserver @Inject constructor(@ApplicationContext private val context: Context) {
    val isOnline: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun current(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(current()) }
        }
        trySend(current())
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
}
