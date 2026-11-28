package com.campus.timetable.feature.importing

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 受控教务浏览器是否占满全屏。
 * 浏览器打开时隐藏底部主导航：既避免「导入」高亮让用户误以为仍在导入页，
 * 也给横屏模式留出足够高度显示教务网页。
 */
object ImportChromeState {
    val browserOpen = MutableStateFlow(false)
}
