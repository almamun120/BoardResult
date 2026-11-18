package com.boardresult.server

import com.boardresult.core.Graph
import com.boardresult.core.ResultView
import com.boardresult.data.Board
import com.boardresult.data.ExamType
import java.util.Locale

object Pages {
    fun esc(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun csrf(s: Sess) = """<input type="hidden" name="_csrf" value="${s.csrf}">"""

    private const val CSS = """
*{box-sizing:border-box}body{margin:0;font-family:"Noto Sans Bengali","Kalpurush",system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#eef3f1;color:#17211d;line-height:1.5}
header{background:#0b6e4f;color:#fff;padding:12px 16px;display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;justify-content:space-between}
header h1{font-size:18px;margin:0}header nav{display:flex;flex-wrap:wrap;gap:6px 16px;align-items:center}
header a,header button.link{color:#fff;text-decoration:none;font-size:14px;background:none;border:0;cursor:pointer;font-family:inherit;padding:0}
main{max-width:980px;margin:16px auto;padding:0 12px}
.card{background:#fff;border-radius:12px;padding:16px 18px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
h2{margin:0 0 12px;font-size:19px}h3{margin:14px 0 8px;font-size:16px}
label{display:block;font-size:13px;color:#445;margin:10px 0 4px}
input,select{width:100%;padding:10px;border:1px solid #b8c5bf;border-radius:8px;font-size:15px;font-family:inherit;background:#fff}
input[type=checkbox],input[type=radio]{width:auto}
.btn{display:inline-block;background:#0b6e4f;color:#fff;border:0;border-radius:8px;padding:10px 18px;font-size:15px;cursor:pointer;text-decoration:none;font-family:inherit}
.btn.alt{background:#fff;color:#0b6e4f;border:1px solid #0b6e4f}.btn.red{background:#b3261e}.btn.sm{padding:6px 12px;font-size:13px}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:0 14px}
table{width:100%;border-collapse:collapse;font-size:14px}th,td{padding:8px 6px;text-align:left;border-bottom:1px solid #e1e8e4;vertical-align:top}
th{background:#f3f7f5;font-size:13px}.scroll{overflow-x:auto}
.flash{padding:10px 14px;border-radius:8px;margin-bottom:12px}.flash.ok{background:#dff3e7;color:#124d2c}.flash.err{background:#fbe0de;color:#7a1710}
.badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;font-weight:600}
.b-ok{background:#dff3e7;color:#124d2c}.b-bad{background:#fbe0de;color:#7a1710}.b-warn{background:#fff1cc;color:#6a4a00}
.sheet h2{text-align:center;margin-bottom:2px}.sheet .sub{text-align:center;color:#556;margin-bottom:12px;font-size:14px}
.info td:nth-child(odd){font-weight:600;color:#334;width:18%}.big{font-size:20px;font-weight:700}
.muted{color:#667}.small{font-size:12px}form.inline{display:inline}
.opts label{display:inline-block;margin-right:14px}
footer{text-align:center;color:#778;font-size:12px;padding:18px}
@media print{header,footer,.noprint{display:none!important}body{background:#fff}.card{box-shadow:none}}
"""

    fun layout(s: Sess, title: String, body: String): String {
        val nav = when (s.role) {
            "teacher" -> """<a href="/teacher">ড্যাশবোর্ড</a><a href="/teacher/password">পাসওয়ার্ড</a>${logout(s)}"""
            "student" -> """<a href="/student">আমার ফলাফল</a><a href="/student/password">পাসওয়ার্ড</a>${logout(s)}"""
            else -> """<a href="/">ফলাফল</a><a href="/student/login">শিক্ষার্থী লগইন</a><a href="/teacher/login">শিক্ষক লগইন</a>"""
        }
        val flash = s.flash?.let { """<div class="flash ${s.flashKind}">${esc(it)}</div>""" } ?: ""
        s.flash = null
        return """<!doctype html><html lang="bn"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>$CSS</style></head>
<body><header><h1>${esc(Graph.prefs.portalTitle)}</h1><nav>$nav</nav></header>
<main>$flash$body</main><footer>${esc(Graph.prefs.portalTitle)}</footer></body></html>"""
    }

    private fun logout(s: Sess) =
        """<form class="inline" method="post" action="/logout">${csrf(s)}<button class="link">লগআউট</button></form>"""

    fun badge(status: String): String = when (status) {
        "PASSED" -> """<span class="badge b-ok">PASSED</span>"""
        "FAILED" -> """<span class="badge b-bad">FAILED</span>"""
        "WITHHELD" -> """<span class="badge b-warn">WITHHELD</span>"""
        "ABSENT" -> """<span class="badge b-bad">ABSENT</span>"""
        else -> """<span class="badge b-warn">INCOMPLETE</span>"""
    }

    fun gpa(d: Double): String = String.format(Locale.US, "%.2f", d)

    fun home(s: Sess, exams: List<ExamType>, boards: List<Board>, a: Int, b: Int): String {
        val examOpts = exams.joinToString("") { """<option value="${it.id}">${esc(it.code)} - ${esc(it.nameEn)}</option>""" }
        val boardOpts = boards.joinToString("") { """<option value="${it.id}">${esc(it.nameEn)}</option>""" }
        return """<div class="card"><h2>পরীক্ষার ফলাফল</h2>
<form method="post" action="/result">${csrf(s)}
<div class="row">
<div><label>পরীক্ষা (Examination)</label><select name="exam">$examOpts</select></div>
<div><label>সাল (Year)</label><input name="year" inputmode="numeric" maxlength="4" required></div>
<div><label>বোর্ড (Board)</label><select name="board">$boardOpts</select></div>
<div><label>রোল (Roll)</label><input name="roll" inputmode="numeric" required></div>
<div><label>রেজিস্ট্রেশন নম্বর (Registration)</label><input name="reg" inputmode="numeric" required></div>
<div><label>$a + $b = ?</label><input name="captcha" inputmode="numeric" required></div>
</div><p><button class="btn">ফলাফল দেখুন</button></p></form></div>
<div class="card muted small">SMS-এ ফলাফল: <b>EXAM BOARD ROLL YEAR</b> লিখে এই ফোন নম্বরে পাঠান। যেমন: <b>SSC DHA 123456 2023</b></div>"""
    }

    fun loginForm(s: Sess, title: String, action: String, userLabel: String): String =
        """<div class="card" style="max-width:420px;margin:auto"><h2>$title</h2>
<form method="post" action="$action">${csrf(s)}
<label>$userLabel</label><input name="user" required autocomplete="username">
<label>পাসওয়ার্ড</label><input name="password" type="password" required autocomplete="current-password">
<p><button class="btn">লগইন</button></p></form></div>"""

    fun passwordForm(s: Sess, action: String): String =
        """<div class="card" style="max-width:420px;margin:auto"><h2>পাসওয়ার্ড পরিবর্তন</h2>
<form method="post" action="$action">${csrf(s)}
<label>বর্তমান পাসওয়ার্ড</label><input name="old" type="password" required>
<label>নতুন পাসওয়ার্ড (কমপক্ষে 6 অক্ষর)</label><input name="new1" type="password" required minlength="6">
<label>নতুন পাসওয়ার্ড আবার</label><input name="new2" type="password" required minlength="6">
<p><button class="btn">পরিবর্তন করুন</button></p></form></div>"""

    /** The result sheet. showMarks=false mimics the public board site (grades only). */
    fun marksheet(v: ResultView, showMarks: Boolean, pdfHref: String?): String {
        val s = v.student
        val o = v.out
        val inst = v.institution?.let { "${esc(it.nameEn)} (EIIN ${esc(it.eiin)})" } ?: "-"
        val status = v.displayStatus
        val sb = StringBuilder()
        sb.append("""<div class="card sheet"><h2>${esc(v.examType.nameEn)} (${esc(v.examType.code)}) ${v.session.year}</h2>
<div class="sub">Board of Intermediate and Secondary Education, ${esc(v.board.nameEn)}</div>
<div class="scroll"><table class="info">
<tr><td>Roll No</td><td>${esc(s.roll)}</td><td>Registration</td><td>${esc(s.registration)}</td></tr>
<tr><td>Name</td><td>${esc(s.name)}</td><td>Board</td><td>${esc(v.board.nameEn)}</td></tr>
<tr><td>Father's Name</td><td>${esc(s.father)}</td><td>Group</td><td>${esc(v.group?.nameEn)}</td></tr>
<tr><td>Mother's Name</td><td>${esc(s.mother)}</td><td>Type</td><td>${esc(s.studentType.lowercase().replaceFirstChar { it.uppercase() })}</td></tr>
<tr><td>Date of Birth</td><td>${esc(s.dob)}</td><td>Result</td><td>${badge(status)}</td></tr>
<tr><td>Institute</td><td colspan="3">$inst</td></tr></table></div>""")

        when {
            s.withheld -> sb.append("""<p class="big" style="text-align:center">Result withheld. Please contact your institution.</p>""")
            o.status == "PENDING" -> sb.append("""<p class="big" style="text-align:center">Result not complete.</p>""")
            else -> {
                sb.append("""<h3>Grade Sheet</h3><div class="scroll"><table><tr><th>Code</th><th>Subject</th>""")
                if (showMarks) sb.append("<th>Marks</th>")
                sb.append("<th>Grade</th>")
                if (showMarks) sb.append("<th>GP</th>")
                sb.append("</tr>")
                o.subjects.forEach { sub ->
                    sb.append("<tr><td>${esc(sub.code)}</td><td>${esc(sub.name)}${if (sub.isFourth) " <span class=\"muted small\">(4th subject)</span>" else ""}</td>")
                    if (showMarks) {
                        val detail = sub.comps.joinToString(" · ") {
                            "${esc(it.name)} ${if (it.absent) "Abs" else (it.obtained ?: "-")}"
                        }
                        sb.append("<td>${sub.total}/${sub.max}<div class=\"muted small\">$detail</div></td>")
                    }
                    sb.append("<td><b>${esc(sub.letter)}</b></td>")
                    if (showMarks) sb.append("<td>${gpa(sub.point)}</td>")
                    sb.append("</tr>")
                }
                sb.append("</table></div>")
                sb.append("""<p class="big">GPA: ${gpa(o.gpa)} (${esc(o.letter)})</p>""")
                if (showMarks) sb.append("<p>Total marks: ${o.total}</p>")
                if (o.status == "FAILED") sb.append("""<p class="muted">Failed in: ${esc(o.failedCodes.joinToString(", "))}</p>""")
            }
        }
        if (pdfHref != null) {
            sb.append("""<p class="noprint"><a class="btn" href="$pdfHref">PDF ডাউনলোড</a> <button class="btn alt" onclick="window.print()">প্রিন্ট</button></p>""")
        }
        sb.append("</div>")
        return sb.toString()
    }
}
