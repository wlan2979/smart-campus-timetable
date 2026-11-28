package com.campus.timetable.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 校验内置「学校教务处直达地址」资源：数量、协议与知名高校真实教务处地址示例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SchoolAcademicUrlAssetTest {

    private val urls: Map<String, String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.assets.open("school_academic_urls.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.decodeFromString<Map<String, String>>(raw)
    }

    @Test
    fun academicUrlsCoverHundredsOfSchoolsWithValidHttpAddresses() {
        assertTrue("教务处直达地址应不少于 800 所，实际 ${urls.size}", urls.size >= 800)
        urls.forEach { (code, url) ->
            assertTrue("$code 的地址不是 HTTP(S)：$url", url.startsWith("https://") || url.startsWith("http://"))
            assertTrue("$code 的地址缺少主机名：$url", url.length > 10)
        }
    }

    @Test
    fun sampleUniversityPointsToRealAcademicOffice() {
        assertEquals("https://dean.pku.edu.cn", urls["4111010001"])
        assertEquals("https://www.tsinghua.edu.cn/jwc/", urls["4111010003"])
    }
}
