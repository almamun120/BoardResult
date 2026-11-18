package com.boardresult.core

import android.content.Context
import com.boardresult.data.AppDatabase

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("board_result", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()
    var adminPinHash: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()
    var seeded: Boolean
        get() = sp.getBoolean("seeded", false)
        set(v) = sp.edit().putBoolean("seeded", v).apply()
    var autoStart: Boolean
        get() = sp.getBoolean("autostart", false)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    // ---- SMS ----
    var smsEnabled: Boolean
        get() = sp.getBoolean("sms_enabled", true)
        set(v) = sp.edit().putBoolean("sms_enabled", v).apply()
    var simSlot: Int                // 0 = system default, 1 = SIM1, 2 = SIM2
        get() = sp.getInt("sim_slot", 0)
        set(v) = sp.edit().putInt("sim_slot", v).apply()
    var perMinute: Int
        get() = sp.getInt("per_min", 3)
        set(v) = sp.edit().putInt("per_min", v).apply()
    var perHour: Int
        get() = sp.getInt("per_hour", 20)
        set(v) = sp.edit().putInt("per_hour", v).apply()
    var perDay: Int
        get() = sp.getInt("per_day", 50)
        set(v) = sp.edit().putInt("per_day", v).apply()
    var globalPerSecond: Int
        get() = sp.getInt("global_sec", 10)
        set(v) = sp.edit().putInt("global_sec", v).apply()
    var maxRetries: Int
        get() = sp.getInt("max_retries", 3)
        set(v) = sp.edit().putInt("max_retries", v).apply()
    var retryDelaySec: Int
        get() = sp.getInt("retry_delay", 30)
        set(v) = sp.edit().putInt("retry_delay", v).apply()
    var maxQueue: Int
        get() = sp.getInt("max_queue", 1000)
        set(v) = sp.edit().putInt("max_queue", v).apply()
    var sendDelayMs: Int
        get() = sp.getInt("send_delay", 700)
        set(v) = sp.edit().putInt("send_delay", v).apply()
    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", true)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()
    var lastAutoBackup: Long
        get() = sp.getLong("last_backup", 0L)
        set(v) = sp.edit().putLong("last_backup", v).apply()
    var portalTitle: String
        get() = sp.getString("portal_title", "Result Portal") ?: "Result Portal"
        set(v) = sp.edit().putString("portal_title", v).apply()
}

object Graph {
    lateinit var app: Context
    lateinit var db: AppDatabase
    lateinit var prefs: Prefs
}
