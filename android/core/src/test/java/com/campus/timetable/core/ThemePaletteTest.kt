package com.campus.timetable.core

import com.campus.timetable.core.theme.AppThemeMode
import com.campus.timetable.core.theme.CourseColorPalette
import com.campus.timetable.core.theme.ThemeSeedColor
import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun `all theme modes and seed colors have distinct light and dark tokens`() {
        assertEquals(3, AppThemeMode.entries.size)
        assertTrue(ThemeSeedColor.entries.size >= 6)
        for (seed in ThemeSeedColor.entries) {
            assertEquals(seed, ThemeSeedColor.fromKey(seed.key))
            assertNotEquals(seed.primaryLight, seed.primaryDark)
        }
        assertEquals(ThemeSeedColor.OCEAN_BLUE, ThemeSeedColor.fromKey("unknown_key"))
        assertTrue(CourseColorPalette.hexColors.size >= 10)
        assertTrue(CourseColorPalette.pickForCourseName("高等数学").startsWith("#"))
    }
}
