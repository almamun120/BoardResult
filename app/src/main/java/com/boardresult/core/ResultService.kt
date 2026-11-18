package com.boardresult.core

import com.boardresult.data.Board
import com.boardresult.data.ExamSession
import com.boardresult.data.ExamType
import com.boardresult.data.Institution
import com.boardresult.data.StudyGroup
import com.boardresult.data.Student

/** Everything needed to show a result anywhere (web, PDF, SMS). */
data class ResultView(
    val student: Student,
    val session: ExamSession,
    val examType: ExamType,
    val board: Board,
    val institution: Institution?,
    val group: StudyGroup?,
    val bands: List<Band>,
    val out: ResultOut
) {
    /** What the public sees: withheld results show no grades. */
    val displayStatus: String get() = if (student.withheld) "WITHHELD" else out.status
}

object ResultService {
    private val db get() = Graph.db

    suspend fun view(studentId: Long): ResultView? {
        val st = db.students().student(studentId) ?: return null
        return build(st)
    }

    suspend fun build(st: Student): ResultView? {
        val ref = db.ref()
        val ses = db.sessions().session(st.sessionId) ?: return null
        val et = ref.examType(ses.examTypeId) ?: return null
        val board = ref.board(ses.boardId) ?: return null
        val scheme = ref.scheme(et.schemeId) ?: return null
        val bands = ref.bands(scheme.id).map { Band(it.minPercent, it.letter, it.point) }
        val marks = db.students().marks(st.id).associateBy { it.componentId }

        val inputs = db.students().studentSubjects(st.id).mapNotNull { ss ->
            val sub = ref.subject(ss.subjectId) ?: return@mapNotNull null
            val comps = ref.components(sub.id).map { c ->
                val m = marks[c.id]
                CompIn(c.name, c.maxMarks, c.passMarks, m?.obtained, m?.absent ?: false)
            }
            SubjectIn(sub.id, sub.code, sub.nameEn, sub.nameBn, ss.isFourth, comps)
        }.sortedWith(compareBy<SubjectIn> { it.isFourth }.thenBy { it.code })

        val out = Calc.result(inputs, bands, Rules(scheme.gpaCap, scheme.optionalDeduct, scheme.failOnAnyCompulsory))
        return ResultView(st, ses, et, board, ref.institution(st.institutionId), ref.group(st.groupId), bands, out)
    }

    /** Recompute and store the summary fields of one student. */
    suspend fun recalc(studentId: Long): ResultView? {
        val v = view(studentId) ?: return null
        val o = v.out
        db.students().saveStudent(
            v.student.copy(
                gpa = o.gpa, letter = o.letter, resultStatus = o.status,
                failedCount = o.failedCodes.size, totalMarks = o.total
            )
        )
        return v
    }

    suspend fun recalcSession(sessionId: Long) {
        db.students().studentsOfSession(sessionId).forEach { recalc(it.id) }
    }

    suspend fun recalcAll() {
        db.students().allStudents().forEach { recalc(it.id) }
    }

    fun statusText(s: String): String = when (s) {
        "PASSED" -> "Passed"
        "FAILED" -> "Failed"
        "ABSENT" -> "Absent"
        "WITHHELD" -> "Withheld"
        else -> "Incomplete"
    }
}
