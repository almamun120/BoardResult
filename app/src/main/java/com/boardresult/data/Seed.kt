package com.boardresult.data

/**
 * First-run sample data so the system is usable immediately.
 * Everything here is editable from the admin app. Subject codes/marks distribution are SAMPLES:
 * please verify them against the official board/NCTB subject-code list for your exam year.
 */
object Seed {
    /** min %, letter, grade point – Bangladesh general education board scale */
    val defaultBands = listOf(
        Triple(80.0, "A+", 5.0), Triple(70.0, "A", 4.0), Triple(60.0, "A-", 3.5),
        Triple(50.0, "B", 3.0), Triple(40.0, "C", 2.0), Triple(33.0, "D", 1.0), Triple(0.0, "F", 0.0)
    )

    suspend fun run(db: AppDatabase) {
        val r = db.ref()
        if (r.examTypes().isNotEmpty()) return

        val schemeId = r.addScheme(GradingScheme(name = "Bangladesh GPA-5 (SSC/HSC)"))
        defaultBands.forEach { (min, letter, point) -> r.addBand(GradeBand(schemeId = schemeId, minPercent = min, letter = letter, point = point)) }

        listOf(
            Triple("DHA", "Dhaka", "ঢাকা"), Triple("RAJ", "Rajshahi", "রাজশাহী"), Triple("COM", "Cumilla", "কুমিল্লা"),
            Triple("JES", "Jashore", "যশোর"), Triple("CHI", "Chattogram", "চট্টগ্রাম"), Triple("BAR", "Barishal", "বরিশাল"),
            Triple("SYL", "Sylhet", "সিলেট"), Triple("DIN", "Dinajpur", "দিনাজপুর"), Triple("MYM", "Mymensingh", "ময়মনসিংহ"),
            Triple("MAD", "Madrasah", "মাদ্রাসা"), Triple("TEC", "Technical", "কারিগরি")
        ).forEach { (c, en, bn) -> r.addBoard(Board(code = c, nameEn = en, nameBn = bn)) }

        val ssc = r.addExamType(ExamType(code = "SSC", nameEn = "Secondary School Certificate", nameBn = "মাধ্যমিক স্কুল সার্টিফিকেট", schemeId = schemeId))
        val hsc = r.addExamType(ExamType(code = "HSC", nameEn = "Higher Secondary Certificate", nameBn = "উচ্চ মাধ্যমিক সার্টিফিকেট", schemeId = schemeId))

        for (et in listOf(ssc, hsc)) {
            val sci = r.addGroup(StudyGroup(examTypeId = et, nameEn = "Science", nameBn = "বিজ্ঞান"))
            r.addGroup(StudyGroup(examTypeId = et, nameEn = "Humanities", nameBn = "মানবিক"))
            r.addGroup(StudyGroup(examTypeId = et, nameEn = "Business Studies", nameBn = "ব্যবসায় শিক্ষা"))

            suspend fun sub(
                code: String, en: String, bn: String, comps: List<Triple<String, Int, Int>>,
                group: Long?, kind: String = "COMPULSORY", key: String = ""
            ) {
                val sid = r.addSubject(Subject(examTypeId = et, code = code, nameEn = en, nameBn = bn))
                comps.forEachIndexed { i, (n, max, pass) ->
                    r.addComponent(SubjectComponent(subjectId = sid, name = n, maxMarks = max, passMarks = pass, sortOrder = i))
                }
                r.addAssignment(GroupSubject(examTypeId = et, groupId = group, subjectId = sid, kind = kind, choiceKey = key))
            }

            if (et == ssc) {
                sub("101", "Bangla", "বাংলা", listOf(Triple("Creative", 70, 23), Triple("MCQ", 30, 10)), null)
                sub("107", "English", "ইংরেজি", listOf(Triple("Written", 100, 33)), null)
                sub("109", "General Mathematics", "সাধারণ গণিত", listOf(Triple("Creative", 60, 20), Triple("MCQ", 40, 13)), null)
                sub("150", "Bangladesh & Global Studies", "বাংলাদেশ ও বিশ্বপরিচয়", listOf(Triple("Creative", 70, 23), Triple("MCQ", 30, 10)), null)
                sub("154", "ICT", "তথ্য ও যোগাযোগ প্রযুক্তি", listOf(Triple("Creative", 30, 10), Triple("MCQ", 20, 7), Triple("Practical", 25, 8)), null)
                val relComps = listOf(Triple("Creative", 70, 23), Triple("MCQ", 30, 10))
                sub("111", "Islam & Moral Education", "ইসলাম ও নৈতিক শিক্ষা", relComps, null, "SELECTIVE", "RELIGION")
                sub("112", "Hindu Religion & Moral Education", "হিন্দুধর্ম ও নৈতিক শিক্ষা", relComps, null, "SELECTIVE", "RELIGION")
                sub("113", "Buddhist Religion & Moral Education", "বৌদ্ধধর্ম ও নৈতিক শিক্ষা", relComps, null, "SELECTIVE", "RELIGION")
                sub("114", "Christian Religion & Moral Education", "খ্রিষ্টধর্ম ও নৈতিক শিক্ষা", relComps, null, "SELECTIVE", "RELIGION")
                val sciComps = listOf(Triple("Creative", 50, 17), Triple("MCQ", 25, 8), Triple("Practical", 25, 8))
                sub("136", "Physics", "পদার্থবিজ্ঞান", sciComps, sci)
                sub("137", "Chemistry", "রসায়ন", sciComps, sci)
                sub("138", "Biology", "জীববিজ্ঞান", sciComps, sci)
                sub("126", "Higher Mathematics", "উচ্চতর গণিত", sciComps, sci, "FOURTH")
            } else {
                sub("101", "Bangla", "বাংলা", listOf(Triple("Creative", 140, 46), Triple("MCQ", 60, 20)), null)
                sub("107", "English", "ইংরেজি", listOf(Triple("Written", 200, 66)), null)
                sub("275", "ICT", "তথ্য ও যোগাযোগ প্রযুক্তি", listOf(Triple("Creative", 50, 17), Triple("MCQ", 25, 8), Triple("Practical", 25, 8)), null)
                val sciComps = listOf(Triple("Creative", 100, 33), Triple("MCQ", 50, 17), Triple("Practical", 50, 17))
                sub("174", "Physics", "পদার্থবিজ্ঞান", sciComps, sci)
                sub("176", "Chemistry", "রসায়ন", sciComps, sci)
                sub("178", "Biology", "জীববিজ্ঞান", sciComps, sci)
                sub("265", "Higher Mathematics", "উচ্চতর গণিত", sciComps, sci, "FOURTH")
            }
        }
    }
}
