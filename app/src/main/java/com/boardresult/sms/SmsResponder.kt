package com.boardresult.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.content.BroadcastReceiver
import com.boardresult.core.Graph
import com.boardresult.core.ResultService
import com.boardresult.core.ResultView
import com.boardresult.data.SmsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

object SmsResponder {

    class Composed(val text: String, val status: String)

    suspend fun handle(ctx: Context, from: String, body: String, subId: Int = -1) {
        if (!Graph.prefs.smsEnabled) return
        val ref = Graph.db.ref()

        // Not a result query? ignore silently (normal SMS must not trigger replies).
        ref.examTypeByCode(SmsParser.firstToken(body)) ?: return

        when (RateLimiter.check(from)) {
            Verdict.BLOCK_SILENT -> { log(from, body, "(rate limited, no reply)", "RATE_LIMITED"); return }
            Verdict.BLOCK_NOTIFY -> {
                reply(ctx, from, "Too many requests. Please try again later.", subId, body, "RATE_LIMITED"); return
            }
            Verdict.ALLOW -> {}
        }
        val c = compose(body) ?: return
        reply(ctx, from, c.text, subId, body, c.status, if (c.status == "SUCCESS") 5 else 8)
    }

    /** Builds the reply for an SMS body (no rate limit, nothing sent). Null = not a result query. */
    suspend fun compose(body: String): Composed? {
        val db = Graph.db
        val ref = db.ref()
        val examType = ref.examTypeByCode(SmsParser.firstToken(body)) ?: return null

        val q = SmsParser.parse(body)
            ?: return Composed(SmsParser.usage(ref.examTypes().map { it.code }, ref.boards().map { it.code }), "INVALID")

        val notFound = Composed(
            "No result found.\n${examType.code} ${q.boardToken} ${q.roll} ${q.year}\nCheck your information and try again.",
            "NOT_FOUND"
        )
        val board = ref.boardByToken(q.boardToken)
        val session = board?.let { db.sessions().sessionFor(examType.id, it.id, q.year) }
        if (board == null || session == null || !session.published) return notFound

        val student = db.students().studentByRoll(session.id, q.roll)
        if (student == null || (q.reg != null && q.reg != student.registration)) return notFound

        val view = ResultService.build(student)
        if (view == null || (view.out.status == "PENDING" && !student.withheld)) return notFound
        return Composed(buildText(view), "SUCCESS")
    }

    fun buildText(v: ResultView): String {
        val s = v.student
        val sb = StringBuilder()
        sb.append("${v.examType.code} ${v.session.year} ${v.board.nameEn.uppercase()}\n")
        sb.append("Roll: ${s.roll}\n")
        sb.append("Name: ${s.name}\n")
        if (s.father.isNotBlank()) sb.append("Father: ${s.father}\n")
        v.group?.let { sb.append("Group: ${it.nameEn}\n") }
        v.institution?.let { sb.append("Inst: ${it.nameEn} (EIIN ${it.eiin})\n") }

        if (s.withheld) {
            sb.append("Result: WITHHELD\nPlease contact your institution.")
            return sb.toString()
        }
        val o = v.out
        when (o.status) {
            "PASSED" -> sb.append("GPA: ${String.format(Locale.US, "%.2f", o.gpa)} (${o.letter})  PASSED\n")
            "FAILED" -> sb.append("Result: FAILED (GPA 0.00)\nFailed in: ${o.failedCodes.joinToString(", ")}\n")
            else -> sb.append("Result: ABSENT\n")
        }
        sb.append("Grades:\n")
        o.subjects.forEach {
            sb.append("${it.code} ${it.name} ${it.letter}${if (it.isFourth) " (4th)" else ""}\n")
        }
        return sb.toString().trimEnd()
    }

    private suspend fun reply(ctx: Context, to: String, text: String, subId: Int, request: String, status: String, priority: Int = 8) {
        if (SmsQueue.hasPending(to, text)) { log(to, request, "(duplicate – already queued)", "DUPLICATE"); return }
        if (!SmsQueue.enqueue(to, text, subId, priority)) { log(to, request, "(queue full)", "QUEUE_FULL"); return }
        log(to, request, text, status)
        SmsQueue.kick(ctx)
    }

    private suspend fun log(phone: String, request: String, response: String, status: String) {
        Graph.db.sms().addLog(SmsLog(phone = phone, request = request, response = response, status = status))
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val subId = intent.getIntExtra("subscription", -1)
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsResponder.handle(app, from, body, subId)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
