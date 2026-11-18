package com.boardresult.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Board::class, GradingScheme::class, GradeBand::class, ExamType::class, StudyGroup::class,
        Subject::class, SubjectComponent::class, GroupSubject::class, Institution::class, Teacher::class,
        ExamSession::class, Student::class, StudentSubject::class, Mark::class,
        SmsLog::class, SmsQueueItem::class, RateEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ref(): RefDao
    abstract fun sessions(): SessionDao
    abstract fun students(): StudentDao
    abstract fun sms(): SmsDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "board_result.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
