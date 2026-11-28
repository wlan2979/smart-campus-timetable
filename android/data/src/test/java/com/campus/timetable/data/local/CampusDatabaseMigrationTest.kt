package com.campus.timetable.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证已安装 v1.0.0 的用户升级到 v2.0.0（桃课）时，Room MIGRATION_1_2 不会丢失课表数据。
 *
 * 依赖 data/schemas 下的 1.json / 2.json（由 KSP 导出），并校验迁移后的表结构与 v2 Schema 完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CampusDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CampusDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesTimetableDataAndUpgradesV2Tables() {
        val dbName = "migration-1-to-2.db"

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO `users` (`id`,`phone`,`nickname`,`isGuest`,`syncVersion`,`createdAt`,`updatedAt`) " +
                    "VALUES ('u1','13800138000','小明',0,3,1000,2000)"
            )
            execSQL(
                "INSERT INTO `semesters` (`id`,`userId`,`name`,`startDate`,`totalWeeks`,`periodsPerDay`,`isCurrent`," +
                    "`syncVersion`,`createdAt`,`updatedAt`,`isDeleted`) " +
                    "VALUES ('s1','u1','2026 秋季学期','2026-09-01',18,12,1,1,1000,2000,0)"
            )
            execSQL(
                "INSERT INTO `courses` (`id`,`semesterId`,`name`,`teacher`,`location`,`courseType`,`colorHex`," +
                    "`credit`,`note`,`isAutoDetectedPe`,`syncVersion`,`createdAt`,`updatedAt`,`isDeleted`) " +
                    "VALUES ('c1','s1','高等数学','张老师','A-101','NORMAL','#3F51B5',4.0,'',0,1,1000,2000,0)"
            )
            execSQL(
                "INSERT INTO `holidays` (`id`,`year`,`date`,`name`,`holidayType`,`isOffDay`,`source`,`version`," +
                    "`updatedAt`) VALUES ('h1',2026,'2026-10-01','国庆节','LEGAL_HOLIDAY',1,'国务院办公厅','2026-cnt31',1000)"
            )
            execSQL(
                "INSERT INTO `alarm_settings` (`id`,`scopeType`,`courseId`,`enabled`,`mode`,`advanceMinutes`," +
                    "`enableClassEndReminder`,`enableTts`,`ttsTemplate`,`ringtoneUri`,`vibrate`,`muteDate`,`updatedAt`) " +
                    "VALUES ('a1','GLOBAL',NULL,1,'ALARM_CLOCK',15,1,1,'{courseName}即将上课',NULL,1,'2026-10-01',2000)"
            )
            execSQL(
                "INSERT INTO `schools` (`id`,`code`,`name`,`province`,`city`,`schoolLevel`,`systemType`," +
                    "`adapterId`,`portalUrl`,`verified`,`statusNote`,`updatedAt`) " +
                    "VALUES ('sc1','10001','旧学校','北京市','北京市','UNDERGRADUATE','ZHENGFANG',NULL,'https://x.edu.cn',0,'',1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, CampusDatabase.MIGRATION_1_2)

        // 1. 未改动的核心课表数据必须原样保留
        db.query("SELECT name, location FROM courses WHERE id = 'c1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("高等数学", it.getString(0))
            assertEquals("A-101", it.getString(1))
        }
        db.query("SELECT name FROM semesters WHERE id = 's1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("2026 秋季学期", it.getString(0))
        }

        // 2. 旧节假日的逐日记录升级为区间记录：endDate=原日期、totalDays=1、无节前提醒日期
        db.query("SELECT endDate, totalDays, reminderDate, name FROM holidays WHERE id = 'h1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("2026-10-01", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertTrue(it.isNull(2))
            assertEquals("国庆节", it.getString(3))
        }

        // 3. 闹钟设置保留，TTS 列被彻底移除
        db.query("SELECT advanceMinutes, mode FROM alarm_settings WHERE id = 'a1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(15, it.getInt(0))
            assertEquals("ALARM_CLOCK", it.getString(1))
        }
        val alarmColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(`alarm_settings`)").use {
            while (it.moveToNext()) alarmColumns.add(it.getString(1))
        }
        assertFalse(alarmColumns.contains("enableTts"))
        assertFalse(alarmColumns.contains("ttsTemplate"))

        // 4. 新增表已创建且为空
        for (table in listOf("bell_schedule_templates", "school_adaptation_requests")) {
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM `$table`").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }

        // 5. 学校表升级为 v2 结构，旧样本行清空，启动时重新装载 2741 所教育部目录
        val schoolColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(`schools`)").use {
            while (it.moveToNext()) schoolColumns.add(it.getString(1))
        }
        for (column in listOf("shortName", "pinyin", "pinyinInitials", "schoolType", "campuses", "systemVersion", "supportStatus", "lastVerifiedAt")) {
            assertTrue("schools 缺少 v2 字段 $column", schoolColumns.contains(column))
        }
        db.query("SELECT COUNT(*) FROM schools").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }

        // 6. 空值的可空字段保持可空（ringtoneUri / muteDate）
        db.query("SELECT ringtoneUri, muteDate FROM alarm_settings WHERE id = 'a1'").use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0))
            assertEquals("2026-10-01", it.getString(1))
        }

        db.close()
    }
}
