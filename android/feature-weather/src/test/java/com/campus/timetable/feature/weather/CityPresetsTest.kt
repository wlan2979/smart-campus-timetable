package com.campus.timetable.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验离线城市速查表的覆盖与数据合法性（v2.2.0 起扩充为地级市 + 县级市/县城区）。
 */
class CityPresetsTest {

    private val presets = ChinaMajorCities.presets

    @Test
    fun presetsCoverNationwideCitiesIncludingCountyLevelOnes() {
        assertTrue("离线城市数量应不少于 2000 座，实际 ${presets.size}", presets.size >= 2000)
        for (name in listOf(
            "北京", "上海", "广州", "深圳", "杭州", "石家庄", "海口", "拉萨", "乌鲁木齐",
            "涿州", "迁安", "昆山", "义乌", "常熟", "仙桃", "济源"
        )) {
            assertTrue("缺少城市 $name", presets.any { it.name == name })
        }
    }

    @Test
    fun everyPresetHasValidChinaCoordinatesAndUniqueName() {
        val seen = mutableSetOf<String>()
        for (city in presets) {
            assertTrue("城市名重复：${city.name}", seen.add(city.name))
            assertTrue("${city.name} 纬度越界：${city.latitude}", city.latitude in 18.0..54.0)
            assertTrue("${city.name} 经度越界：${city.longitude}", city.longitude in 73.0..135.0)
            assertTrue("${city.name} 缺少省份", city.province.isNotBlank())
        }
        assertEquals(presets.size, seen.size)
    }
}
