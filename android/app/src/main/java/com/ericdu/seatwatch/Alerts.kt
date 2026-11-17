package com.ericdu.seatwatch

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

const val NTFY = "https://ntfy.sh"
const val CH_ALERTS = "alerts"
const val CH_SERVICE = "service"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        PollWorker.schedule(this)
    }
}

// ------------------------------------------------------------------ notifications
object Notifier {
    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Seat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A watched class has an open seat"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android to keep instant alerts connected"
                setShowBadge(false)
            }
        )
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Handle one ntfy message JSON (dedup, history, notification). */
    fun handle(ctx: Context, j: JSONObject) {
        if (j.optString("event") != "message") return
        val prefs = Prefs(ctx)
        val id = j.optString("id")
        if (id.isEmpty() || !prefs.markSeen(id)) return
        val title = j.optString("title", "Seat Watch")
        val body = j.optString("message")
        val time = j.optLong("time", System.currentTimeMillis() / 1000) * 1000
        prefs.addHistory(title, body, time)
        val prio = j.optInt("priority", 3)
        val click = j.optString("click").takeIf { it.startsWith("http") }
        show(ctx, id.hashCode(), title, body, prio >= 4, click)
    }

    fun show(ctx: Context, nid: Int, title: String, body: String, urgent: Boolean, click: String?) {
        if (!canPost(ctx)) return
        val intent = if (click != null) Intent(Intent.ACTION_VIEW, Uri.parse(click))
        else Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(ctx, nid, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(nid, n) } catch (_: SecurityException) {}
    }
}

// ------------------------------------------------------------------ instant alerts: long-lived ntfy stream
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var conn: HttpURLConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Watching for open seats")
            .setContentText("Instant alerts are on")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)

        if (loop?.isActive != true) loop = scope.launch { streamLoop() }
        return START_STICKY
    }

    private suspend fun streamLoop() {
        var backoff = 5_000L
        while (scope.isActive) {
            val prefs = Prefs(this)
            val topic = prefs.topic
            if (topic.isEmpty()) { delay(30_000); continue }
            val since = prefs.lastId.ifEmpty { "10m" }
            try {
                val c = URL("$NTFY/$topic/json?since=$since").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 20_000
                c.readTimeout = 120_000            // ntfy sends keepalives every ~45 s
                c.setRequestProperty("User-Agent", "SeatWatch-Android/1.0")
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        backoff = 5_000L
                        runCatching { Notifier.handle(this, JSONObject(line)) }
                    }
                }
            } catch (_: Exception) {
            } finally { conn?.disconnect(); conn = null }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }

    override fun onDestroy() {
        conn?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            if (Prefs(ctx).topic.isEmpty()) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java)) } catch (_: Exception) {
                // Android blocks starting it from the background in some states; PollWorker still covers alerts.
            }
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AlertService::class.java))
    }
}

// ------------------------------------------------------------------ fallback: poll every 15 min (also catches up after phone was off)
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        pollOnce(applicationContext)
        Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Fetch cached messages since the last one we saw. Returns number of messages handled. */
        fun pollOnce(ctx: Context): Int {
            val prefs = Prefs(ctx)
            if (prefs.topic.isEmpty()) return 0
            val since = prefs.lastId.ifEmpty { "12h" }
            return try {
                val (code, text) = Http.request("$NTFY/${prefs.topic}/json?poll=1&since=$since", timeoutMs = 30_000)
                if (code >= 400) return 0
                var n = 0
                text.lineSequence().filter { it.isNotBlank() }.forEach {
                    runCatching { Notifier.handle(ctx, JSONObject(it)); n++ }
                }
                n
            } catch (_: Exception) { 0 }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PollWorker.schedule(ctx)
        if (Prefs(ctx).instant) AlertService.start(ctx)
    }
}
