package com.boardresult.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.boardresult.MainActivity
import com.boardresult.core.Graph
import com.boardresult.server.WebServer
import com.boardresult.sms.SmsQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

object ServerState {
    val running = MutableStateFlow(false)
    val error = MutableStateFlow("")

    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Foreground service: keeps the web portal alive and drains/retries the SMS queue. */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        val ok = WebServer.start(Graph.prefs.port)
        ServerState.running.value = ok
        ServerState.error.value = if (ok) "" else WebServer.lastError
        if (!ok) { stopSelf(); return START_NOT_STICKY }

        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                try {
                    if (Graph.prefs.smsEnabled) SmsQueue.process(applicationContext, maxBatch = 500)
                    val now = System.currentTimeMillis()
                    if (Graph.prefs.autoBackup && now - Graph.prefs.lastAutoBackup > 24 * 3600_000L) {
                        com.boardresult.core.Backup.localBackup(applicationContext)
                        Graph.prefs.lastAutoBackup = now
                    }
                } catch (_: Exception) {
                }
                delay(15_000)
            }
        }
        return START_STICKY
    }

    private fun stopServer() {
        loop?.cancel()
        WebServer.stop()
        ServerState.running.value = false
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Result server", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Board Result server running")
            .setContentText("Port ${Graph.prefs.port} · SMS ${if (Graph.prefs.smsEnabled) "on" else "off"}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "result_server"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.boardresult.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Graph.prefs.autoStart) {
            try { ServerService.start(context) } catch (_: Exception) {}
        }
    }
}
