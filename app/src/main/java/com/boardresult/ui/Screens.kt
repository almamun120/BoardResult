package com.boardresult.ui

import android.Manifest
import android.database.sqlite.SQLiteConstraintException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boardresult.core.Backup
import com.boardresult.core.Graph
import com.boardresult.core.ResultService
import com.boardresult.core.Security
import com.boardresult.data.*
import com.boardresult.server.WebServer
import com.boardresult.service.ServerService
import com.boardresult.service.ServerState
import com.boardresult.sms.SmsQueue
import com.boardresult.sms.SmsResponder
import kotlinx.coroutines.launch

private val db get() = Graph.db
private val prefs get() = Graph.prefs

private fun num(s: String?) = s?.trim()?.toIntOrNull()
private fun dec(s: String?) = s?.trim()?.toDoubleOrNull()

// ───────────────────────────── Home ─────────────────────────────

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val running by ServerState.running.collectAsState()
    val err by ServerState.error.collectAsState()
    val students by db.sessions().studentTotalFlow().collectAsState(0)
    val published by db.sessions().publishedCountFlow().collectAsState(0)
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Web portal (teacher / student)", fontWeight = FontWeight.Bold)
                Text(if (running) "Running on port ${prefs.port}" else "Stopped", color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (running) ServerState.lanAddresses().forEach { Text("http://$it:${prefs.port}") }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { if (running) ServerService.stop(ctx) else ServerService.start(ctx) }) {
                    Text(if (running) "Stop server" else "Start server")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Summary", fontWeight = FontWeight.Bold)
                Text("Students: $students · Published exam sessions: $published")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Permissions", fontWeight = FontWeight.Bold)
                Text("SMS receive/send, SIM info and notifications are needed for the SMS result service.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    perms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Grant permissions") }
            }
        }
    }
}

// ───────────────────────────── Results (exam sessions) ─────────────────────────────

@Composable
fun ResultsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by db.sessions().sessionsFlow().collectAsState(emptyList())
    val exams by db.ref().examTypesFlow().collectAsState(emptyList())
    val boards by db.ref().boardsFlow().collectAsState(emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<ExamSession?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    CrudList(sessions, "No exam sessions yet. Tap + to create one (exam type + board + year).", { showAdd = true }) { ses ->
        val et = exams.firstOrNull { it.id == ses.examTypeId }
        val bd = boards.firstOrNull { it.id == ses.boardId }
        val stats by produceState("…", ses.id, tick, ses.published) {
            val d = db.sessions()
            value = "Students ${d.studentCount(ses.id)} · Passed ${d.countByStatus(ses.id, "PASSED")} · " +
                "Failed ${d.countByStatus(ses.id, "FAILED")} · Incomplete ${d.countByStatus(ses.id, "PENDING")} · GPA 5.00: ${d.countGpa5(ses.id)}"
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${et?.code ?: "?"} ${ses.year} — ${bd?.nameEn ?: "?"}", fontWeight = FontWeight.Bold)
                Text(stats, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ses.published) "Published" else "Unpublished", Modifier.weight(1f),
                        color = if (ses.published) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = ses.published, onCheckedChange = { on ->
                        scope.launch {
                            if (on) ResultService.recalcSession(ses.id)
                            db.sessions().saveSession(ses.copy(published = on, publishedAt = if (on) System.currentTimeMillis() else null))
                            val pending = db.sessions().countByStatus(ses.id, "PENDING")
                            if (on && pending > 0) toast(ctx, "Published. Note: $pending student(s) have incomplete marks and won't show a result.")
                            tick++
                        }
                    })
                }
                Row {
                    TextButton(onClick = { scope.launch { ResultService.recalcSession(ses.id); tick++; toast(ctx, "Recalculated") } }) { Text("Recalculate") }
                    TextButton(onClick = { del = ses }) { Text("Delete") }
                }
            }
        }
    }

    if (showAdd) FormDialog(
        "New exam session",
        listOf(
            Fld("exam", "Exam type", FKind.DROPDOWN, exams.map { it.id.toString() to "${it.code} - ${it.nameEn}" }),
            Fld("board", "Board", FKind.DROPDOWN, boards.map { it.id.toString() to it.nameEn }),
            Fld("year", "Year", FKind.NUMBER)
        ),
        mapOf("year" to java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()),
        { showAdd = false }
    ) { v ->
        val e = v["exam"]?.toLongOrNull(); val b = v["board"]?.toLongOrNull(); val y = num(v["year"])
        if (e == null || b == null || y == null || y < 1990 || y > 2100) return@FormDialog "Select exam, board and a valid year."
        if (db.sessions().sessionFor(e, b, y) != null) return@FormDialog "This session already exists."
        db.sessions().addSession(ExamSession(examTypeId = e, boardId = b, year = y)); null
    }

    del?.let { s ->
        ConfirmDialog("Delete this session and ALL its students and marks? This cannot be undone.", {
            scope.launch {
                db.students().studentsOfSession(s.id).forEach {
                    db.students().clearMarks(it.id); db.students().clearStudentSubjects(it.id); db.students().deleteStudentRow(it.id)
                }
                db.sessions().deleteSession(s)
            }
            del = null
        }, { del = null })
    }
}

// ───────────────────────────── Setup menu ─────────────────────────────

@Composable
fun SetupScreen(onOpen: (Screen) -> Unit) {
    val entries = listOf(
        Triple(Screen.ExamTypes, "Exam types", "SSC, HSC … (also the SMS keyword) and their grading scheme"),
        Triple(Screen.Groups, "Groups / divisions", "Science, Humanities, Business … per exam type"),
        Triple(Screen.Subjects, "Subjects", "Codes, mark components, group assignment, compulsory / selective / optional (4th)"),
        Triple(Screen.Schemes, "Grading schemes", "Grade bands, GPA cap, 4th-subject rule"),
        Triple(Screen.Boards, "Boards", "Board name and 3-letter SMS code"),
        Triple(Screen.Institutions, "Institutions", "School / college name and EIIN"),
        Triple(Screen.Teachers, "Teachers", "Web-portal accounts tied to an institution")
    )
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        items(entries) { (s, t, sub) -> ItemCard(t, sub, onClick = { onOpen(s) }) }
    }
}

// ───────────────────────────── Boards ─────────────────────────────

@Composable
fun BoardsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.ref().boardsFlow().collectAsState(emptyList())
    var edit by remember { mutableStateOf<Board?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Board?>(null) }

    CrudList(list, "No boards.", { adding = true }) { b ->
        ItemCard("${b.code} — ${b.nameEn}", b.nameBn, actions = { EditDeleteActions({ edit = b }, { del = b }) })
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add board" else "Edit board",
            listOf(Fld("code", "SMS code (3 letters)"), Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)")),
            mapOf("code" to (cur?.code ?: ""), "en" to (cur?.nameEn ?: ""), "bn" to (cur?.nameBn ?: "")),
            { adding = false; edit = null }
        ) { v ->
            val code = v["code"].orEmpty().trim().uppercase(); val en = v["en"].orEmpty().trim()
            if (!Regex("^[A-Z]{3}$").matches(code)) return@FormDialog "Code must be exactly 3 letters."
            if (en.isEmpty()) return@FormDialog "English name required."
            try {
                if (cur == null) db.ref().addBoard(Board(code = code, nameEn = en, nameBn = v["bn"].orEmpty().trim()))
                else db.ref().saveBoard(cur.copy(code = code, nameEn = en, nameBn = v["bn"].orEmpty().trim()))
                null
            } catch (e: SQLiteConstraintException) { "This code already exists." }
        }
    }
    del?.let { b ->
        ConfirmDialog("Delete ${b.nameEn}?", {
            scope.launch { if (db.ref().boardUse(b.id) > 0) toast(ctx, "Board is in use (institutions or sessions).") else db.ref().deleteBoard(b) }
            del = null
        }, { del = null })
    }
}

// ───────────────────────────── Institutions ─────────────────────────────

@Composable
fun InstitutionsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.ref().institutionsFlow().collectAsState(emptyList())
    val boards by db.ref().boardsFlow().collectAsState(emptyList())
    var edit by remember { mutableStateOf<Institution?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Institution?>(null) }

    CrudList(list, "No institutions.", { adding = true }) { i ->
        val bn = boards.firstOrNull { it.id == i.boardId }?.nameEn ?: "?"
        ItemCard(i.nameEn, "EIIN ${i.eiin} · $bn · ${i.type}${if (i.district.isNotBlank()) " · ${i.district}" else ""}",
            actions = { EditDeleteActions({ edit = i }, { del = i }) })
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add institution" else "Edit institution",
            listOf(
                Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)"), Fld("eiin", "EIIN", FKind.NUMBER),
                Fld("board", "Board", FKind.DROPDOWN, boards.map { it.id.toString() to it.nameEn }),
                Fld("district", "District"),
                Fld("type", "Type", FKind.DROPDOWN, listOf("SCHOOL", "COLLEGE", "MADRASAH", "TECHNICAL").map { it to it })
            ),
            mapOf("en" to (cur?.nameEn ?: ""), "bn" to (cur?.nameBn ?: ""), "eiin" to (cur?.eiin ?: ""),
                "board" to (cur?.boardId?.toString() ?: ""), "district" to (cur?.district ?: ""), "type" to (cur?.type ?: "SCHOOL")),
            { adding = false; edit = null }
        ) { v ->
            val en = v["en"].orEmpty().trim(); val eiin = v["eiin"].orEmpty().trim(); val b = v["board"]?.toLongOrNull()
            if (en.isEmpty()) return@FormDialog "Name required."
            if (!Regex("^\\d{4,8}$").matches(eiin)) return@FormDialog "EIIN must be 4–8 digits."
            if (b == null) return@FormDialog "Select a board."
            val ni = Institution(id = cur?.id ?: 0, nameEn = en, nameBn = v["bn"].orEmpty().trim(), eiin = eiin, boardId = b,
                district = v["district"].orEmpty().trim(), type = v["type"] ?: "SCHOOL")
            try { if (cur == null) db.ref().addInstitution(ni) else db.ref().saveInstitution(ni); null }
            catch (e: SQLiteConstraintException) { "This EIIN already exists." }
        }
    }
    del?.let { i ->
        ConfirmDialog("Delete ${i.nameEn}?", {
            scope.launch { if (db.ref().institutionUse(i.id) > 0) toast(ctx, "In use by students or teachers.") else db.ref().deleteInstitution(i) }
            del = null
        }, { del = null })
    }
}

// ───────────────────────────── Teachers ─────────────────────────────

@Composable
fun TeachersScreen() {
    val scope = rememberCoroutineScope()
    val list by db.ref().teachersFlow().collectAsState(emptyList())
    val insts by db.ref().institutionsFlow().collectAsState(emptyList())
    var edit by remember { mutableStateOf<Teacher?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Teacher?>(null) }

    CrudList(list, "No teachers. Add an institution first, then a teacher.", { adding = true }) { t ->
        val inst = insts.firstOrNull { it.id == t.institutionId }?.nameEn ?: "?"
        ItemCard("${t.name} (${t.username})", "$inst · ${if (t.active) "active" else "disabled"}",
            actions = { EditDeleteActions({ edit = t }, { del = t }) })
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add teacher" else "Edit teacher (leave password empty to keep)",
            listOf(
                Fld("name", "Full name"), Fld("user", "Username"), Fld("pw", "Password", FKind.PASSWORD),
                Fld("inst", "Institution", FKind.DROPDOWN, insts.map { it.id.toString() to "${it.nameEn} (${it.eiin})" }),
                Fld("active", "Active", FKind.SWITCH)
            ),
            mapOf("name" to (cur?.name ?: ""), "user" to (cur?.username ?: ""), "inst" to (cur?.institutionId?.toString() ?: ""),
                "active" to (cur?.active ?: true).toString()),
            { adding = false; edit = null }
        ) { v ->
            val name = v["name"].orEmpty().trim(); val user = v["user"].orEmpty().trim(); val pw = v["pw"].orEmpty()
            val inst = v["inst"]?.toLongOrNull()
            if (name.isEmpty()) return@FormDialog "Name required."
            if (!Regex("^[A-Za-z0-9._-]{3,30}$").matches(user)) return@FormDialog "Username: 3–30 letters/digits/._-"
            if (inst == null) return@FormDialog "Select an institution."
            if (cur == null && pw.length < 6) return@FormDialog "Password must be at least 6 characters."
            if (cur != null && pw.isNotEmpty() && pw.length < 6) return@FormDialog "Password must be at least 6 characters."
            try {
                if (cur == null) db.ref().addTeacher(Teacher(username = user, name = name, passwordHash = Security.hash(pw), institutionId = inst, active = v["active"] == "true"))
                else db.ref().saveTeacher(cur.copy(username = user, name = name, institutionId = inst, active = v["active"] == "true",
                    passwordHash = if (pw.isEmpty()) cur.passwordHash else Security.hash(pw)))
                null
            } catch (e: SQLiteConstraintException) { "Username already taken." }
        }
    }
    del?.let { t -> ConfirmDialog("Delete ${t.name}?", { scope.launch { db.ref().deleteTeacher(t) }; del = null }, { del = null }) }
}

// ───────────────────────────── Exam types ─────────────────────────────

@Composable
fun ExamTypesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.ref().examTypesFlow().collectAsState(emptyList())
    val schemes by db.ref().schemesFlow().collectAsState(emptyList())
    var edit by remember { mutableStateOf<ExamType?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<ExamType?>(null) }

    CrudList(list, "No exam types.", { adding = true }) { e ->
        ItemCard("${e.code} — ${e.nameEn}", "Scheme: ${schemes.firstOrNull { it.id == e.schemeId }?.name ?: "?"}",
            actions = { EditDeleteActions({ edit = e }, { del = e }) })
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add exam type" else "Edit exam type",
            listOf(
                Fld("code", "Code (SMS keyword, e.g. SSC)"), Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)"),
                Fld("scheme", "Grading scheme", FKind.DROPDOWN, schemes.map { it.id.toString() to it.name })
            ),
            mapOf("code" to (cur?.code ?: ""), "en" to (cur?.nameEn ?: ""), "bn" to (cur?.nameBn ?: ""), "scheme" to (cur?.schemeId?.toString() ?: "")),
            { adding = false; edit = null }
        ) { v ->
            val code = v["code"].orEmpty().trim().uppercase(); val en = v["en"].orEmpty().trim(); val sc = v["scheme"]?.toLongOrNull()
            if (!Regex("^[A-Z0-9]{2,12}$").matches(code)) return@FormDialog "Code: 2–12 letters/digits."
            if (en.isEmpty()) return@FormDialog "Name required."
            if (sc == null) return@FormDialog "Select a grading scheme."
            try {
                if (cur == null) db.ref().addExamType(ExamType(code = code, nameEn = en, nameBn = v["bn"].orEmpty().trim(), schemeId = sc))
                else { db.ref().saveExamType(cur.copy(code = code, nameEn = en, nameBn = v["bn"].orEmpty().trim(), schemeId = sc)); ResultService.recalcAll() }
                null
            } catch (e: SQLiteConstraintException) { "This code already exists." }
        }
    }
    del?.let { e ->
        ConfirmDialog("Delete ${e.code}?", {
            scope.launch { if (db.ref().examTypeUse(e.id) > 0) toast(ctx, "Remove its sessions, groups and subjects first.") else db.ref().deleteExamType(e) }
            del = null
        }, { del = null })
    }
}

// ───────────────────────────── Grading schemes & bands ─────────────────────────────

@Composable
fun SchemesScreen(onOpen: (GradingScheme) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.ref().schemesFlow().collectAsState(emptyList())
    var edit by remember { mutableStateOf<GradingScheme?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<GradingScheme?>(null) }

    CrudList(list, "No schemes.", { adding = true }) { s ->
        ItemCard(s.name, "GPA cap ${s.gpaCap} · 4th subject: points above ${s.optionalDeduct} added · fail any compulsory: ${s.failOnAnyCompulsory}",
            onClick = { onOpen(s) }, actions = { EditDeleteActions({ edit = s }, { del = s }) })
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add scheme (default bands are copied)" else "Edit scheme",
            listOf(Fld("name", "Name"), Fld("cap", "GPA cap", FKind.DECIMAL), Fld("ded", "4th-subject deduction", FKind.DECIMAL),
                Fld("fail", "Fail if any compulsory subject fails", FKind.SWITCH)),
            mapOf("name" to (cur?.name ?: ""), "cap" to (cur?.gpaCap ?: 5.0).toString(), "ded" to (cur?.optionalDeduct ?: 2.0).toString(),
                "fail" to (cur?.failOnAnyCompulsory ?: true).toString()),
            { adding = false; edit = null }
        ) { v ->
            val name = v["name"].orEmpty().trim(); val cap = dec(v["cap"]); val ded = dec(v["ded"])
            if (name.isEmpty() || cap == null || ded == null || cap <= 0) return@FormDialog "Fill all fields with valid numbers."
            val ns = GradingScheme(id = cur?.id ?: 0, name = name, gpaCap = cap, optionalDeduct = ded, failOnAnyCompulsory = v["fail"] == "true")
            if (cur == null) {
                val id = db.ref().addScheme(ns)
                Seed.defaultBands.forEach { (m, l, p) -> db.ref().addBand(GradeBand(schemeId = id, minPercent = m, letter = l, point = p)) }
            } else { db.ref().saveScheme(ns); ResultService.recalcAll() }
            null
        }
    }
    del?.let { s ->
        ConfirmDialog("Delete ${s.name}?", {
            scope.launch {
                if (db.ref().schemeUse(s.id) > 0) toast(ctx, "Used by an exam type.")
                else { db.ref().deleteBandsOf(s.id); db.ref().deleteScheme(s) }
            }
            del = null
        }, { del = null })
    }
}

@Composable
fun BandsScreen(scheme: GradingScheme) {
    val scope = rememberCoroutineScope()
    val list by db.ref().bandsFlow(scheme.id).collectAsState(emptyList())
    var edit by remember { mutableStateOf<GradeBand?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<GradeBand?>(null) }

    Column {
        Text("A subject gets the band with the highest 'min %' not above its percentage. Keep a band with min 0 (the fail grade, point 0).",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        CrudList(list, "No bands.", { adding = true }) { b ->
            ItemCard("${b.letter} — GP ${b.point}", "from ${b.minPercent}%", actions = { EditDeleteActions({ edit = b }, { del = b }) })
        }
    }
    if (adding || edit != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add band" else "Edit band",
            listOf(Fld("min", "Min percent", FKind.DECIMAL), Fld("letter", "Letter grade"), Fld("pt", "Grade point", FKind.DECIMAL)),
            mapOf("min" to (cur?.minPercent?.toString() ?: ""), "letter" to (cur?.letter ?: ""), "pt" to (cur?.point?.toString() ?: "")),
            { adding = false; edit = null }
        ) { v ->
            val m = dec(v["min"]); val p = dec(v["pt"]); val l = v["letter"].orEmpty().trim()
            if (m == null || m < 0 || m > 100 || p == null || p < 0 || l.isEmpty()) return@FormDialog "Enter valid values (min 0–100)."
            val nb = GradeBand(id = cur?.id ?: 0, schemeId = scheme.id, minPercent = m, letter = l, point = p)
            if (cur == null) db.ref().addBand(nb) else db.ref().saveBand(nb)
            ResultService.recalcAll(); null
        }
    }
    del?.let { b -> ConfirmDialog("Delete band ${b.letter}?", { scope.launch { db.ref().deleteBand(b); ResultService.recalcAll() }; del = null }, { del = null }) }
}

// ───────────────────────────── Groups ─────────────────────────────

@Composable
fun GroupsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exams by db.ref().examTypesFlow().collectAsState(emptyList())
    val all by db.ref().groupsFlow().collectAsState(emptyList())
    var et by remember { mutableStateOf("") }
    val etId = et.toLongOrNull() ?: exams.firstOrNull()?.id
    var edit by remember { mutableStateOf<StudyGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<StudyGroup?>(null) }

    Column {
        Column(Modifier.padding(horizontal = 12.dp)) {
            DropdownField("Exam type", exams.map { it.id.toString() to it.code }, etId?.toString() ?: "") { et = it }
        }
        CrudList(all.filter { it.examTypeId == etId }, "No groups for this exam type.", if (etId != null) ({ adding = true }) else null) { g ->
            ItemCard(g.nameEn, g.nameBn, actions = { EditDeleteActions({ edit = g }, { del = g }) })
        }
    }
    if ((adding || edit != null) && etId != null) {
        val cur = edit
        FormDialog(if (cur == null) "Add group" else "Edit group",
            listOf(Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)")),
            mapOf("en" to (cur?.nameEn ?: ""), "bn" to (cur?.nameBn ?: "")), { adding = false; edit = null }
        ) { v ->
            val en = v["en"].orEmpty().trim()
            if (en.isEmpty()) return@FormDialog "Name required."
            if (cur == null) db.ref().addGroup(StudyGroup(examTypeId = etId, nameEn = en, nameBn = v["bn"].orEmpty().trim()))
            else db.ref().saveGroup(cur.copy(nameEn = en, nameBn = v["bn"].orEmpty().trim()))
            null
        }
    }
    del?.let { g ->
        ConfirmDialog("Delete ${g.nameEn}?", {
            scope.launch { if (db.ref().groupUse(g.id) > 0) toast(ctx, "Group has students or subject assignments.") else db.ref().deleteGroup(g) }
            del = null
        }, { del = null })
    }
}

// ───────────────────────────── Subjects ─────────────────────────────

@Composable
fun SubjectsScreen(onOpen: (Subject) -> Unit) {
    val exams by db.ref().examTypesFlow().collectAsState(emptyList())
    val all by db.ref().subjectsFlow().collectAsState(emptyList())
    var et by remember { mutableStateOf("") }
    val etId = et.toLongOrNull() ?: exams.firstOrNull()?.id
    var adding by remember { mutableStateOf(false) }

    Column {
        Column(Modifier.padding(horizontal = 12.dp)) {
            DropdownField("Exam type", exams.map { it.id.toString() to it.code }, etId?.toString() ?: "") { et = it }
        }
        CrudList(all.filter { it.examTypeId == etId }, "No subjects. Tap + to add.", if (etId != null) ({ adding = true }) else null) { s ->
            ItemCard("${s.code} — ${s.nameEn}", s.nameBn, onClick = { onOpen(s) })
        }
    }
    if (adding && etId != null) {
        FormDialog("Add subject", listOf(Fld("code", "Subject code"), Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)")),
            emptyMap(), { adding = false }
        ) { v ->
            val code = v["code"].orEmpty().trim(); val en = v["en"].orEmpty().trim()
            if (code.isEmpty() || en.isEmpty()) return@FormDialog "Code and English name required."
            try {
                val sub = Subject(examTypeId = etId, code = code, nameEn = en, nameBn = v["bn"].orEmpty().trim())
                val id = db.ref().addSubject(sub)
                onOpen(sub.copy(id = id)); null
            } catch (e: SQLiteConstraintException) { "This code already exists for the exam type." }
        }
    }
}

@Composable
fun SubjectDetailScreen(subject: Subject, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val comps by db.ref().componentsFlow(subject.id).collectAsState(emptyList())
    val assigns by db.ref().assignmentsFlow(subject.id).collectAsState(emptyList())
    val groups by db.ref().groupsFlow().collectAsState(emptyList())
    var editC by remember { mutableStateOf<SubjectComponent?>(null) }
    var addC by remember { mutableStateOf(false) }
    var delC by remember { mutableStateOf<SubjectComponent?>(null) }
    var editA by remember { mutableStateOf<GroupSubject?>(null) }
    var addA by remember { mutableStateOf(false) }
    var delA by remember { mutableStateOf<GroupSubject?>(null) }
    var editS by remember { mutableStateOf(false) }
    var delS by remember { mutableStateOf(false) }
    val myGroups = groups.filter { it.examTypeId == subject.examTypeId }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text("${subject.code} — ${subject.nameEn}", style = MaterialTheme.typography.titleMedium)
        Row {
            OutlinedButton(onClick = { editS = true }) { Text("Edit") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { delS = true }) { Text("Delete subject") }
        }
        Text("Mark components (total ${comps.sumOf { it.maxMarks }})", fontWeight = FontWeight.Bold)
        comps.forEach { c ->
            ItemCard(c.name, "max ${c.maxMarks} · pass ${c.passMarks}", actions = { EditDeleteActions({ editC = c }, { delC = c }) })
        }
        Button(onClick = { addC = true }) { Text("+ Component") }

        Text("Used in", fontWeight = FontWeight.Bold)
        assigns.forEach { a ->
            val g = if (a.groupId == null) "Common (all groups)" else myGroups.firstOrNull { it.id == a.groupId }?.nameEn ?: "?"
            ItemCard(g, "${a.kind}${if (a.choiceKey.isNotBlank()) " · choice: ${a.choiceKey}" else ""}", actions = { EditDeleteActions({ editA = a }, { delA = a }) })
        }
        Button(onClick = { addA = true }) { Text("+ Assign to group") }
    }

    if (editS) FormDialog("Edit subject", listOf(Fld("code", "Code"), Fld("en", "Name (English)"), Fld("bn", "Name (Bangla)")),
        mapOf("code" to subject.code, "en" to subject.nameEn, "bn" to subject.nameBn), { editS = false }
    ) { v ->
        if (v["code"].orEmpty().isBlank() || v["en"].orEmpty().isBlank()) return@FormDialog "Code and name required."
        try { db.ref().saveSubject(subject.copy(code = v["code"].orEmpty().trim(), nameEn = v["en"].orEmpty().trim(), nameBn = v["bn"].orEmpty().trim())); null }
        catch (e: SQLiteConstraintException) { "Code already used." }
    }
    if (delS) ConfirmDialog("Delete subject ${subject.code}?", {
        scope.launch {
            if (db.ref().subjectUse(subject.id) > 0) toast(ctx, "Students are using this subject.")
            else { db.ref().deleteComponentsOf(subject.id); db.ref().deleteAssignmentsOf(subject.id); db.ref().deleteSubjectRow(subject.id); onDeleted() }
        }
        delS = false
    }, { delS = false })

    if (addC || editC != null) {
        val cur = editC
        FormDialog(if (cur == null) "Add component" else "Edit component",
            listOf(Fld("name", "Name (Creative, MCQ, Practical, 1st paper…)"), Fld("max", "Full marks", FKind.NUMBER), Fld("pass", "Pass marks (0 = none)", FKind.NUMBER)),
            mapOf("name" to (cur?.name ?: ""), "max" to (cur?.maxMarks?.toString() ?: ""), "pass" to (cur?.passMarks?.toString() ?: "")),
            { addC = false; editC = null }
        ) { v ->
            val n = v["name"].orEmpty().trim(); val mx = num(v["max"]); val ps = num(v["pass"])
            if (n.isEmpty() || mx == null || mx <= 0 || ps == null || ps < 0 || ps > mx) return@FormDialog "Enter a name, full marks > 0 and pass marks between 0 and full marks."
            if (cur == null) db.ref().addComponent(SubjectComponent(subjectId = subject.id, name = n, maxMarks = mx, passMarks = ps, sortOrder = comps.size))
            else db.ref().saveComponent(cur.copy(name = n, maxMarks = mx, passMarks = ps))
            ResultService.recalcAll(); null
        }
    }
    delC?.let { c ->
        ConfirmDialog("Delete component ${c.name}?", {
            scope.launch { if (db.ref().componentUse(c.id) > 0) toast(ctx, "Marks already entered for it.") else { db.ref().deleteComponent(c); ResultService.recalcAll() } }
            delC = null
        }, { delC = null })
    }

    if (addA || editA != null) {
        val cur = editA
        FormDialog(if (cur == null) "Assign subject" else "Edit assignment",
            listOf(
                Fld("group", "Group", FKind.DROPDOWN, listOf("0" to "Common (all groups)") + myGroups.map { it.id.toString() to it.nameEn }),
                Fld("kind", "Kind", FKind.DROPDOWN, listOf("COMPULSORY" to "Compulsory", "SELECTIVE" to "Selective (pick one per choice key)", "FOURTH" to "Optional / 4th subject")),
                Fld("key", "Choice key (for Selective, e.g. RELIGION)")
            ),
            mapOf("group" to (cur?.groupId?.toString() ?: "0"), "kind" to (cur?.kind ?: "COMPULSORY"), "key" to (cur?.choiceKey ?: "")),
            { addA = false; editA = null }
        ) { v ->
            val g = v["group"]?.toLongOrNull()?.takeIf { it > 0 }
            val kind = v["kind"] ?: "COMPULSORY"; val key = v["key"].orEmpty().trim().uppercase()
            if (kind == "SELECTIVE" && key.isEmpty()) return@FormDialog "Selective subjects need a choice key."
            val na = GroupSubject(id = cur?.id ?: 0, examTypeId = subject.examTypeId, groupId = g, subjectId = subject.id,
                kind = kind, choiceKey = if (kind == "SELECTIVE") key else "")
            if (cur == null) db.ref().addAssignment(na) else db.ref().saveAssignment(na)
            null
        }
    }
    delA?.let { a -> ConfirmDialog("Remove this assignment?", { scope.launch { db.ref().deleteAssignment(a) }; delA = null }, { delA = null }) }
}

// ───────────────────────────── SMS ─────────────────────────────

@Composable
fun SmsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by db.sms().logsFlow().collectAsState(emptyList())
    val pending by db.sms().queueCountFlow("PENDING").collectAsState(0)
    val dead by db.sms().queueCountFlow("DEAD").collectAsState(0)
    val done by db.sms().queueCountFlow("DONE").collectAsState(0)
    var enabled by remember { mutableStateOf(prefs.smsEnabled) }
    var showLimits by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }
    var testOut by remember { mutableStateOf<String?>(null) }
    var simOpen by remember { mutableStateOf(prefs.simSlot.toString()) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SMS result service", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.smsEnabled = it })
                    }
                    Text("Format: EXAM BOARD ROLL YEAR  (e.g. SSC DHA 123456 2023)", style = MaterialTheme.typography.bodySmall)
                    DropdownField("Reply SIM", listOf("0" to "System default", "1" to "SIM 1", "2" to "SIM 2"), simOpen) { simOpen = it; prefs.simSlot = it.toInt() }
                    Text("Queue — pending $pending · sent $done · failed $dead", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { showLimits = true }) { Text("Limits") }
                        TextButton(onClick = { showTest = true }) { Text("Test SMS") }
                        TextButton(onClick = { scope.launch { db.sms().reviveDead(System.currentTimeMillis()); SmsQueue.process(ctx, 20) } }) { Text("Retry failed") }
                    }
                    Row {
                        TextButton(onClick = { scope.launch { SmsQueue.process(ctx, 20) } }) { Text("Send queue now") }
                        TextButton(onClick = { scope.launch { db.sms().clearLogs() } }) { Text("Clear log") }
                    }
                }
            }
        }
        items(logs) { l ->
            ItemCard("${l.status} · ${l.phone}", "${l.request}\n→ ${l.response.take(120)}")
        }
    }

    if (showLimits) FormDialog("Rate limits & retry",
        listOf(
            Fld("min", "Max queries / number / minute", FKind.NUMBER), Fld("hour", "… per hour", FKind.NUMBER), Fld("day", "… per day", FKind.NUMBER),
            Fld("glob", "Global per second", FKind.NUMBER), Fld("retries", "Max send retries", FKind.NUMBER),
            Fld("delay", "Retry base delay (seconds)", FKind.NUMBER), Fld("maxq", "Max queue size", FKind.NUMBER),
            Fld("gap", "Delay between sent SMS (ms)", FKind.NUMBER)
        ),
        mapOf("min" to prefs.perMinute.toString(), "hour" to prefs.perHour.toString(), "day" to prefs.perDay.toString(),
            "glob" to prefs.globalPerSecond.toString(), "retries" to prefs.maxRetries.toString(),
            "delay" to prefs.retryDelaySec.toString(), "maxq" to prefs.maxQueue.toString(),
            "gap" to prefs.sendDelayMs.toString()),
        { showLimits = false }
    ) { v ->
        val a = num(v["min"]); val b = num(v["hour"]); val c = num(v["day"]); val d = num(v["glob"])
        val e = num(v["retries"]); val f = num(v["delay"]); val g = num(v["maxq"]); val h = num(v["gap"])
        if (listOf(a, b, c, d, e, f, g, h).any { it == null || it < 1 }) return@FormDialog "All values must be numbers ≥ 1."
        prefs.perMinute = a!!; prefs.perHour = b!!; prefs.perDay = c!!; prefs.globalPerSecond = d!!
        prefs.maxRetries = e!!; prefs.retryDelaySec = f!!; prefs.maxQueue = g!!; prefs.sendDelayMs = h!!
        null
    }

    if (showTest) FormDialog("Test an SMS (nothing is sent)", listOf(Fld("sms", "SMS text")), mapOf("sms" to "SSC DHA 123456 2023"), { showTest = false }) { v ->
        val c = SmsResponder.compose(v["sms"].orEmpty())
        testOut = if (c == null) "(not a result query – would be ignored)" else "[${c.status}]\n${c.text}"
        null
    }
    testOut?.let { ConfirmDialog(it, { testOut = null }, { testOut = null }) }
}

// ───────────────────────────── Settings ─────────────────────────────

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(prefs.autoStart) }
    var showPort by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var autoB by remember { mutableStateOf(prefs.autoBackup) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val io = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) io.launch(kotlinx.coroutines.Dispatchers.IO) {
            val msg = try { Backup.exportTo(ctx, uri); "Backup saved." } catch (e: Exception) { "Backup failed: ${e.message}" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { toast(ctx, msg) }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmRestore = uri }

    confirmRestore?.let { uri ->
        ConfirmDialog("Replace ALL current data with this backup? A safety copy of the current data is saved first, then the app closes – reopen it afterwards.", {
            confirmRestore = null
            io.launch(kotlinx.coroutines.Dispatchers.IO) {
                ServerService.stop(ctx)
                val err = Backup.restoreFrom(ctx, uri)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    toast(ctx, err ?: "Restored. Closing – please reopen the app.")
                }
                if (err == null) { kotlinx.coroutines.delay(1500); android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }, { confirmRestore = null })
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ItemCard("Web port", "${prefs.port}  (restart the server to apply)", onClick = { showPort = true })
        ItemCard("Portal title", prefs.portalTitle, onClick = { showTitle = true })
        ItemCard("Change admin PIN", "Protects this admin app", onClick = { showPin = true })
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start server on boot", fontWeight = FontWeight.SemiBold)
                    Text("Starts the web portal after the phone restarts", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; prefs.autoStart = it })
            }
        }
        OutlinedButton(onClick = { scopeRecalc(ctx) }) { Text("Recalculate all results") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Backup & restore", fontWeight = FontWeight.Bold)
                Text("Includes students, marks, setup, teachers. The file holds personal data and password hashes – store it safely.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily automatic backup (while server runs) · ${Backup.count(ctx)} file(s) in app folder", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = autoB, onCheckedChange = { autoB = it; prefs.autoBackup = it })
                }
                Row {
                    TextButton(onClick = { exportLauncher.launch("board-result-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".db") }) { Text("Backup to file") }
                    TextButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore") }
                }
            }
        }
    }
    if (showPort) FormDialog("Web port", listOf(Fld("p", "Port (1024–65535)", FKind.NUMBER)), mapOf("p" to prefs.port.toString()), { showPort = false }) { v ->
        val p = num(v["p"]); if (p == null || p < 1024 || p > 65535) "Invalid port." else { prefs.port = p; null }
    }
    if (showTitle) FormDialog("Portal title", listOf(Fld("t", "Title")), mapOf("t" to prefs.portalTitle), { showTitle = false }) { v ->
        val t = v["t"].orEmpty().trim(); if (t.isEmpty()) "Required." else { prefs.portalTitle = t; null }
    }
    if (showPin) FormDialog("Change admin PIN", listOf(Fld("old", "Current PIN", FKind.PASSWORD), Fld("new", "New PIN (4+ digits)", FKind.PASSWORD)), emptyMap(), { showPin = false }) { v ->
        val n = v["new"].orEmpty()
        when {
            !Security.verify(v["old"].orEmpty(), prefs.adminPinHash) -> "Current PIN is wrong."
            n.length < 4 -> "New PIN must be at least 4 characters."
            else -> { prefs.adminPinHash = Security.hash(n); null }
        }
    }
}

private fun scopeRecalc(ctx: android.content.Context) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        ResultService.recalcAll()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { toast(ctx, "Done") }
    }
}
