package com.boardresult.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "boards", indices = [Index(value = ["code"], unique = true)])
data class Board(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,            // 3 letters used in SMS: DHA, RAJ ...
    val nameEn: String,
    val nameBn: String = ""
)

@Entity(tableName = "grading_schemes")
data class GradingScheme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gpaCap: Double = 5.0,
    val optionalDeduct: Double = 2.0,          // 4th subject: points above this are added
    val failOnAnyCompulsory: Boolean = true
)

@Entity(tableName = "grade_bands", indices = [Index("schemeId")])
data class GradeBand(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val schemeId: Long,
    val minPercent: Double,
    val letter: String,
    val point: Double
)

@Entity(tableName = "exam_types", indices = [Index(value = ["code"], unique = true)])
data class ExamType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,            // SSC, HSC ... (also the SMS keyword)
    val nameEn: String,
    val nameBn: String = "",
    val schemeId: Long
)

@Entity(tableName = "study_groups", indices = [Index("examTypeId")])
data class StudyGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examTypeId: Long,
    val nameEn: String,
    val nameBn: String = ""
)

@Entity(tableName = "subjects", indices = [Index(value = ["examTypeId", "code"], unique = true)])
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examTypeId: Long,
    val code: String,
    val nameEn: String,
    val nameBn: String = ""
)

/** A mark component of a subject: Creative/Written, MCQ, Practical, 1st paper ... */
@Entity(tableName = "subject_components", indices = [Index("subjectId")])
data class SubjectComponent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val name: String,
    val maxMarks: Int,
    val passMarks: Int,
    val sortOrder: Int = 0
)

/**
 * Which subjects belong to which group.
 * groupId == null  -> common subject for every group of the exam type.
 * kind: COMPULSORY | SELECTIVE (student picks one per choiceKey, e.g. RELIGION) | FOURTH (optional/4th subject)
 */
@Entity(tableName = "group_subjects", indices = [Index("examTypeId"), Index("subjectId")])
data class GroupSubject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examTypeId: Long,
    val groupId: Long?,
    val subjectId: Long,
    val kind: String = "COMPULSORY",
    val choiceKey: String = ""
)

@Entity(tableName = "institutions", indices = [Index(value = ["eiin"], unique = true)])
data class Institution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameEn: String,
    val nameBn: String = "",
    val eiin: String,
    val boardId: Long,
    val district: String = "",
    val type: String = "SCHOOL"   // SCHOOL | COLLEGE | MADRASAH | TECHNICAL
)

@Entity(tableName = "teachers", indices = [Index(value = ["username"], unique = true)])
data class Teacher(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val name: String,
    val passwordHash: String,
    val institutionId: Long,
    val active: Boolean = true
)

@Entity(tableName = "exam_sessions", indices = [Index(value = ["examTypeId", "boardId", "year"], unique = true)])
data class ExamSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examTypeId: Long,
    val boardId: Long,
    val year: Int,
    val published: Boolean = false,
    val publishedAt: Long? = null
)

@Entity(
    tableName = "students",
    indices = [
        Index(value = ["sessionId", "roll"], unique = true),
        Index(value = ["sessionId", "registration"], unique = true),
        Index("registration"),
        Index("institutionId")
    ]
)
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val institutionId: Long,
    val groupId: Long,
    val roll: String,
    val registration: String,
    val name: String,
    val father: String = "",
    val mother: String = "",
    val dob: String = "",
    val gender: String = "",
    val studentType: String = "REGULAR",   // REGULAR | IRREGULAR
    val phone: String = "",
    val passwordHash: String = "",
    val withheld: Boolean = false,
    // computed summary (refreshed on every marks save / recalculation)
    val gpa: Double = 0.0,
    val letter: String = "-",
    val resultStatus: String = "PENDING",  // PENDING | PASSED | FAILED | ABSENT
    val failedCount: Int = 0,
    val totalMarks: Int = 0,
    val createdBy: Long = 0
)

@Entity(tableName = "student_subjects", indices = [Index("studentId")])
data class StudentSubject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val subjectId: Long,
    val isFourth: Boolean = false
)

@Entity(tableName = "marks", indices = [Index(value = ["studentId", "componentId"], unique = true)])
data class Mark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val subjectId: Long,
    val componentId: Long,
    val obtained: Int? = null,
    val absent: Boolean = false
)

@Entity(tableName = "sms_logs")
data class SmsLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val request: String,
    val response: String,
    val status: String,       // SUCCESS | NOT_FOUND | INVALID | RATE_LIMITED
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sms_queue", indices = [Index(value = ["status", "scheduledAt"])])
data class SmsQueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val message: String,
    val subId: Int = -1,
    val status: String = "PENDING",   // PENDING | PROCESSING | DONE | DEAD
    val priority: Int = 5,
    val attempts: Int = 0,
    val scheduledAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rate_events", indices = [Index(value = ["phone", "createdAt"])])
data class RateEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val createdAt: Long = System.currentTimeMillis()
)
