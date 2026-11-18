package com.boardresult.server

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.boardresult.core.ResultView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws an A4 result sheet with Android's PdfDocument (no external library). */
object PdfMaker {
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    private fun paint(size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
            color = 0xFF111111.toInt()
        }

    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 0.8f) {
        val p = Paint().apply { strokeWidth = w; color = 0xFF444444.toInt() }
        c.drawLine(x1, y1, x2, y2, p)
    }

    fun make(v: ResultView, withMarks: Boolean): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val s = v.student

        // frame
        val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF0B6E4F.toInt() }
        c.drawRect(18f, 18f, W - 18f, H - 18f, frame)

        var y = 56f
        c.drawText("Government of the People's Republic of Bangladesh", W / 2f, y, paint(11f, false, Paint.Align.CENTER))
        y += 22f
        c.drawText("Board of Intermediate and Secondary Education, ${v.board.nameEn}", W / 2f, y, paint(15f, true, Paint.Align.CENTER))
        y += 22f
        c.drawText("${v.examType.nameEn} (${v.examType.code}) Examination ${v.session.year}", W / 2f, y, paint(12f, true, Paint.Align.CENTER))
        y += 16f
        c.drawText("RESULT SHEET", W / 2f, y, paint(12f, true, Paint.Align.CENTER))
        y += 12f
        line(c, M, y, W - M, y, 1.4f)
        y += 22f

        val lab = paint(10.5f, true)
        val value = paint(10.5f)
        fun row(l: String, r: String, x: Float, yy: Float, w: Float) {
            c.drawText(l, x, yy, lab)
            c.drawText(": " + fit(value, r, w - 100f), x + 98f, yy, value)
        }
        val half = (W - 2 * M) / 2f
        row("Roll No", s.roll, M, y, half); row("Registration No", s.registration, M + half, y, half); y += 17f
        row("Name", s.name, M, y, W - 2 * M); y += 17f
        row("Father's Name", s.father.ifBlank { "-" }, M, y, half); row("Mother's Name", s.mother.ifBlank { "-" }, M + half, y, half); y += 17f
        row("Date of Birth", s.dob.ifBlank { "-" }, M, y, half); row("Group", v.group?.nameEn ?: "-", M + half, y, half); y += 17f
        row("Student Type", s.studentType.lowercase().replaceFirstChar { it.uppercase() }, M, y, half)
        row("Board", v.board.nameEn, M + half, y, half); y += 17f
        val inst = v.institution?.let { "${it.nameEn} (EIIN ${it.eiin})" } ?: "-"
        row("Institute", inst, M, y, W - 2 * M); y += 14f
        line(c, M, y, W - M, y); y += 22f

        if (s.withheld || v.out.status == "PENDING") {
            val msg = if (s.withheld) "RESULT WITHHELD" else "RESULT NOT COMPLETE"
            c.drawText(msg, W / 2f, y + 30f, paint(18f, true, Paint.Align.CENTER))
        } else {
            // table header
            val cCode = M + 4f
            val cName = M + 54f
            val cMarks = W - M - 150f
            val cGrade = W - M - 70f
            val cGp = W - M - 8f
            val hp = paint(10.5f, true)
            c.drawText("Code", cCode, y, hp)
            c.drawText("Subject", cName, y, hp)
            if (withMarks) c.drawText("Marks", cMarks, y, hp)
            c.drawText("Grade", cGrade, y, hp)
            if (withMarks) c.drawText("GP", cGp, y, paint(10.5f, true, Paint.Align.RIGHT))
            y += 6f
            line(c, M, y, W - M, y); y += 16f

            val rp = paint(10.5f)
            v.out.subjects.forEach { sub ->
                c.drawText(sub.code, cCode, y, rp)
                val nm = sub.name + if (sub.isFourth) "  (4th subject)" else ""
                c.drawText(fit(rp, nm, (if (withMarks) cMarks else cGrade) - cName - 8f), cName, y, rp)
                if (withMarks) c.drawText("${sub.total}/${sub.max}", cMarks, y, rp)
                c.drawText(sub.letter, cGrade, y, rp)
                if (withMarks) c.drawText(String.format(Locale.US, "%.2f", sub.point), cGp, y, paint(10.5f, false, Paint.Align.RIGHT))
                y += 18f
            }
            line(c, M, y - 6f, W - M, y - 6f)
            y += 14f

            val o = v.out
            val big = paint(13f, true)
            c.drawText("Result : ${if (o.status == "PASSED") "PASSED" else o.status}", M, y, big)
            c.drawText("GPA : ${String.format(Locale.US, "%.2f", o.gpa)}  (${o.letter})", M + 200f, y, big)
            y += 20f
            if (withMarks) {
                c.drawText("Total marks obtained : ${o.total}", M, y, rp)
                y += 16f
            }
            if (o.status == "FAILED") {
                c.drawText("Failed in subject code(s): ${o.failedCodes.joinToString(", ")}", M, y, rp)
                y += 16f
            }
        }

        val fp = paint(9f)
        val stamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        c.drawText("Computer generated result sheet - printed on $stamp.", M, H - 44f, fp)
        c.drawText("Verify authenticity with your institution / board.", M, H - 31f, fp)

        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
