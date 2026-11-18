package com.boardresult.sms

import com.boardresult.core.Graph
import com.boardresult.data.RateEvent

enum class Verdict { ALLOW, BLOCK_NOTIFY, BLOCK_SILENT }

/** Per-number (minute/hour/day) + global per-second limiter. Limits are admin-configurable. */
object RateLimiter {
    private val recent = ArrayDeque<Long>()
    private val lock = Any()

    suspend fun check(phone: String): Verdict {
        val p = Graph.prefs
        val dao = Graph.db.sms()
        val now = System.currentTimeMillis()

        val globalBlocked = synchronized(lock) {
            while (recent.isNotEmpty() && now - recent.first() > 1000) recent.removeFirst()
            if (recent.size >= p.globalPerSecond) true else { recent.addLast(now); false }
        }
        if (globalBlocked) return Verdict.BLOCK_SILENT

        dao.addRate(RateEvent(phone = phone, createdAt = now))
        val m = dao.rateCount(phone, now - 60_000L)
        val h = dao.rateCount(phone, now - 3_600_000L)
        val d = dao.rateCount(phone, now - 86_400_000L)
        if (Math.random() < 0.02) dao.purgeRate(now - 90_000_000L)

        val over = m > p.perMinute || h > p.perHour || d > p.perDay
        if (!over) return Verdict.ALLOW
        val first = m == p.perMinute + 1 || h == p.perHour + 1 || d == p.perDay + 1
        return if (first) Verdict.BLOCK_NOTIFY else Verdict.BLOCK_SILENT
    }
}
