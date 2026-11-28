package com.campus.timetable.schooladapters

import com.campus.timetable.domain.model.EduSystemType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.School
import com.campus.timetable.domain.model.SchoolSupportStatus

/**
 * 受控官方登录说明（绝不采集或明文保存教务系统账号密码，也不绕过验证码）。
 */
data class AdapterAuthSpec(
    val loginUrl: String,
    val timetableHintUrl: String = "",
    val instruction: String,
    val requiresOfficialWebViewLogin: Boolean = true
)

sealed interface AdapterParseResult {
    data class Success(
        val candidates: List<ParsedCourseCandidate>,
        val notice: String = ""
    ) : AdapterParseResult

    data class AuthExpired(val message: String) : AdapterParseResult
    data class Failure(val errorCode: String, val message: String) : AdapterParseResult
}

/**
 * 统一学校教务系统适配器 SPI。
 * 采用「学校目录 + 通用教务适配器 + 学校专属配置」架构，让同教务体系的多所学校共享适配器。
 */
interface SchoolAdapter {
    val adapterId: String
    val systemType: EduSystemType
    val displayName: String

    /** 仅包含经过真实学校实网账号与教务接口导入验证的学校代码，未经实测验证严禁列入 */
    val verifiedSchoolCodes: Set<String>

    fun isVerifiedFor(school: School): Boolean =
        school.verified && school.supportStatus == SchoolSupportStatus.VERIFIED && school.code in verifiedSchoolCodes

    fun buildAuthSpec(school: School): AdapterAuthSpec

    fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult
}

sealed interface AdapterLookupResult {
    data class Verified(val adapter: SchoolAdapter, val authSpec: AdapterAuthSpec) : AdapterLookupResult
    data class GenericOrAssisted(
        val adapter: SchoolAdapter?,
        val school: School,
        val authSpec: AdapterAuthSpec,
        val message: String
    ) : AdapterLookupResult
    data class UnverifiedFramework(
        val adapter: SchoolAdapter?,
        val school: School,
        val message: String
    ) : AdapterLookupResult
}
