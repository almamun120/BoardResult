package com.boardresult.sms

import java.util.Calendar

data class SmsQuery(
    val exam: String,
    val boardToken: String,
    val roll: String,
    val year: Int,
    val reg: String?
)

/**
 * Same shape as the real board SMS service:
 *     <EXAM> <BOARD> <ROLL> <YEAR>            e.g.  SSC DHA 123456 2023
 * Optional 5th token = registration number, used as an extra check:
 *     SSC DHA 123456 2023 1234567890
 */
object SmsParser {
    fun firstToken(raw: String): String =
        raw.trim().split(Regex("\\s+")).firstOrNull()?.uppercase() ?: ""

    fun parse(raw: String): SmsQuery? {
        val t = raw.trim().uppercase().split(Regex("\\s+"))
        if (t.size != 4 && t.size != 5) return null
        val (exam, board, roll, yearStr) = t
        if (!Regex("^[A-Z0-9]{2,12}$").matches(exam)) return null
        if (!Regex("^[A-Z]{3,12}$").matches(board)) return null
        if (!Regex("^\\d{3,10}$").matches(roll)) return null
        if (!Regex("^\\d{4}$").matches(yearStr)) return null
        val year = yearStr.toInt()
        if (year < 1990 || year > Calendar.getInstance().get(Calendar.YEAR)) return null
        val reg = if (t.size == 5) t[4] else null
        if (reg != null && !Regex("^\\d{5,15}$").matches(reg)) return null
        return SmsQuery(exam, board, roll, year, reg)
    }

    fun usage(exams: List<String>, boards: List<String>): String =
        "Wrong format! Send:\n" +
            "<EXAM> <BOARD> <ROLL> <YEAR>\n" +
            "Example: ${exams.firstOrNull() ?: "SSC"} ${boards.firstOrNull() ?: "DHA"} 123456 2023\n" +
            "Exams: ${exams.joinToString(" ")}\n" +
            "Boards: ${boards.joinToString(" ")}"
}
