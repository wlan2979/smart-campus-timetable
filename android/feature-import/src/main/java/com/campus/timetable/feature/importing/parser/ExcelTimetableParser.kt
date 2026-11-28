package com.campus.timetable.feature.importing.parser

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.model.CourseType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.WeekRuleType
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 真实 Excel 课表解析器：
 * - `.xls`：通过 Apache POI (HSSFWorkbook) 真实解析二进制 BIFF8 工作簿与合并单元格。
 * - `.xlsx`：通过标准 OOXML ZIP + XML DOM 完整解析 sharedStrings.xml、sheet*.xml 与 mergeCells，
 *   兼顾 Android 8.0+ 零崩溃兼容性与完整功能支持。
 * 同时支持「列表型课表」与「周一~周日网格型课表」两种高校导出格式。
 */
@Singleton
class ExcelTimetableParser @Inject constructor() {

    data class SheetGrid(
        val sheetName: String,
        val rows: List<List<String>>,
        val mergedRowSpans: Map<Pair<Int, Int>, Int> = emptyMap()
    )

    fun parse(inputStream: InputStream, fileName: String): Result<List<ParsedCourseCandidate>> = runCatching {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) throw IllegalArgumentException("所选 Excel 文件为空")

        val isZipXlsx = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val sheets = if (fileName.endsWith(".xlsx", ignoreCase = true) || isZipXlsx) {
            readXlsxSheets(bytes)
        } else {
            readXlsSheetsWithPoi(bytes)
        }

        val allCandidates = mutableListOf<ParsedCourseCandidate>()
        for (sheet in sheets) {
            val parsed = parseSheetGrid(sheet)
            allCandidates += parsed
        }
        allCandidates
    }

    private fun readXlsSheetsWithPoi(bytes: ByteArray): List<SheetGrid> {
        val formatter = DataFormatter()
        HSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val result = mutableListOf<SheetGrid>()
            for (sIdx in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(sIdx)
                val maxRow = sheet.lastRowNum
                if (maxRow < 0) continue
                val mergedSpans = mutableMapOf<Pair<Int, Int>, Int>()
                for (mIdx in 0 until sheet.numMergedRegions) {
                    val region = sheet.getMergedRegion(mIdx)
                    mergedSpans[region.firstRow to region.firstColumn] =
                        (region.lastRow - region.firstRow + 1).coerceAtLeast(1)
                }
                val maxCols = (0..maxRow).maxOfOrNull { r ->
                    sheet.getRow(r)?.lastCellNum?.toInt() ?: 0
                } ?: 0
                val rows = (0..maxRow).map { rIdx ->
                    val row = sheet.getRow(rIdx)
                    (0 until maxCols).map { cIdx ->
                        val cell = row?.getCell(cIdx)
                        cellToText(cell, formatter).trim()
                    }
                }
                result += SheetGrid(sheet.sheetName, rows, mergedSpans)
            }
            return result
        }
    }

    private fun cellToText(cell: Cell?, formatter: DataFormatter): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.orEmpty()
            CellType.NUMERIC -> formatter.formatCellValue(cell)
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> runCatching { cell.stringCellValue }.getOrElse { formatter.formatCellValue(cell) }
            else -> ""
        }
    }

    private fun readXlsxSheets(bytes: ByteArray): List<SheetGrid> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings) ?: emptyList()
        val sheetPaths = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()

        return sheetPaths.mapIndexed { idx, path ->
            parseWorksheetXml(entries[path]!!, sharedStrings, "Sheet${idx + 1}")
        }
    }

    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val doc = newSecureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        val siNodes = doc.getElementsByTagName("si")
        val list = ArrayList<String>(siNodes.length)
        for (i in 0 until siNodes.length) {
            val si = siNodes.item(i) as Element
            val tNodes = si.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until tNodes.length) {
                sb.append(tNodes.item(j).textContent.orEmpty())
            }
            list += sb.toString()
        }
        return list
    }

    private fun parseWorksheetXml(xmlBytes: ByteArray, sharedStrings: List<String>, name: String): SheetGrid {
        val doc = newSecureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))

        val mergedSpans = mutableMapOf<Pair<Int, Int>, Int>()
        val mergeNodes = doc.getElementsByTagName("mergeCell")
        for (i in 0 until mergeNodes.length) {
            val ref = (mergeNodes.item(i) as Element).getAttribute("ref")
            val parts = ref.split(":")
            if (parts.size == 2) {
                val (r1, c1) = parseCellRef(parts[0])
                val (r2, _) = parseCellRef(parts[1])
                if (r1 >= 0 && c1 >= 0 && r2 >= r1) {
                    mergedSpans[r1 to c1] = r2 - r1 + 1
                }
            }
        }

        val cellMap = mutableMapOf<Pair<Int, Int>, String>()
        var maxRow = -1
        var maxCol = -1
        val rowNodes = doc.getElementsByTagName("row")
        for (r in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(r) as Element
            val fallbackRowIdx = (rowEl.getAttribute("r").toIntOrNull()?.minus(1)) ?: r
            val cNodes = rowEl.getElementsByTagName("c")
            for (c in 0 until cNodes.length) {
                val cEl = cNodes.item(c) as Element
                val ref = cEl.getAttribute("r")
                val (rowIdx, colIdx) = if (ref.isNotBlank()) parseCellRef(ref) else (fallbackRowIdx to c)
                if (rowIdx < 0 || colIdx < 0) continue
                val type = cEl.getAttribute("t")
                val value = when (type) {
                    "s" -> {
                        val idx = cEl.getElementsByTagName("v").item(0)?.textContent?.trim()?.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "inlineStr" -> {
                        val tNodes = cEl.getElementsByTagName("t")
                        buildString {
                            for (k in 0 until tNodes.length) append(tNodes.item(k).textContent.orEmpty())
                        }
                    }
                    else -> cEl.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                }.trim()
                if (value.isNotEmpty()) {
                    cellMap[rowIdx to colIdx] = value
                    if (rowIdx > maxRow) maxRow = rowIdx
                    if (colIdx > maxCol) maxCol = colIdx
                }
            }
        }

        if (maxRow < 0 || maxCol < 0) return SheetGrid(name, emptyList(), mergedSpans)
        val rows = (0..maxRow).map { r ->
            (0..maxCol).map { c -> cellMap[r to c].orEmpty() }
        }
        return SheetGrid(name, rows, mergedSpans)
    }

    private fun parseCellRef(ref: String): Pair<Int, Int> {
        var col = 0
        var idx = 0
        while (idx < ref.length && ref[idx].isLetter()) {
            col = col * 26 + (ref[idx].uppercaseChar() - 'A' + 1)
            idx++
        }
        val rowNum = ref.substring(idx).toIntOrNull() ?: return -1 to -1
        return (rowNum - 1) to (col - 1)
    }

    private fun parseSheetGrid(sheet: SheetGrid): List<ParsedCourseCandidate> {
        if (sheet.rows.size < 2) return emptyList()

        // 模式 A：标准列表型（包含「课程名称 / 课程」表头）
        val tableHeaderIdx = sheet.rows.take(5).indexOfFirst { row ->
            row.any { it.contains("课程") } && row.any { it.contains("星期") || it.contains("周次") || it.contains("节次") }
        }
        if (tableHeaderIdx >= 0) {
            val headers = sheet.rows[tableHeaderIdx]
            val nameIdx = headers.indexOfFirst { it.contains("课程") }
            val teacherIdx = headers.indexOfFirst { it.contains("教师") || it.contains("老师") }
            val locIdx = headers.indexOfFirst { it.contains("地点") || it.contains("教室") }
            val dayIdx = headers.indexOfFirst { it.contains("星期") || it.contains("周几") }
            val periodIdx = headers.indexOfFirst { it.contains("节次") || it.contains("节数") }
            val weekIdx = headers.indexOfFirst { it.contains("周次") || it.contains("周数") }
            val parityIdx = headers.indexOfFirst { it.contains("单双") }

            return sheet.rows.drop(tableHeaderIdx + 1).mapNotNull { row ->
                val name = row.getOrNull(nameIdx)?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val teacher = row.getOrNull(teacherIdx)?.trim().orEmpty()
                val location = row.getOrNull(locIdx)?.trim().orEmpty()
                val dayRaw = row.getOrNull(dayIdx)?.trim().orEmpty()
                val periodRaw = row.getOrNull(periodIdx)?.trim().orEmpty()
                val weekRaw = buildString {
                    append(row.getOrNull(weekIdx)?.trim().orEmpty())
                    val p = row.getOrNull(parityIdx)?.trim().orEmpty()
                    if (p.isNotEmpty()) append("($p)")
                }

                val dow = ChineseCourseTextParser.parseDayOfWeek(dayRaw)
                    ?: dayRaw.toIntOrNull()?.takeIf { it in 1..7 }
                val periods = ChineseCourseTextParser.parsePeriods(
                    if (periodRaw.contains("节")) periodRaw else "${periodRaw}节"
                )
                val weeks = ChineseCourseTextParser.parseWeeks(
                    if (weekRaw.contains("周")) weekRaw else "${weekRaw}周"
                )

                val warnings = mutableListOf<String>()
                if (dow == null) warnings += "未识别星期"
                if (periods == null) warnings += "未识别节次"
                if (weeks == null) warnings += "未识别周次，默认1-16周"

                ParsedCourseCandidate(
                    name = name,
                    teacher = teacher,
                    location = location,
                    dayOfWeek = dow ?: 1,
                    startPeriod = periods?.first ?: 1,
                    endPeriod = periods?.second ?: 2,
                    weekRuleType = weeks?.ruleType ?: WeekRuleType.ALL,
                    startWeek = weeks?.startWeek ?: 1,
                    endWeek = weeks?.endWeek ?: 16,
                    customWeeks = weeks?.customWeeks ?: emptyList(),
                    courseType = if (PeCourseDetector.isLikelyPeCourse(name)) {
                        CourseType.PHYSICAL_EDUCATION
                    } else CourseType.NORMAL,
                    rawSnippet = row.filter { it.isNotBlank() }.joinToString(" | "),
                    needsManualReview = warnings.isNotEmpty(),
                    reviewWarning = warnings.joinToString("；").ifBlank { null }
                )
            }
        }

        // 模式 B：周视图网格型（表头含 周一..周日）
        var gridHeaderRow = -1
        val colToDow = mutableMapOf<Int, Int>()
        for (rIdx in 0 until sheet.rows.size.coerceAtMost(5)) {
            val temp = mutableMapOf<Int, Int>()
            sheet.rows[rIdx].forEachIndexed { cIdx, text ->
                ChineseCourseTextParser.parseDayOfWeek(text)?.let { temp[cIdx] = it }
            }
            if (temp.size >= 3) {
                gridHeaderRow = rIdx
                colToDow.putAll(temp)
                break
            }
        }

        if (gridHeaderRow >= 0) {
            val candidates = mutableListOf<ParsedCourseCandidate>()
            val firstDayCol = colToDow.keys.minOrNull() ?: 1
            val bodyRowCount = sheet.rows.size - (gridHeaderRow + 1)
            val defaultRowStep = if (bodyRowCount >= 8) 1 else 2
            val barePeriodRegex = Regex("^\\s*(\\d{1,2})(?:\\s*[-~至、,]\\s*(\\d{1,2}))?\\s*$")

            // 预计算各行对应的节次区间，保障合并跨行课程（如 1 行 1 节表中的 2 行合并）能拿到真实结束节次
            val rowPeriodRanges = mutableMapOf<Int, Pair<Int, Int>>()
            var nextPeriod = 1
            for (rIdx in (gridHeaderRow + 1) until sheet.rows.size) {
                val row = sheet.rows[rIdx]
                val scanEnd = firstDayCol.coerceAtLeast(1).coerceAtMost(row.size)
                var rowPeriods: Pair<Int, Int>? = null
                for (c in 0 until scanEnd) {
                    val label = row[c].trim()
                    if (label.isEmpty()) continue
                    rowPeriods = ChineseCourseTextParser.parsePeriods(label)
                    if (rowPeriods != null) break
                }
                if (rowPeriods == null) {
                    for (c in 0 until scanEnd) {
                        val m = barePeriodRegex.matchEntire(row[c].trim()) ?: continue
                        val s = m.groupValues[1].toIntOrNull() ?: continue
                        val e = m.groupValues[2].toIntOrNull() ?: s
                        if (s in 1..20 && e in s..20) {
                            rowPeriods = s to e
                            break
                        }
                    }
                }
                val defaultStart = rowPeriods?.first ?: nextPeriod
                val defaultEnd = rowPeriods?.second ?: (defaultStart + defaultRowStep - 1)
                rowPeriodRanges[rIdx] = defaultStart to defaultEnd
                nextPeriod = defaultEnd + 1
            }

            for (rIdx in (gridHeaderRow + 1) until sheet.rows.size) {
                val row = sheet.rows[rIdx]
                val (defaultStart, defaultEnd) = rowPeriodRanges[rIdx] ?: continue

                row.forEachIndexed { cIdx, text ->
                    val dow = colToDow[cIdx] ?: return@forEachIndexed
                    if (text.isBlank()) return@forEachIndexed
                    val mergeSpan = (sheet.mergedRowSpans[rIdx to cIdx] ?: 1).coerceIn(1, 12)
                    val lastCoveredRow = (rIdx + mergeSpan - 1).coerceAtMost(sheet.rows.lastIndex)
                    val effectiveEnd = if (mergeSpan > 1) {
                        rowPeriodRanges[lastCoveredRow]?.second ?: (defaultEnd + mergeSpan - 1)
                    } else {
                        defaultEnd
                    }.coerceAtLeast(defaultStart)
                    candidates += ChineseCourseTextParser.parseGridCell(text, dow, defaultStart, effectiveEnd)
                }
            }
            return candidates
        }

        // 模式 C：自由文本兜底
        val allText = sheet.rows.joinToString("\n") { it.filter(String::isNotBlank).joinToString(" ") }
        return ChineseCourseTextParser.parseFreeTextLines(allText)
    }
}
