package com.boardresult.server

import android.database.sqlite.SQLiteConstraintException
import com.boardresult.core.Graph
import com.boardresult.core.ResultService
import com.boardresult.core.ResultView
import com.boardresult.core.Security
import com.boardresult.data.ExamSession
import com.boardresult.data.GroupSubject
import com.boardresult.data.Institution
import com.boardresult.data.Mark
import com.boardresult.data.Student
import com.boardresult.data.StudentSubject
import com.boardresult.data.StudyGroup
import com.boardresult.data.Subject
import com.boardresult.data.SubjectComponent
import com.boardresult.data.Teacher
import com.boardresult.server.Pages.csrf
import com.boardresult.server.Pages.esc
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Cookie
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Embedded web portal (Ktor CIO) – teachers enter marks, students view / download results. */
object WebServer {
    private var engine: ApplicationEngine? = null
    @Volatile var lastError: String = ""

    @Synchronized
    fun start(port: Int): Boolean {
        stop()
        return try {
            engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                routing { portal() }
            }.start(wait = false)
            lastError = ""
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            engine = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try { engine?.stop(300, 1000) } catch (_: Exception) {}
        engine = null
    }
}

// ───────────────────────────── helpers ─────────────────────────────

private val db get() = Graph.db

private fun ApplicationCall.sess(): Sess {
    Sessions.find(request.cookies["sid"])?.let { return it }
    val s = Sessions.create()
    response.cookies.append(
        Cookie(name = "sid", value = s.id, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Lax"))
    )
    return s
}

private suspend fun ApplicationCall.html(s: Sess, title: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(Pages.layout(s, title, body), ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

/** Reads a POST form and verifies the CSRF token. */
private suspend fun ApplicationCall.form(s: Sess): Parameters? {
    val p = receiveParameters()
    if (p["_csrf"] != s.csrf) {
        respondText("Invalid or expired form. Go back and reload the page.", status = HttpStatusCode.Forbidden)
        return null
    }
    return p
}

private fun ApplicationCall.ip(): String = try { request.local.remoteHost } catch (e: Exception) { "?" }

private suspend fun ApplicationCall.teacher(s: Sess): Teacher? {
    if (s.role != "teacher") { respondRedirect("/teacher/login"); return null }
    val t = db.ref().teacher(s.userId)
    if (t == null || !t.active) { s.role = ""; respondRedirect("/teacher/login"); return null }
    return t
}

private class Offer(val a: GroupSubject, val sub: Subject)

private suspend fun offers(etId: Long, groupId: Long): List<Offer> =
    db.ref().assignmentsForGroup(etId, groupId)
        .mapNotNull { a -> db.ref().subject(a.subjectId)?.let { Offer(a, it) } }
        .distinctBy { it.sub.id }
        .sortedBy { it.sub.code }

private val rollRx = Regex("^\\d{3,10}$")
private val regRx = Regex("^\\d{5,15}$")
private val dobRx = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val phoneRx = Regex("^[+]?\\d{6,15}$")

// ───────────────────────────── routes ─────────────────────────────

private fun Routing.portal() {

    // ---------------- public result lookup ----------------
    get("/") {
        val s = call.sess()
        call.renderHome(s)
    }

    post("/result") {
        val s = call.sess()
        val p = call.form(s) ?: return@post
        val ok = p["captcha"]?.trim()?.toIntOrNull() == s.captcha
        s.captcha = -1
        val examId = p["exam"]?.toLongOrNull()
        val boardId = p["board"]?.toLongOrNull()
        val year = p["year"]?.trim()?.toIntOrNull()
        val roll = p["roll"]?.trim().orEmpty()
        val reg = p["reg"]?.trim().orEmpty()
        if (!ok) { s.say("ক্যাপচা সঠিক নয়।", "err"); call.renderHome(s); return@post }
        if (examId == null || boardId == null || year == null) { s.say("সব তথ্য সঠিকভাবে দিন।", "err"); call.renderHome(s); return@post }

        val ses = db.sessions().sessionFor(examId, boardId, year)
        val st = if (ses != null && ses.published) db.students().studentByRoll(ses.id, roll) else null
        val view = if (st != null && st.registration == reg) ResultService.build(st) else null
        if (view == null || (view.out.status == "PENDING" && !view.student.withheld)) {
            s.say("কোনো ফলাফল পাওয়া যায়নি। তথ্য যাচাই করে আবার চেষ্টা করুন।", "err")
            call.renderHome(s); return@post
        }
        s.resultStudentId = view.student.id
        call.html(s, "Result", Pages.marksheet(view, showMarks = false, pdfHref = "/result/pdf") +
            """<p><a class="btn alt" href="/">নতুন অনুসন্ধান</a></p>""")
    }

    get("/result/pdf") {
        val s = call.sess()
        val v = if (s.resultStudentId > 0) ResultService.view(s.resultStudentId) else null
        if (v == null || !v.session.published) { call.respondRedirect("/"); return@get }
        call.sendPdf(v, false)
    }

    // ---------------- student ----------------
    get("/student/login") {
        val s = call.sess()
        call.html(s, "Student login", Pages.loginForm(s, "শিক্ষার্থী লগইন", "/student/login", "রেজিস্ট্রেশন নম্বর"))
    }

    post("/student/login") {
        val s = call.sess()
        val p = call.form(s) ?: return@post
        val reg = p["user"]?.trim().orEmpty()
        val pw = p["password"].orEmpty()
        val key = "s:$reg:${call.ip()}"
        if (Throttle.blocked(key)) { s.say("অনেকবার ভুল হয়েছে। ১০ মিনিট পরে চেষ্টা করুন।", "err"); call.respondRedirect("/student/login"); return@post }
        val mine = db.students().studentsByReg(reg).filter { Security.verify(pw, it.passwordHash) }
        if (mine.isEmpty()) {
            Throttle.fail(key)
            s.say("রেজিস্ট্রেশন নম্বর বা পাসওয়ার্ড সঠিক নয়।", "err")
            call.respondRedirect("/student/login"); return@post
        }
        Throttle.ok(key)
        s.role = "student"; s.studentIds = mine.map { it.id }
        call.respondRedirect("/student")
    }

    get("/student") {
        val s = call.sess()
        if (s.role != "student") { call.respondRedirect("/student/login"); return@get }
        val rows = StringBuilder()
        for (id in s.studentIds) {
            val v = ResultService.view(id) ?: continue
            val published = v.session.published
            rows.append("<tr><td>${esc(v.examType.code)} ${v.session.year}</td><td>${esc(v.board.nameEn)}</td><td>${esc(v.student.roll)}</td><td>${esc(v.student.name)}</td><td>")
            if (!published) rows.append("""<span class="badge b-warn">প্রকাশিত হয়নি</span>""")
            else rows.append(Pages.badge(v.displayStatus) + " <a class=\"btn sm\" href=\"/student/result/$id\">দেখুন</a>")
            rows.append("</td></tr>")
        }
        call.html(s, "My results", """<div class="card"><h2>আমার ফলাফল</h2><div class="scroll"><table>
<tr><th>পরীক্ষা</th><th>বোর্ড</th><th>রোল</th><th>নাম</th><th>ফলাফল</th></tr>$rows</table></div></div>""")
    }

    get("/student/result/{id}") {
        val s = call.sess()
        val id = call.parameters["id"]?.toLongOrNull() ?: 0L
        if (s.role != "student" || id !in s.studentIds) { call.respondRedirect("/student/login"); return@get }
        val v = ResultService.view(id)
        if (v == null || !v.session.published) { s.say("ফলাফল এখনো প্রকাশিত হয়নি।", "err"); call.respondRedirect("/student"); return@get }
        call.html(s, "Result", Pages.marksheet(v, showMarks = true, pdfHref = "/student/result/$id/pdf") +
            """<p><a class="btn alt" href="/student">← ফিরে যান</a></p>""")
    }

    get("/student/result/{id}/pdf") {
        val s = call.sess()
        val id = call.parameters["id"]?.toLongOrNull() ?: 0L
        if (s.role != "student" || id !in s.studentIds) { call.respondRedirect("/student/login"); return@get }
        val v = ResultService.view(id)
        if (v == null || !v.session.published) { call.respondRedirect("/student"); return@get }
        call.sendPdf(v, true)
    }

    get("/student/password") {
        val s = call.sess()
        if (s.role != "student") { call.respondRedirect("/student/login"); return@get }
        call.html(s, "Password", Pages.passwordForm(s, "/student/password"))
    }

    post("/student/password") {
        val s = call.sess()
        val p = call.form(s) ?: return@post
        if (s.role != "student") { call.respondRedirect("/student/login"); return@post }
        val old = p["old"].orEmpty(); val n1 = p["new1"].orEmpty(); val n2 = p["new2"].orEmpty()
        val mine = s.studentIds.mapNotNull { db.students().student(it) }
        when {
            n1.length < 6 -> s.say("নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে।", "err")
            n1 != n2 -> s.say("দুটি নতুন পাসওয়ার্ড মেলেনি।", "err")
            mine.none { Security.verify(old, it.passwordHash) } -> s.say("বর্তমান পাসওয়ার্ড ভুল।", "err")
            else -> {
                val h = Security.hash(n1)
                mine.forEach { db.students().saveStudent(it.copy(passwordHash = h)) }
                s.say("পাসওয়ার্ড পরিবর্তন হয়েছে।")
            }
        }
        call.respondRedirect("/student/password")
    }

    // ---------------- teacher ----------------
    get("/teacher/login") {
        val s = call.sess()
        call.html(s, "Teacher login", Pages.loginForm(s, "শিক্ষক লগইন", "/teacher/login", "ইউজারনেম"))
    }

    post("/teacher/login") {
        val s = call.sess()
        val p = call.form(s) ?: return@post
        val user = p["user"]?.trim().orEmpty()
        val pw = p["password"].orEmpty()
        val key = "t:${user.lowercase()}:${call.ip()}"
        if (Throttle.blocked(key)) { s.say("অনেকবার ভুল হয়েছে। ১০ মিনিট পরে চেষ্টা করুন।", "err"); call.respondRedirect("/teacher/login"); return@post }
        val t = db.ref().teacherByUsername(user)
        if (t == null || !t.active || !Security.verify(pw, t.passwordHash)) {
            Throttle.fail(key)
            s.say("ইউজারনেম বা পাসওয়ার্ড সঠিক নয়।", "err")
            call.respondRedirect("/teacher/login"); return@post
        }
        Throttle.ok(key)
        s.role = "teacher"; s.userId = t.id
        call.respondRedirect("/teacher")
    }

    get("/teacher") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@get
        val inst = db.ref().institution(t.institutionId)
        if (inst == null) { s.say("আপনার প্রতিষ্ঠান পাওয়া যায়নি। অ্যাডমিনের সাথে যোগাযোগ করুন।", "err"); call.respondRedirect("/teacher/login"); return@get }
        val rows = StringBuilder()
        for (ses in db.sessions().sessionsOfBoard(inst.boardId)) {
            val et = db.ref().examType(ses.examTypeId) ?: continue
            val count = db.students().search(ses.id, inst.id, "").size
            rows.append("<tr><td>${esc(et.code)} ${ses.year}</td><td>$count</td><td>")
            rows.append(if (ses.published) """<span class="badge b-ok">প্রকাশিত</span>""" else """<span class="badge b-warn">অপ্রকাশিত</span>""")
            rows.append("""</td><td><a class="btn sm" href="/teacher/session/${ses.id}">খুলুন</a></td></tr>""")
        }
        call.html(s, "Teacher", """<div class="card"><h2>${esc(inst.nameEn)}</h2>
<p class="muted">EIIN: ${esc(inst.eiin)} · শিক্ষক: ${esc(t.name)}</p>
<div class="scroll"><table><tr><th>পরীক্ষা</th><th>আপনার শিক্ষার্থী</th><th>অবস্থা</th><th></th></tr>$rows</table></div></div>""")
    }

    get("/teacher/session/{sid}") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@get
        val ctx = call.sessionCtx(s, t) ?: return@get
        val (ses, inst, etCode) = ctx
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        val list = db.students().search(ses.id, inst.id, q)
        val rows = StringBuilder()
        for (st in list) {
            val grp = db.ref().group(st.groupId)?.nameEn ?: "-"
            rows.append("<tr><td>${esc(st.roll)}</td><td>${esc(st.name)}</td><td>${esc(grp)}</td><td>")
            rows.append(Pages.badge(if (st.withheld) "WITHHELD" else st.resultStatus))
            if (st.resultStatus == "PASSED") rows.append(" ${Pages.gpa(st.gpa)}")
            rows.append("""</td><td><a class="btn sm" href="/teacher/student/${st.id}/marks">নম্বর</a> <a class="btn sm alt" href="/teacher/student/${st.id}">প্রোফাইল</a></td></tr>""")
        }
        val add = if (ses.published) """<span class="badge b-warn">প্রকাশিত ফলাফল সম্পাদনা করা যায় না</span>"""
        else """<a class="btn" href="/teacher/session/${ses.id}/add">+ নতুন শিক্ষার্থী</a>"""
        call.html(s, "Session", """<div class="card"><h2>$etCode ${ses.year}</h2>
<form method="get" class="row"><div><input name="q" placeholder="নাম / রোল / রেজি. খুঁজুন" value="${esc(q)}"></div><div><button class="btn alt">খুঁজুন</button></div></form>
<p>$add</p><div class="scroll"><table><tr><th>রোল</th><th>নাম</th><th>গ্রুপ</th><th>ফলাফল</th><th></th></tr>$rows</table></div></div>""")
    }

    get("/teacher/session/{sid}/add") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@get
        val (ses, _, _) = call.sessionCtx(s, t) ?: return@get
        if (ses.published) { s.say("ফলাফল প্রকাশিত, নতুন শিক্ষার্থী যোগ করা যাবে না।", "err"); call.respondRedirect("/teacher/session/${ses.id}"); return@get }
        val groups = db.ref().groupsOf(ses.examTypeId)
        val gid = call.request.queryParameters["group"]?.toLongOrNull()
        val grp = groups.firstOrNull { it.id == gid }
        if (grp == null) {
            val opts = groups.joinToString("") { """<option value="${it.id}">${esc(it.nameEn)}${if (it.nameBn.isNotBlank()) " / " + esc(it.nameBn) else ""}</option>""" }
            call.html(s, "Add student", """<div class="card" style="max-width:420px"><h2>বিভাগ (গ্রুপ) বাছাই করুন</h2>
<form method="get"><select name="group">$opts</select><p><button class="btn">পরবর্তী</button></p></form></div>""")
            return@get
        }
        call.html(s, "Add student", studentForm(s, "/teacher/session/${ses.id}/add", ses, grp, null, emptyList()))
    }

    post("/teacher/session/{sid}/add") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        val p = call.form(s) ?: return@post
        val (ses, inst, _) = call.sessionCtx(s, t) ?: return@post
        if (ses.published) { s.say("ফলাফল প্রকাশিত।", "err"); call.respondRedirect("/teacher/session/${ses.id}"); return@post }
        val grp = db.ref().group(p["group"]?.toLongOrNull() ?: 0L)
        if (grp == null || grp.examTypeId != ses.examTypeId) { call.respondRedirect("/teacher/session/${ses.id}/add"); return@post }
        val back = "/teacher/session/${ses.id}/add?group=${grp.id}"

        val f = readFields(p)
        if (f.error != null) { s.say(f.error, "err"); call.respondRedirect(back); return@post }
        val picked = pickSubjects(p, offers(ses.examTypeId, grp.id))
        if (picked.error != null) { s.say(picked.error, "err"); call.respondRedirect(back); return@post }

        val password = Security.randomPassword(8)
        try {
            val id = db.students().addStudent(
                Student(
                    sessionId = ses.id, institutionId = inst.id, groupId = grp.id,
                    roll = f.roll, registration = f.reg, name = f.name, father = f.father, mother = f.mother,
                    dob = f.dob, gender = f.gender, studentType = f.type, phone = f.phone,
                    passwordHash = Security.hash(password), createdBy = t.id
                )
            )
            db.students().addStudentSubjects(picked.list.map { StudentSubject(studentId = id, subjectId = it.first, isFourth = it.second) })
            ResultService.recalc(id)
            s.say("শিক্ষার্থী যোগ হয়েছে। লগইন: রেজি. ${f.reg} · পাসওয়ার্ড: $password  (এটি আর দেখানো হবে না, শিক্ষার্থীকে জানিয়ে দিন)")
            call.respondRedirect("/teacher/student/$id/marks")
        } catch (e: SQLiteConstraintException) {
            s.say("এই রোল বা রেজিস্ট্রেশন নম্বর এই পরীক্ষায় আগে থেকেই আছে।", "err")
            call.respondRedirect(back)
        }
    }

    get("/teacher/student/{id}") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@get
        val own = call.ownStudent(s, t) ?: return@get
        val (st, ses) = own
        val grp = db.ref().group(st.groupId) ?: return@get
        val sel = db.students().studentSubjects(st.id)
        val locked = ses.published
        val form = studentForm(s, "/teacher/student/${st.id}", ses, grp, st, sel)
        val tools = """<div class="card"><h3>অন্যান্য</h3>
<form class="inline" method="post" action="/teacher/student/${st.id}/reset">${csrf(s)}<button class="btn alt sm">নতুন পাসওয়ার্ড তৈরি</button></form>
<form class="inline" method="post" action="/teacher/student/${st.id}/delete" onsubmit="return confirm('মুছে ফেলবেন?')">${csrf(s)}<button class="btn red sm">শিক্ষার্থী মুছুন</button></form></div>"""
        val note = if (locked) """<div class="flash err">ফলাফল প্রকাশিত — পরিবর্তন করা যাবে না।</div>""" else ""
        call.html(s, "Student", note + form + (if (locked) "" else tools))
    }

    post("/teacher/student/{id}") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        val p = call.form(s) ?: return@post
        val own = call.ownStudent(s, t) ?: return@post
        val (st, ses) = own
        if (ses.published) { s.say("ফলাফল প্রকাশিত।", "err"); call.respondRedirect("/teacher/student/${st.id}"); return@post }
        val f = readFields(p)
        if (f.error != null) { s.say(f.error, "err"); call.respondRedirect("/teacher/student/${st.id}"); return@post }
        val picked = pickSubjects(p, offers(ses.examTypeId, st.groupId))
        if (picked.error != null) { s.say(picked.error, "err"); call.respondRedirect("/teacher/student/${st.id}"); return@post }
        try {
            db.students().saveStudent(
                st.copy(roll = f.roll, registration = f.reg, name = f.name, father = f.father, mother = f.mother,
                    dob = f.dob, gender = f.gender, studentType = f.type, phone = f.phone)
            )
            val oldIds = db.students().studentSubjects(st.id).map { it.subjectId }.toSet()
            val newIds = picked.list.map { it.first }.toSet()
            (oldIds - newIds).forEach { db.students().clearMarksOfSubject(st.id, it) }
            db.students().clearStudentSubjects(st.id)
            db.students().addStudentSubjects(picked.list.map { StudentSubject(studentId = st.id, subjectId = it.first, isFourth = it.second) })
            ResultService.recalc(st.id)
            s.say("তথ্য সংরক্ষিত হয়েছে।")
            call.respondRedirect("/teacher/student/${st.id}/marks")
        } catch (e: SQLiteConstraintException) {
            s.say("এই রোল বা রেজিস্ট্রেশন নম্বর আগে থেকেই আছে।", "err")
            call.respondRedirect("/teacher/student/${st.id}")
        }
    }

    post("/teacher/student/{id}/reset") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        call.form(s) ?: return@post
        val (st, _) = call.ownStudent(s, t) ?: return@post
        val pw = Security.randomPassword(8)
        db.students().saveStudent(st.copy(passwordHash = Security.hash(pw)))
        s.say("নতুন পাসওয়ার্ড: $pw  (রেজি. ${st.registration}) — এটি আর দেখানো হবে না।")
        call.respondRedirect("/teacher/student/${st.id}")
    }

    post("/teacher/student/{id}/delete") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        call.form(s) ?: return@post
        val (st, ses) = call.ownStudent(s, t) ?: return@post
        if (ses.published) { s.say("ফলাফল প্রকাশিত।", "err"); call.respondRedirect("/teacher/student/${st.id}"); return@post }
        db.students().clearMarks(st.id)
        db.students().clearStudentSubjects(st.id)
        db.students().deleteStudentRow(st.id)
        s.say("শিক্ষার্থী মুছে ফেলা হয়েছে।")
        call.respondRedirect("/teacher/session/${ses.id}")
    }

    get("/teacher/student/{id}/marks") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@get
        val (st, ses) = call.ownStudent(s, t) ?: return@get
        call.html(s, "Marks", marksPage(s, st, ses))
    }

    post("/teacher/student/{id}/marks") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        val p = call.form(s) ?: return@post
        val (st, ses) = call.ownStudent(s, t) ?: return@post
        if (ses.published) { s.say("ফলাফল প্রকাশিত — নম্বর পরিবর্তন করা যাবে না।", "err"); call.respondRedirect("/teacher/student/${st.id}/marks"); return@post }
        val out = ArrayList<Mark>()
        for (r in subjectRows(st.id)) for (c in r.comps) {
            val absent = p["a_${c.id}"] != null
            val raw = p["m_${c.id}"]?.trim().orEmpty()
            var value: Int? = null
            if (!absent && raw.isNotEmpty()) {
                value = raw.toIntOrNull()
                if (value == null || value < 0 || value > c.maxMarks) {
                    s.say("${r.sub.code} ${r.sub.nameEn} / ${c.name}: নম্বর 0 থেকে ${c.maxMarks} এর মধ্যে হতে হবে।", "err")
                    call.respondRedirect("/teacher/student/${st.id}/marks"); return@post
                }
            }
            out.add(Mark(studentId = st.id, subjectId = r.sub.id, componentId = c.id, obtained = value, absent = absent))
        }
        db.students().putMarks(out)
        ResultService.recalc(st.id)
        s.say("নম্বর সংরক্ষিত হয়েছে।")
        call.respondRedirect("/teacher/student/${st.id}/marks")
    }

    get("/teacher/password") {
        val s = call.sess()
        call.teacher(s) ?: return@get
        call.html(s, "Password", Pages.passwordForm(s, "/teacher/password"))
    }

    post("/teacher/password") {
        val s = call.sess()
        val t = call.teacher(s) ?: return@post
        val p = call.form(s) ?: return@post
        val old = p["old"].orEmpty(); val n1 = p["new1"].orEmpty(); val n2 = p["new2"].orEmpty()
        when {
            n1.length < 6 -> s.say("নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে।", "err")
            n1 != n2 -> s.say("দুটি নতুন পাসওয়ার্ড মেলেনি।", "err")
            !Security.verify(old, t.passwordHash) -> s.say("বর্তমান পাসওয়ার্ড ভুল।", "err")
            else -> { db.ref().saveTeacher(t.copy(passwordHash = Security.hash(n1))); s.say("পাসওয়ার্ড পরিবর্তন হয়েছে।") }
        }
        call.respondRedirect("/teacher/password")
    }

    post("/logout") {
        val s = call.sess()
        call.form(s) ?: return@post
        Sessions.destroy(s.id)
        call.respondRedirect("/")
    }
}

// ───────────────────────────── page helpers ─────────────────────────────

private suspend fun ApplicationCall.renderHome(s: Sess) {
    val a = Security.randomInt(9) + 1
    val b = Security.randomInt(9) + 1
    s.captcha = a + b
    html(s, "Result", Pages.home(s, db.ref().examTypes(), db.ref().boards(), a, b))
}

private suspend fun ApplicationCall.sendPdf(v: ResultView, withMarks: Boolean) {
    val bytes = PdfMaker.make(v, withMarks)
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"result-${v.examType.code}-${v.student.roll}.pdf\"")
    respondBytes(bytes, ContentType.Application.Pdf)
}

private data class SessCtx(val ses: ExamSession, val inst: Institution, val examCode: String)

/** Validates that the session belongs to the teacher's board. */
private suspend fun ApplicationCall.sessionCtx(s: Sess, t: Teacher): SessCtx? {
    val sid = parameters["sid"]?.toLongOrNull() ?: 0L
    val ses = db.sessions().session(sid)
    val inst = db.ref().institution(t.institutionId)
    val et = ses?.let { db.ref().examType(it.examTypeId) }
    if (ses == null || inst == null || et == null || ses.boardId != inst.boardId) {
        s.say("পরীক্ষাটি পাওয়া যায়নি।", "err"); respondRedirect("/teacher"); return null
    }
    return SessCtx(ses, inst, et.code)
}

private suspend fun ApplicationCall.ownStudent(s: Sess, t: Teacher): Pair<Student, ExamSession>? {
    val id = parameters["id"]?.toLongOrNull() ?: 0L
    val st = db.students().student(id)
    if (st == null || st.institutionId != t.institutionId) {
        s.say("শিক্ষার্থী পাওয়া যায়নি।", "err"); respondRedirect("/teacher"); return null
    }
    val ses = db.sessions().session(st.sessionId)
    if (ses == null) { respondRedirect("/teacher"); return null }
    return st to ses
}

private class Fields(
    val roll: String = "", val reg: String = "", val name: String = "", val father: String = "", val mother: String = "",
    val dob: String = "", val gender: String = "", val type: String = "REGULAR", val phone: String = "",
    val error: String? = null
)

private fun readFields(p: Parameters): Fields {
    val roll = p["roll"]?.trim().orEmpty()
    val reg = p["reg"]?.trim().orEmpty()
    val name = p["name"]?.trim().orEmpty()
    val dob = p["dob"]?.trim().orEmpty()
    val phone = p["phone"]?.trim().orEmpty()
    val type = if (p["type"] == "IRREGULAR") "IRREGULAR" else "REGULAR"
    val gender = p["gender"]?.takeIf { it in listOf("M", "F", "O") } ?: ""
    val err = when {
        name.isEmpty() || name.length > 80 -> "নাম দিন (সর্বোচ্চ ৮০ অক্ষর)।"
        !rollRx.matches(roll) -> "রোল ৩–১০ অঙ্কের সংখ্যা হতে হবে।"
        !regRx.matches(reg) -> "রেজিস্ট্রেশন ৫–১৫ অঙ্কের সংখ্যা হতে হবে।"
        dob.isNotEmpty() && !dobRx.matches(dob) -> "জন্ম তারিখ সঠিক নয়।"
        phone.isNotEmpty() && !phoneRx.matches(phone) -> "ফোন নম্বর সঠিক নয়।"
        else -> null
    }
    return Fields(roll, reg, name, p["father"]?.trim().orEmpty(), p["mother"]?.trim().orEmpty(), dob, gender, type, phone, err)
}

private class Picked(val list: List<Pair<Long, Boolean>> = emptyList(), val error: String? = null)

private fun pickSubjects(p: Parameters, offers: List<Offer>): Picked {
    val chosen = ArrayList<Pair<Long, Boolean>>()
    offers.filter { it.a.kind == "COMPULSORY" }.forEach { chosen.add(it.sub.id to false) }
    val keys = offers.filter { it.a.kind == "SELECTIVE" }.map { it.a.choiceKey }.distinct()
    for (k in keys) {
        val pick = p["sel_$k"]?.toLongOrNull()
        val ok = offers.firstOrNull { it.a.kind == "SELECTIVE" && it.a.choiceKey == k && it.sub.id == pick }
        if (ok == null) return Picked(error = "'$k' এর জন্য একটি বিষয় বাছাই করুন।")
        chosen.add(ok.sub.id to false)
    }
    val fourth = p["fourth"]?.toLongOrNull()
    if (fourth != null && fourth > 0) {
        val ok = offers.firstOrNull { it.a.kind == "FOURTH" && it.sub.id == fourth }
            ?: return Picked(error = "৪র্থ বিষয় সঠিক নয়।")
        if (chosen.none { it.first == ok.sub.id }) chosen.add(ok.sub.id to true)
    }
    return Picked(chosen)
}

private suspend fun studentForm(
    s: Sess, action: String, ses: ExamSession, grp: StudyGroup, st: Student?, sel: List<StudentSubject>
): String {
    val offs = offers(ses.examTypeId, grp.id)
    val selected = sel.map { it.subjectId }.toSet()
    val fourthSel = sel.firstOrNull { it.isFourth }?.subjectId
    val sb = StringBuilder()
    sb.append("""<div class="card"><h2>${if (st == null) "নতুন শিক্ষার্থী" else "শিক্ষার্থীর প্রোফাইল"} — ${esc(grp.nameEn)}</h2>
<form method="post" action="$action">${csrf(s)}<input type="hidden" name="group" value="${grp.id}"><div class="row">""")
    fun inp(label: String, name: String, value: String, extra: String = "") =
        sb.append("""<div><label>$label</label><input name="$name" value="${esc(value)}" $extra></div>""")
    inp("নাম (Name)", "name", st?.name ?: "", "required")
    inp("রোল (Roll)", "roll", st?.roll ?: "", "required inputmode=numeric")
    inp("রেজিস্ট্রেশন (Registration)", "reg", st?.registration ?: "", "required inputmode=numeric")
    inp("পিতার নাম", "father", st?.father ?: "")
    inp("মাতার নাম", "mother", st?.mother ?: "")
    inp("জন্ম তারিখ", "dob", st?.dob ?: "", "type=date")
    inp("ফোন (ঐচ্ছিক)", "phone", st?.phone ?: "", "inputmode=tel")
    fun dd(label: String, name: String, opts: List<Pair<String, String>>, cur: String) {
        sb.append("""<div><label>$label</label><select name="$name">""")
        opts.forEach { (v, l) -> sb.append("""<option value="$v"${if (v == cur) " selected" else ""}>$l</option>""") }
        sb.append("</select></div>")
    }
    dd("লিঙ্গ", "gender", listOf("" to "—", "M" to "পুরুষ", "F" to "মহিলা", "O" to "অন্যান্য"), st?.gender ?: "")
    dd("ধরন", "type", listOf("REGULAR" to "Regular", "IRREGULAR" to "Irregular"), st?.studentType ?: "REGULAR")
    sb.append("</div><h3>বিষয়</h3>")

    val compulsory = offs.filter { it.a.kind == "COMPULSORY" }
    if (compulsory.isNotEmpty()) {
        sb.append("""<p class="muted small">আবশ্যিক: ${compulsory.joinToString(", ") { esc(it.sub.code + " " + it.sub.nameEn) }}</p>""")
    }
    offs.filter { it.a.kind == "SELECTIVE" }.groupBy { it.a.choiceKey }.forEach { (key, list) ->
        sb.append("""<div class="opts"><label>${esc(key)} (একটি বাছাই করুন)</label>""")
        val cur = list.firstOrNull { it.sub.id in selected }?.sub?.id
        list.forEach {
            sb.append("""<label><input type="radio" name="sel_${esc(key)}" value="${it.sub.id}"${if (cur == it.sub.id) " checked" else ""}> ${esc(it.sub.code)} ${esc(it.sub.nameEn)}</label>""")
        }
        sb.append("</div>")
    }
    val fourth = offs.filter { it.a.kind == "FOURTH" }
    if (fourth.isNotEmpty()) {
        sb.append("""<label>৪র্থ বিষয় (ঐচ্ছিক)</label><select name="fourth"><option value="0">— নেই —</option>""")
        fourth.forEach { sb.append("""<option value="${it.sub.id}"${if (fourthSel == it.sub.id) " selected" else ""}>${esc(it.sub.code)} ${esc(it.sub.nameEn)}</option>""") }
        sb.append("</select>")
    }
    sb.append("""<p><button class="btn">সংরক্ষণ</button> ${if (st != null) """<a class="btn alt" href="/teacher/student/${st.id}/marks">নম্বর</a>""" else ""}</p></form></div>""")
    return sb.toString()
}

private class SubRow(val ss: StudentSubject, val sub: Subject, val comps: List<SubjectComponent>)

private suspend fun subjectRows(studentId: Long): List<SubRow> =
    db.students().studentSubjects(studentId)
        .mapNotNull { ss -> db.ref().subject(ss.subjectId)?.let { SubRow(ss, it, db.ref().components(it.id)) } }
        .sortedWith(compareBy<SubRow>({ it.ss.isFourth }, { it.sub.code }))

private suspend fun marksPage(s: Sess, st: Student, ses: ExamSession): String {
    val rows = subjectRows(st.id)
    val marks = db.students().marks(st.id).associateBy { it.componentId }
    val locked = ses.published
    val sb = StringBuilder()
    sb.append("""<div class="card"><h2>${esc(st.name)} <span class="muted small">Roll ${esc(st.roll)}</span></h2>""")
    if (locked) sb.append("""<div class="flash err">ফলাফল প্রকাশিত — নম্বর পরিবর্তন করা যাবে না।</div>""")
    sb.append("""<form method="post" action="/teacher/student/${st.id}/marks">${csrf(s)}<div class="scroll"><table>
<tr><th>বিষয়</th><th>অংশ</th><th>নম্বর</th><th>অনুপস্থিত</th></tr>""")
    for (r in rows) {
        r.comps.forEachIndexed { i, c ->
            val m = marks[c.id]
            sb.append("<tr>")
            if (i == 0) sb.append("""<td rowspan="${r.comps.size}"><b>${esc(r.sub.code)}</b> ${esc(r.sub.nameEn)}${if (r.ss.isFourth) " <span class=\"muted small\">(4th)</span>" else ""}</td>""")
            sb.append("<td>${esc(c.name)} <span class=\"muted small\">/${c.maxMarks} (pass ${c.passMarks})</span></td>")
            sb.append("""<td><input style="max-width:110px" type="number" min="0" max="${c.maxMarks}" name="m_${c.id}" value="${m?.obtained ?: ""}"${if (locked) " disabled" else ""}></td>""")
            sb.append("""<td><input type="checkbox" name="a_${c.id}"${if (m?.absent == true) " checked" else ""}${if (locked) " disabled" else ""}></td></tr>""")
        }
    }
    sb.append("</table></div>")
    if (!locked) sb.append("""<p><button class="btn">নম্বর সংরক্ষণ</button></p>""")
    sb.append("</form></div>")

    // live preview using the configured scheme
    val v = ResultService.view(st.id)
    if (v != null) {
        sb.append("""<div class="card"><h3>প্রাপ্ত ফলাফল (প্রিভিউ)</h3><p>${Pages.badge(v.displayStatus)} """)
        if (v.out.status == "PASSED") sb.append("GPA <b>${Pages.gpa(v.out.gpa)}</b> (${esc(v.out.letter)})")
        sb.append("</p><div class=\"scroll\"><table><tr><th>কোড</th><th>বিষয়</th><th>মোট</th><th>গ্রেড</th><th>GP</th></tr>")
        v.out.subjects.forEach {
            sb.append("<tr><td>${esc(it.code)}</td><td>${esc(it.name)}</td><td>${if (it.complete) "${it.total}/${it.max}" else "—"}</td><td>${esc(it.letter)}</td><td>${Pages.gpa(it.point)}</td></tr>")
        }
        sb.append("</table></div></div>")
    }
    sb.append("""<p><a class="btn alt" href="/teacher/session/${ses.id}">← তালিকা</a></p>""")
    return sb.toString()
}
