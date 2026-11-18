package com.boardresult.core

import java.math.BigDecimal
import java.math.RoundingMode

data class Band(val min: Double, val letter: String, val point: Double)

data class Rules(
    val gpaCap: Double = 5.0,
    val optionalDeduct: Double = 2.0,
    val failOnAnyCompulsory: Boolean = true
)

data class CompIn(val name: String, val max: Int, val pass: Int, val obtained: Int?, val absent: Boolean)

data class SubjectIn(
    val subjectId: Long,
    val code: String,
    val name: String,
    val nameBn: String,
    val isFourth: Boolean,
    val comps: List<CompIn>
)

data class SubjectOut(
    val subjectId: Long,
    val code: String,
    val name: String,
    val nameBn: String,
    val isFourth: Boolean,
    val comps: List<CompIn>,
    val total: Int,
    val max: Int,
    val letter: String,
    val point: Double,
    val passed: Boolean,
    val complete: Boolean,
    val absent: Boolean
)

data class ResultOut(
    val subjects: List<SubjectOut>,
    val gpa: Double,
    val letter: String,
    val status: String,          // PENDING | PASSED | FAILED | ABSENT
    val failedCodes: List<String>,
    val total: Int
)

/**
 * Result calculation following the Bangladesh general-education board rules (SSC/HSC style):
 *  - a subject's grade comes from the combined % of all its components;
 *  - failing any single component (below its pass mark, or absent) fails the subject (grade F, point 0);
 *  - GPA = (sum of points of compulsory subjects + max(0, 4th-subject point - 2)) / number of compulsory subjects, capped at 5.00;
 *  - failing any compulsory subject => GPA 0.00 and the result is FAILED; the 4th subject can never fail a student;
 *  - the overall letter grade is taken from the GPA.
 * All numbers come from the grading scheme the admin configured.
 */
object Calc {

    fun subject(s: SubjectIn, bands: List<Band>): SubjectOut {
        val sorted = bands.sortedByDescending { it.min }
        val failBand = sorted.lastOrNull()
        val failLetter = failBand?.letter ?: "F"

        val complete = s.comps.isNotEmpty() && s.comps.all { it.absent || it.obtained != null }
        val allAbsent = s.comps.isNotEmpty() && s.comps.all { it.absent }
        val max = s.comps.sumOf { it.max }
        val total = s.comps.sumOf { if (it.absent) 0 else (it.obtained ?: 0) }
        val compFail = s.comps.any { it.pass > 0 && (it.absent || (it.obtained ?: 0) < it.pass) }

        val letter: String
        val point: Double
        if (!complete) {
            letter = "-"; point = 0.0
        } else if (compFail || allAbsent || max == 0) {
            letter = failLetter; point = 0.0
        } else {
            val band = sorted.firstOrNull { total * 100.0 >= it.min * max - 1e-9 } ?: failBand
            letter = band?.letter ?: failLetter
            point = band?.point ?: 0.0
        }
        return SubjectOut(
            s.subjectId, s.code, s.name, s.nameBn, s.isFourth, s.comps,
            total, max, letter, point, complete && point > 0.0, complete, allAbsent
        )
    }

    fun letterForGpa(gpa: Double, bands: List<Band>): String {
        val failLetter = bands.minByOrNull { it.min }?.letter ?: "F"
        return bands.filter { it.point > 0.0 }
            .sortedByDescending { it.point }
            .firstOrNull { gpa >= it.point - 1e-9 }?.letter ?: failLetter
    }

    fun result(inputs: List<SubjectIn>, bands: List<Band>, rules: Rules): ResultOut {
        val subs = inputs.map { subject(it, bands) }
        val total = subs.sumOf { it.total }
        val failLetter = bands.minByOrNull { it.min }?.letter ?: "F"

        if (subs.isEmpty() || subs.any { !it.complete }) {
            return ResultOut(subs, 0.0, "-", "PENDING", emptyList(), total)
        }
        val core = subs.filter { !it.isFourth }
        if (core.isEmpty()) return ResultOut(subs, 0.0, "-", "PENDING", emptyList(), total)

        if (core.all { it.absent }) {
            return ResultOut(subs, 0.0, failLetter, "ABSENT", core.map { it.code }, total)
        }
        val failed = core.filter { !it.passed }
        if (rules.failOnAnyCompulsory && failed.isNotEmpty()) {
            return ResultOut(subs, 0.0, failLetter, "FAILED", failed.map { it.code }, total)
        }
        val fourth = subs.firstOrNull { it.isFourth }
        val bonus = if (fourth != null) maxOf(0.0, fourth.point - rules.optionalDeduct) else 0.0
        val raw = (core.sumOf { it.point } + bonus) / core.size
        val gpa = BigDecimal.valueOf(minOf(raw, rules.gpaCap)).setScale(2, RoundingMode.HALF_UP).toDouble()
        return ResultOut(subs, gpa, letterForGpa(gpa, bands), "PASSED", failed.map { it.code }, total)
    }
}
