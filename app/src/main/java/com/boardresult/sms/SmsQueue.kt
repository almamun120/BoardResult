package com.boardresult.sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.boardresult.core.Graph
import com.boardresult.data.SmsQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Persistent outgoing-SMS queue with retry + exponential backoff. Survives restarts (Room). */
object SmsQueue {
    private val mutex = Mutex()

    suspend fun enqueue(phone: String, message: String, subId: Int = -1, priority: Int = 5): Boolean {
        val dao = Graph.db.sms()
        if (dao.queueCount("PENDING") >= Graph.prefs.maxQueue) return false
        dao.addQueue(SmsQueueItem(phone = phone, message = message, subId = subId, priority = priority))
        return true
    }

    @Volatile private var failStreak = 0
    @Volatile private var pausedUntil = 0L

    suspend fun hasPending(phone: String, message: String) = Graph.db.sms().pendingDup(phone, message) > 0

    /** Fire-and-forget drain: never blocks the SMS receiver, and only one drain runs at a time. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { process(app, maxBatch = 500) } catch (_: Exception) {}
        }
    }

    /**
     * Sends due messages, highest priority first, paced by the configured delay.
     * Circuit breaker: after 5 consecutive failures sending pauses for 30 s (e.g. no signal / airplane mode)
     * instead of burning every message's retry budget.
     */
    suspend fun process(ctx: Context, maxBatch: Int = 20) {
        if (!mutex.tryLock()) return          // another drain is already running
        try {
            val dao = Graph.db.sms()
            dao.stuck(System.currentTimeMillis() - 120_000L).forEach { fail(it, "send timeout") }
            var n = 0
            while (n < maxBatch) {
                val now = System.currentTimeMillis()
                if (now < pausedUntil) break
                val next = dao.nextDue(now) ?: break
                val item = next.copy(status = "PROCESSING", startedAt = now, attempts = next.attempts + 1)
                dao.saveQueue(item)
                try {
                    send(ctx, item)
                } catch (e: Exception) {
                    fail(item, e.message ?: e.javaClass.simpleName)
                }
                n++
                delay(Graph.prefs.sendDelayMs.toLong())
            }
            dao.purgeDone(System.currentTimeMillis() - 86_400_000L)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun fail(item: SmsQueueItem, err: String) {
        val p = Graph.prefs
        val dao = Graph.db.sms()
        failStreak++
        if (failStreak >= 5) { pausedUntil = System.currentTimeMillis() + 30_000L; failStreak = 0 }
        if (item.attempts >= p.maxRetries) {
            dao.saveQueue(item.copy(status = "DEAD", error = err))
        } else {
            val backoff = p.retryDelaySec * 1000L * (1L shl (item.attempts - 1).coerceIn(0, 10))
            dao.saveQueue(item.copy(status = "PENDING", error = err, scheduledAt = System.currentTimeMillis() + backoff))
        }
    }

    suspend fun onSent(id: Long, ok: Boolean, code: Int) {
        val dao = Graph.db.sms()
        val item = dao.queueItem(id) ?: return
        if (item.status != "PROCESSING") return
        if (ok) { failStreak = 0; dao.saveQueue(item.copy(status = "DONE", error = "")) } else fail(item, "send failed (code $code)")
    }

    @SuppressLint("MissingPermission")
    private fun subIdForSlot(ctx: Context, slot: Int): Int {
        if (slot <= 0) return -1
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return -1
        return try {
            ctx.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfoForSimSlotIndex(slot - 1)?.subscriptionId ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, subId: Int): SmsManager {
        val sub = if (subId >= 0) subId else subIdForSlot(ctx, Graph.prefs.simSlot)
        return if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            if (sub >= 0) m.createForSubscriptionId(sub) else m
        } else {
            if (sub >= 0) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
        }
    }

    private fun send(ctx: Context, item: SmsQueueItem) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("SEND_SMS permission not granted")
        }
        val mgr = manager(ctx, item.subId)
        val parts = mgr.divideMessage(item.message)
        val sentIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            val intent = Intent(ctx, SmsSentReceiver::class.java).apply {
                action = SmsSentReceiver.ACTION
                putExtra("id", item.id)
                putExtra("part", i)
                putExtra("parts", parts.size)
            }
            sentIntents.add(
                PendingIntent.getBroadcast(
                    ctx, (item.id * 100 + i).toInt(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        mgr.sendMultipartTextMessage(item.phone, null, parts, sentIntents, null)
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.boardresult.SMS_SENT" }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val code = resultCode
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsQueue.onSent(id, code == Activity.RESULT_OK, code)
            } finally {
                pending.finish()
            }
        }
    }
}
