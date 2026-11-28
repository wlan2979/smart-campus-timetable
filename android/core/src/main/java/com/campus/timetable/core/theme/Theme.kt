package com.campus.timetable.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

enum class ThemeSeedColor(
    val key: String,
    val label: String,
    val primaryLight: Color,
    val primaryContainerLight: Color,
    val primaryDark: Color,
    val primaryContainerDark: Color
) {
    OCEAN_BLUE(
        key = "ocean_blue",
        label = "海湾蓝",
        primaryLight = Color(0xFF1A73E8),
        primaryContainerLight = Color(0xFFD2E3FC),
        primaryDark = Color(0xFF8AB4F8),
        primaryContainerDark = Color(0xFF174EA6)
    ),
    EMERALD(
        key = "emerald",
        label = "青松绿",
        primaryLight = Color(0xFF137333),
        primaryContainerLight = Color(0xFFCEEAD6),
        primaryDark = Color(0xFF81C995),
        primaryContainerDark = Color(0xFF0D652D)
    ),
    INDIGO(
        key = "indigo",
        label = "靛青紫",
        primaryLight = Color(0xFF3F51B5),
        primaryContainerLight = Color(0xFFDBE1FF),
        primaryDark = Color(0xFFB3C5FF),
        primaryContainerDark = Color(0xFF283593)
    ),
    CORAL(
        key = "coral",
        label = "暖珊瑚",
        primaryLight = Color(0xFFC5221F),
        primaryContainerLight = Color(0xFFFAD2CF),
        primaryDark = Color(0xFFF28B82),
        primaryContainerDark = Color(0xFF8C1D18)
    ),
    AMBER(
        key = "amber",
        label = "落日琥珀",
        primaryLight = Color(0xFFB06000),
        primaryContainerLight = Color(0xFFFEEFC3),
        primaryDark = Color(0xFFFDD663),
        primaryContainerDark = Color(0xFF7A4100)
    ),
    TEAL(
        key = "teal",
        label = "湖水青",
        primaryLight = Color(0xFF007B83),
        primaryContainerLight = Color(0xFFCBF0F8),
        primaryDark = Color(0xFF78D9EC),
        primaryContainerDark = Color(0xFF005B63)
    );

    companion object {
        fun fromKey(key: String?): ThemeSeedColor =
            entries.firstOrNull { it.key == key } ?: OCEAN_BLUE
    }
}

/**
 * 现代低饱和度课程卡片色板（参考 Google 日历层级感，确保白字与深色主题都具备高可读性）。
 */
object CourseColorPalette {
    val hexColors: List<String> = listOf(
        "#1A73E8", // 蓝
        "#0B8043", // 绿
        "#8E24AA", // 紫
        "#E67C73", // 粉红
        "#F4511E", // 橘红
        "#039BE5", // 天蓝
        "#3F51B5", // 靛蓝
        "#33B679", // 薄荷
        "#D81B60", // 玫红
        "#00897B", // 青绿
        "#F6BF26", // 琥珀
        "#616161"  // 岩石灰
    )

    fun parseColor(hex: String, fallback: Color = Color(0xFF1A73E8)): Color {
        return runCatching {
            Color(android.graphics.Color.parseColor(hex))
        }.getOrDefault(fallback)
    }

    fun pickForCourseName(name: String): String {
        val index = (name.hashCode() and 0x7FFFFFFF) % hexColors.size
        return hexColors[index]
    }
}

private val CampusTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
)

@Composable
fun CampusTimetableTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    seedColor: ThemeSeedColor = ThemeSeedColor.OCEAN_BLUE,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = seedColor.primaryDark,
            onPrimary = Color(0xFF062E6F),
            primaryContainer = seedColor.primaryContainerDark,
            onPrimaryContainer = Color(0xFFE8F0FE),
            secondary = Color(0xFF81C995),
            onSecondary = Color(0xFF00381C),
            secondaryContainer = Color(0xFF1E3A2A),
            onSecondaryContainer = Color(0xFFCEEAD6),
            background = Color(0xFF121316),
            onBackground = Color(0xFFE3E3E8),
            surface = Color(0xFF181A1F),
            onSurface = Color(0xFFE3E3E8),
            surfaceVariant = Color(0xFF23262E),
            onSurfaceVariant = Color(0xFFB5BAC5),
            outline = Color(0xFF3A3F4B),
            outlineVariant = Color(0xFF2B2F39)
        )
    } else {
        lightColorScheme(
            primary = seedColor.primaryLight,
            onPrimary = Color.White,
            primaryContainer = seedColor.primaryContainerLight,
            onPrimaryContainer = Color(0xFF041E49),
            secondary = Color(0xFF137333),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFCEEAD6),
            onSecondaryContainer = Color(0xFF072711),
            background = Color(0xFFF8F9FC),
            onBackground = Color(0xFF1F1F1F),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1F1F1F),
            surfaceVariant = Color(0xFFF1F3F9),
            onSurfaceVariant = Color(0xFF5F6368),
            outline = Color(0xFFDADCE0),
            outlineVariant = Color(0xFFE8EAED)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CampusTypography,
        content = content
    )
}
