package com.campus.timetable.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.campus.timetable.data.local.dao.*
import com.campus.timetable.data.local.entity.*

@Database(
    entities = [
        UserEntity::class,
        SemesterEntity::class,
        ClassPeriodEntity::class,
        BellScheduleTemplateEntity::class,
        CourseEntity::class,
        CourseScheduleEntity::class,
        CourseExceptionEntity::class,
        HolidayEntity::class,
        SchoolCalendarOverrideEntity::class,
        AlarmSettingEntity::class,
        WeatherSettingEntity::class,
        SchoolEntity::class,
        SchoolAdaptationRequestEntity::class,
        ImportHistoryEntity::class,
        SyncRecordEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun semesterDao(): SemesterDao
    abstract fun classPeriodDao(): ClassPeriodDao
    abstract fun bellScheduleTemplateDao(): BellScheduleTemplateDao
    abstract fun courseDao(): CourseDao
    abstract fun holidayDao(): HolidayDao
    abstract fun alarmSettingDao(): AlarmSettingDao
    abstract fun weatherSettingDao(): WeatherSettingDao
    abstract fun schoolDao(): SchoolDao
    abstract fun schoolAdaptationRequestDao(): SchoolAdaptationRequestDao
    abstract fun importHistoryDao(): ImportHistoryDao
    abstract fun syncRecordDao(): SyncRecordDao

    companion object {
        /**
         * 正式无损迁移 v1 -> v2（保障 v1.0.0 升级至 v2.0.0 桃课时保留全部学期、课程、临时调课与闹钟/天气设置）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 新增作息时间模板表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bell_schedule_templates` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `ruleJson` TEXT NOT NULL,
                        `isPreset` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // 2. 新增学校适配申请表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `school_adaptation_requests` (
                        `id` TEXT NOT NULL,
                        `schoolName` TEXT NOT NULL,
                        `eduSystemType` TEXT NOT NULL,
                        `portalUrl` TEXT NOT NULL,
                        `pageSummary` TEXT NOT NULL,
                        `userAuthorized` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // 3. 升级 holidays 支持连续假期区间（endDate / totalDays / reminderDate）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `holidays_v2` (
                        `id` TEXT NOT NULL,
                        `year` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `endDate` TEXT NOT NULL,
                        `totalDays` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `holidayType` TEXT NOT NULL,
                        `isOffDay` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `version` TEXT NOT NULL,
                        `reminderDate` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `holidays_v2` (`id`, `year`, `date`, `endDate`, `totalDays`, `name`, `holidayType`, `isOffDay`, `source`, `version`, `reminderDate`, `updatedAt`)
                    SELECT `id`, `year`, `date`, `date`, 1, `name`, `holidayType`, `isOffDay`, `source`, `version`, NULL, `updatedAt` FROM `holidays`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `holidays`")
                db.execSQL("ALTER TABLE `holidays_v2` RENAME TO `holidays`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_holidays_year` ON `holidays` (`year`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_holidays_date` ON `holidays` (`date`)")

                // 4. 升级 alarm_settings，彻底移除失效的 TTS 列并保留原有闹钟设置
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `alarm_settings_v2` (
                        `id` TEXT NOT NULL,
                        `scopeType` TEXT NOT NULL,
                        `courseId` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `advanceMinutes` INTEGER NOT NULL,
                        `enableClassEndReminder` INTEGER NOT NULL,
                        `ringtoneUri` TEXT,
                        `vibrate` INTEGER NOT NULL,
                        `muteDate` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `alarm_settings_v2` (`id`, `scopeType`, `courseId`, `enabled`, `mode`, `advanceMinutes`, `enableClassEndReminder`, `ringtoneUri`, `vibrate`, `muteDate`, `updatedAt`)
                    SELECT `id`, `scopeType`, `courseId`, `enabled`, `mode`, `advanceMinutes`, `enableClassEndReminder`, `ringtoneUri`, `vibrate`, `muteDate`, `updatedAt` FROM `alarm_settings`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `alarm_settings`")
                db.execSQL("ALTER TABLE `alarm_settings_v2` RENAME TO `alarm_settings`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_settings_scopeType` ON `alarm_settings` (`scopeType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_settings_courseId` ON `alarm_settings` (`courseId`)")

                // 5. 升级 schools 为完整高校信息库结构（后续启动自动刷新加载 2741 所教育部目录）
                db.execSQL("DROP TABLE IF EXISTS `schools`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schools` (
                        `id` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `shortName` TEXT NOT NULL,
                        `aliases` TEXT NOT NULL,
                        `pinyin` TEXT NOT NULL,
                        `pinyinInitials` TEXT NOT NULL,
                        `province` TEXT NOT NULL,
                        `city` TEXT NOT NULL,
                        `schoolType` TEXT NOT NULL,
                        `schoolLevel` TEXT NOT NULL,
                        `campuses` TEXT NOT NULL,
                        `systemType` TEXT NOT NULL,
                        `systemVersion` TEXT NOT NULL,
                        `adapterId` TEXT,
                        `portalUrl` TEXT NOT NULL,
                        `supportStatus` TEXT NOT NULL,
                        `verified` INTEGER NOT NULL,
                        `lastVerifiedAt` INTEGER,
                        `statusNote` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_schools_code` ON `schools` (`code`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_name` ON `schools` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_province` ON `schools` (`province`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_schoolLevel` ON `schools` (`schoolLevel`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_pinyin` ON `schools` (`pinyin`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_pinyinInitials` ON `schools` (`pinyinInitials`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schools_supportStatus` ON `schools` (`supportStatus`)")
            }
        }
    }
}
