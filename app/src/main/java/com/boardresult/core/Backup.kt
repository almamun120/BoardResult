package com.boardresult.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Whole-database backup/restore (students, marks, setup, teachers, SMS log). App settings/PIN are not included. */
object Backup {
    private const val DB = "board_result.db"
    private const val DB_VERSION = 1

    private fun dbFile(ctx: Context) = ctx.getDatabasePath(DB)

    private fun checkpoint() {
        Graph.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    fun backupDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups").also { it.mkdirs() }

    fun exportTo(ctx: Context, uri: Uri) {
        checkpoint()
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> dbFile(ctx).inputStream().use { it.copyTo(out) } }
    }

    /** Local copy in the app folder; keeps the newest 7 of each prefix. */
    fun localBackup(ctx: Context, prefix: String = "auto"): File {
        checkpoint()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(backupDir(ctx), "$prefix-$stamp.db")
        dbFile(ctx).copyTo(f, overwrite = true)
        backupDir(ctx).listFiles { x -> x.name.startsWith("$prefix-") }
            ?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        return f
    }

    fun count(ctx: Context): Int = backupDir(ctx).listFiles()?.size ?: 0

    /** Returns an error text, or null when the restore file was swapped in (the app must be restarted afterwards). */
    fun restoreFrom(ctx: Context, uri: Uri): String? {
        val tmp = File(ctx.cacheDir, "restore.db")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val header = tmp.inputStream().use { s -> ByteArray(15).also { s.read(it) } }
            if (String(header, Charsets.US_ASCII) != "SQLite format 3") return "This is not a valid backup file."
            val chk = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY)
            val okSchema = try {
                chk.version == DB_VERSION &&
                    chk.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('students','exam_sessions','room_master_table')", null)
                        .use { it.count == 3 }
            } finally { chk.close() }
            if (!okSchema) return "Backup is from a different app version or is damaged."

            localBackup(ctx, "pre-restore")          // safety copy of the current data
            Graph.db.close()
            val target = dbFile(ctx)
            File(target.path + "-wal").delete(); File(target.path + "-shm").delete()
            tmp.copyTo(target, overwrite = true)
            return null
        } catch (e: Exception) {
            return "Restore failed: ${e.message}"
        } finally {
            tmp.delete()
        }
    }
}
