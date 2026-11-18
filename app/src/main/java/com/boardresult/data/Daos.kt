package com.boardresult.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RefDao {
    // ---- boards ----
    @Query("SELECT * FROM boards ORDER BY nameEn") fun boardsFlow(): Flow<List<Board>>
    @Query("SELECT * FROM boards ORDER BY nameEn") suspend fun boards(): List<Board>
    @Query("SELECT * FROM boards WHERE id=:id") suspend fun board(id: Long): Board?
    @Query("SELECT * FROM boards WHERE UPPER(code)=UPPER(:t) OR UPPER(nameEn) LIKE UPPER(:t) || '%' ORDER BY (UPPER(code)=UPPER(:t)) DESC LIMIT 1")
    suspend fun boardByToken(t: String): Board?
    @Insert suspend fun addBoard(b: Board): Long
    @Update suspend fun saveBoard(b: Board)
    @Delete suspend fun deleteBoard(b: Board)
    @Query("SELECT (SELECT COUNT(*) FROM institutions WHERE boardId=:id) + (SELECT COUNT(*) FROM exam_sessions WHERE boardId=:id)")
    suspend fun boardUse(id: Long): Int

    // ---- grading ----
    @Query("SELECT * FROM grading_schemes ORDER BY name") fun schemesFlow(): Flow<List<GradingScheme>>
    @Query("SELECT * FROM grading_schemes WHERE id=:id") suspend fun scheme(id: Long): GradingScheme?
    @Insert suspend fun addScheme(s: GradingScheme): Long
    @Update suspend fun saveScheme(s: GradingScheme)
    @Delete suspend fun deleteScheme(s: GradingScheme)
    @Query("DELETE FROM grade_bands WHERE schemeId=:id") suspend fun deleteBandsOf(id: Long)
    @Query("SELECT COUNT(*) FROM exam_types WHERE schemeId=:id") suspend fun schemeUse(id: Long): Int
    @Query("SELECT * FROM grade_bands WHERE schemeId=:id ORDER BY minPercent DESC") fun bandsFlow(id: Long): Flow<List<GradeBand>>
    @Query("SELECT * FROM grade_bands WHERE schemeId=:id ORDER BY minPercent DESC") suspend fun bands(id: Long): List<GradeBand>
    @Insert suspend fun addBand(b: GradeBand): Long
    @Update suspend fun saveBand(b: GradeBand)
    @Delete suspend fun deleteBand(b: GradeBand)

    // ---- exam types ----
    @Query("SELECT * FROM exam_types ORDER BY code") fun examTypesFlow(): Flow<List<ExamType>>
    @Query("SELECT * FROM exam_types ORDER BY code") suspend fun examTypes(): List<ExamType>
    @Query("SELECT * FROM exam_types WHERE id=:id") suspend fun examType(id: Long): ExamType?
    @Query("SELECT * FROM exam_types WHERE UPPER(code)=UPPER(:c) LIMIT 1") suspend fun examTypeByCode(c: String): ExamType?
    @Insert suspend fun addExamType(e: ExamType): Long
    @Update suspend fun saveExamType(e: ExamType)
    @Delete suspend fun deleteExamType(e: ExamType)
    @Query("SELECT (SELECT COUNT(*) FROM exam_sessions WHERE examTypeId=:id) + (SELECT COUNT(*) FROM study_groups WHERE examTypeId=:id) + (SELECT COUNT(*) FROM subjects WHERE examTypeId=:id)")
    suspend fun examTypeUse(id: Long): Int

    // ---- groups ----
    @Query("SELECT * FROM study_groups ORDER BY examTypeId, nameEn") fun groupsFlow(): Flow<List<StudyGroup>>
    @Query("SELECT * FROM study_groups WHERE examTypeId=:et ORDER BY nameEn") suspend fun groupsOf(et: Long): List<StudyGroup>
    @Query("SELECT * FROM study_groups WHERE id=:id") suspend fun group(id: Long): StudyGroup?
    @Insert suspend fun addGroup(g: StudyGroup): Long
    @Update suspend fun saveGroup(g: StudyGroup)
    @Delete suspend fun deleteGroup(g: StudyGroup)
    @Query("SELECT (SELECT COUNT(*) FROM students WHERE groupId=:id) + (SELECT COUNT(*) FROM group_subjects WHERE groupId=:id)")
    suspend fun groupUse(id: Long): Int

    // ---- subjects ----
    @Query("SELECT * FROM subjects ORDER BY examTypeId, code") fun subjectsFlow(): Flow<List<Subject>>
    @Query("SELECT * FROM subjects WHERE id=:id") suspend fun subject(id: Long): Subject?
    @Insert suspend fun addSubject(s: Subject): Long
    @Update suspend fun saveSubject(s: Subject)
    @Query("DELETE FROM subjects WHERE id=:id") suspend fun deleteSubjectRow(id: Long)
    @Query("DELETE FROM subject_components WHERE subjectId=:id") suspend fun deleteComponentsOf(id: Long)
    @Query("DELETE FROM group_subjects WHERE subjectId=:id") suspend fun deleteAssignmentsOf(id: Long)
    @Query("SELECT COUNT(*) FROM student_subjects WHERE subjectId=:id") suspend fun subjectUse(id: Long): Int

    @Query("SELECT * FROM subject_components WHERE subjectId=:id ORDER BY sortOrder, id") fun componentsFlow(id: Long): Flow<List<SubjectComponent>>
    @Query("SELECT * FROM subject_components WHERE subjectId=:id ORDER BY sortOrder, id") suspend fun components(id: Long): List<SubjectComponent>
    @Insert suspend fun addComponent(c: SubjectComponent): Long
    @Update suspend fun saveComponent(c: SubjectComponent)
    @Delete suspend fun deleteComponent(c: SubjectComponent)
    @Query("SELECT COUNT(*) FROM marks WHERE componentId=:id") suspend fun componentUse(id: Long): Int

    @Query("SELECT * FROM group_subjects WHERE subjectId=:id") fun assignmentsFlow(id: Long): Flow<List<GroupSubject>>
    @Query("SELECT * FROM group_subjects WHERE examTypeId=:et AND (groupId IS NULL OR groupId=:g)")
    suspend fun assignmentsForGroup(et: Long, g: Long): List<GroupSubject>
    @Insert suspend fun addAssignment(a: GroupSubject): Long
    @Update suspend fun saveAssignment(a: GroupSubject)
    @Delete suspend fun deleteAssignment(a: GroupSubject)

    // ---- institutions / teachers ----
    @Query("SELECT * FROM institutions ORDER BY nameEn") fun institutionsFlow(): Flow<List<Institution>>
    @Query("SELECT * FROM institutions WHERE id=:id") suspend fun institution(id: Long): Institution?
    @Insert suspend fun addInstitution(i: Institution): Long
    @Update suspend fun saveInstitution(i: Institution)
    @Delete suspend fun deleteInstitution(i: Institution)
    @Query("SELECT (SELECT COUNT(*) FROM students WHERE institutionId=:id) + (SELECT COUNT(*) FROM teachers WHERE institutionId=:id)")
    suspend fun institutionUse(id: Long): Int

    @Query("SELECT * FROM teachers ORDER BY name") fun teachersFlow(): Flow<List<Teacher>>
    @Query("SELECT * FROM teachers WHERE id=:id") suspend fun teacher(id: Long): Teacher?
    @Query("SELECT * FROM teachers WHERE LOWER(username)=LOWER(:u) LIMIT 1") suspend fun teacherByUsername(u: String): Teacher?
    @Insert suspend fun addTeacher(t: Teacher): Long
    @Update suspend fun saveTeacher(t: Teacher)
    @Delete suspend fun deleteTeacher(t: Teacher)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM exam_sessions ORDER BY year DESC, id DESC") fun sessionsFlow(): Flow<List<ExamSession>>
    @Query("SELECT * FROM exam_sessions ORDER BY year DESC, id DESC") suspend fun sessions(): List<ExamSession>
    @Query("SELECT * FROM exam_sessions WHERE boardId=:b ORDER BY year DESC, id DESC") suspend fun sessionsOfBoard(b: Long): List<ExamSession>
    @Query("SELECT * FROM exam_sessions WHERE id=:id") suspend fun session(id: Long): ExamSession?
    @Query("SELECT * FROM exam_sessions WHERE examTypeId=:e AND boardId=:b AND year=:y LIMIT 1")
    suspend fun sessionFor(e: Long, b: Long, y: Int): ExamSession?
    @Insert suspend fun addSession(s: ExamSession): Long
    @Update suspend fun saveSession(s: ExamSession)
    @Delete suspend fun deleteSession(s: ExamSession)
    @Query("SELECT COUNT(*) FROM students WHERE sessionId=:id") suspend fun studentCount(id: Long): Int
    @Query("SELECT COUNT(*) FROM students WHERE sessionId=:id AND resultStatus=:st") suspend fun countByStatus(id: Long, st: String): Int
    @Query("SELECT COUNT(*) FROM students WHERE sessionId=:id AND resultStatus='PASSED' AND gpa>=5.0") suspend fun countGpa5(id: Long): Int
    @Query("SELECT COUNT(*) FROM exam_sessions WHERE published=1") fun publishedCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM students") fun studentTotalFlow(): Flow<Int>
}

@Dao
interface StudentDao {
    @Insert suspend fun addStudent(s: Student): Long
    @Update suspend fun saveStudent(s: Student)
    @Query("DELETE FROM students WHERE id=:id") suspend fun deleteStudentRow(id: Long)
    @Query("SELECT * FROM students WHERE id=:id") suspend fun student(id: Long): Student?
    @Query("SELECT * FROM students WHERE sessionId=:sid AND roll=:roll LIMIT 1") suspend fun studentByRoll(sid: Long, roll: String): Student?
    @Query("SELECT * FROM students WHERE sessionId=:sid AND registration=:reg LIMIT 1") suspend fun studentByReg(sid: Long, reg: String): Student?
    @Query("SELECT * FROM students WHERE registration=:reg") suspend fun studentsByReg(reg: String): List<Student>
    @Query("SELECT * FROM students WHERE sessionId=:sid") suspend fun studentsOfSession(sid: Long): List<Student>
    @Query("SELECT * FROM students WHERE sessionId=:sid AND institutionId=:inst AND (name LIKE '%' || :q || '%' OR roll LIKE '%' || :q || '%' OR registration LIKE '%' || :q || '%') ORDER BY roll LIMIT 500")
    suspend fun search(sid: Long, inst: Long, q: String): List<Student>
    @Query("SELECT * FROM students") suspend fun allStudents(): List<Student>

    @Query("SELECT * FROM student_subjects WHERE studentId=:id") suspend fun studentSubjects(id: Long): List<StudentSubject>
    @Insert suspend fun addStudentSubjects(l: List<StudentSubject>)
    @Query("DELETE FROM student_subjects WHERE studentId=:id") suspend fun clearStudentSubjects(id: Long)

    @Query("SELECT * FROM marks WHERE studentId=:id") suspend fun marks(id: Long): List<Mark>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMarks(l: List<Mark>)
    @Query("DELETE FROM marks WHERE studentId=:id") suspend fun clearMarks(id: Long)
    @Query("DELETE FROM marks WHERE studentId=:id AND subjectId=:sub") suspend fun clearMarksOfSubject(id: Long, sub: Long)
}

@Dao
interface SmsDao {
    @Insert suspend fun addLog(l: SmsLog)
    @Query("SELECT * FROM sms_logs ORDER BY id DESC LIMIT 100") fun logsFlow(): Flow<List<SmsLog>>
    @Query("DELETE FROM sms_logs") suspend fun clearLogs()
    @Query("SELECT COUNT(*) FROM sms_logs WHERE status='SUCCESS'") fun successCountFlow(): Flow<Int>

    @Insert suspend fun addQueue(q: SmsQueueItem): Long
    @Update suspend fun saveQueue(q: SmsQueueItem)
    @Query("SELECT * FROM sms_queue WHERE id=:id") suspend fun queueItem(id: Long): SmsQueueItem?
    @Query("SELECT * FROM sms_queue WHERE status='PENDING' AND scheduledAt<=:now ORDER BY priority ASC, scheduledAt ASC LIMIT 1")
    suspend fun nextDue(now: Long): SmsQueueItem?
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status='PENDING' AND phone=:p AND message=:m") suspend fun pendingDup(p: String, m: String): Int
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status=:st") suspend fun queueCount(st: String): Int
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status=:st") fun queueCountFlow(st: String): Flow<Int>
    @Query("UPDATE sms_queue SET status='PENDING', attempts=0, scheduledAt=:now WHERE status='DEAD'") suspend fun reviveDead(now: Long)
    @Query("SELECT * FROM sms_queue WHERE status='PROCESSING' AND startedAt<:before") suspend fun stuck(before: Long): List<SmsQueueItem>
    @Query("DELETE FROM sms_queue WHERE status='DONE' AND createdAt<:before") suspend fun purgeDone(before: Long)

    @Insert suspend fun addRate(e: RateEvent)
    @Query("SELECT COUNT(*) FROM rate_events WHERE phone=:p AND createdAt>=:since") suspend fun rateCount(p: String, since: Long): Int
    @Query("DELETE FROM rate_events WHERE createdAt<:before") suspend fun purgeRate(before: Long)
}
