package com.boardresult.server

import com.boardresult.core.Security
import java.util.concurrent.ConcurrentHashMap

class Sess(val id: String) {
    val csrf: String = Security.token()
    @Volatile var role: String = ""            // "", "teacher", "student"
    @Volatile var userId: Long = 0
    @Volatile var studentIds: List<Long> = emptyList()
    @Volatile var flash: String? = null
    @Volatile var flashKind: String = "ok"
    @Volatile var captcha: Int = -1
    @Volatile var resultStudentId: Long = 0
    @Volatile var touched: Long = System.currentTimeMillis()

    fun say(msg: String, kind: String = "ok") { flash = msg; flashKind = kind }
}

object Sessions {
    private val map = ConcurrentHashMap<String, Sess>()
    private const val TTL = 2 * 60 * 60 * 1000L

    fun find(id: String?): Sess? {
        if (id == null) return null
        val s = map[id] ?: return null
        val now = System.currentTimeMillis()
        if (now - s.touched > TTL) { map.remove(id); return null }
        s.touched = now
        return s
    }

    fun create(): Sess {
        if (map.size > 5000) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now - it.value.touched > TTL }
        }
        val s = Sess(Security.token())
        map[s.id] = s
        return s
    }

    fun destroy(id: String) { map.remove(id) }
}

/** Very small brute-force guard: 6 failed logins per key within 10 minutes -> locked for the rest of the window. */
object Throttle {
    private class Entry(var count: Int, var first: Long)
    private val map = ConcurrentHashMap<String, Entry>()
    private const val WINDOW = 10 * 60 * 1000L
    private const val LIMIT = 6

    fun blocked(key: String): Boolean {
        val e = map[key] ?: return false
        if (System.currentTimeMillis() - e.first > WINDOW) { map.remove(key); return false }
        return e.count >= LIMIT
    }

    fun fail(key: String) {
        val now = System.currentTimeMillis()
        val e = map[key]
        if (e == null || now - e.first > WINDOW) map[key] = Entry(1, now) else e.count++
    }

    fun ok(key: String) { map.remove(key) }
}
