package com.campus.timetable.feature.importing

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.campus.timetable.domain.model.CourseType
import com.campus.timetable.domain.model.ImportSourceType
import com.campus.timetable.domain.model.WeekRuleType
import com.campus.timetable.feature.importing.parser.ExcelTimetableParser
import com.campus.timetable.feature.importing.parser.LocalOcrEngine
import com.campus.timetable.feature.importing.parser.MlKitChineseOcrEngine
import com.campus.timetable.feature.importing.parser.PdfTimetableParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.test.runTest
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileParsersTest {

    private val excelParser = ExcelTimetableParser()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun `real binary xls workbook parses via Apache POI`() {
        val out = ByteArrayOutputStream()
        HSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("课表")
            val header = sheet.createRow(0)
            listOf("课程名称", "任课教师", "上课地点", "星期", "节次", "周次").forEachIndexed { idx, s ->
                header.createCell(idx).setCellValue(s)
            }
            val row1 = sheet.createRow(1)
            listOf("数据结构", "王教授", "信息楼302", "星期二", "3-4节", "1-16周(单)").forEachIndexed { idx, s ->
                row1.createCell(idx).setCellValue(s)
            }
            val row2 = sheet.createRow(2)
            listOf("大学体育(篮球)", "刘教练", "南区篮球场", "星期四", "7-8节", "1,3,5,7周").forEachIndexed { idx, s ->
                row2.createCell(idx).setCellValue(s)
            }
            wb.write(out)
        }

        val parsed = excelParser.parse(ByteArrayInputStream(out.toByteArray()), "my_schedule.xls").getOrThrow()
        assertEquals(2, parsed.size)
        assertEquals("数据结构", parsed[0].name)
        assertEquals(2, parsed[0].dayOfWeek)
        assertEquals(WeekRuleType.ODD, parsed[0].weekRuleType)
        assertEquals("大学体育(篮球)", parsed[1].name)
        assertEquals(CourseType.PHYSICAL_EDUCATION, parsed[1].courseType)
        assertEquals(WeekRuleType.CUSTOM, parsed[1].weekRuleType)
        assertEquals(listOf(1, 3, 5, 7), parsed[1].customWeeks)
    }

    @Test
    fun `real xlsx ooxml workbook with sharedStrings parses weekly grid accurately`() {
        val sharedStringsXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "  <si><t>节次</t></si>\n" +
            "  <si><t>星期一</t></si>\n" +
            "  <si><t>星期二</t></si>\n" +
            "  <si><t>星期三</t></si>\n" +
            "  <si><t>第1-2节</t></si>\n" +
            "  <si><t>软件工程\n赵老师\n1-16周(双)\n教B-401</t></si>\n" +
            "</sst>"
        )
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="s"><v>2</v></c>
                  <c r="D1" t="s"><v>3</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>4</v></c>
                  <c r="C2" t="s"><v>5</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(sharedStringsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheetXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val parsed = excelParser.parse(ByteArrayInputStream(baos.toByteArray()), "grid.xlsx").getOrThrow()
        assertEquals(1, parsed.size)
        val c = parsed.first()
        assertEquals("软件工程", c.name)
        assertEquals(2, c.dayOfWeek) // 星期二
        assertEquals(1, c.startPeriod)
        assertEquals(2, c.endPeriod)
        assertEquals(WeekRuleType.EVEN, c.weekRuleType)
        assertEquals("教B-401", c.location)
    }

    @Test
    fun `real pdf text extraction and scanned pdf ocr fallback both work cleanly`() = runTest {
        val pdfParser = PdfTimetableParser(MlKitChineseOcrEngine())

        // 1. 含纯文本层的 PDF（使用 PDFBox 真实生成）
        val textPdfOut = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(40f, 700f)
                cs.showText("LinearAlgebra ProfZhang A-201 1-16 3-4")
                cs.endText()
            }
            doc.save(textPdfOut)
        }
        val textOutcome = pdfParser.parse(ByteArrayInputStream(textPdfOut.toByteArray())).getOrThrow()
        assertFalse(textOutcome.usedOcr)
        assertEquals(ImportSourceType.PDF, textOutcome.sourceType)
        assertTrue(textOutcome.extractedRawText.contains("LinearAlgebra"))

        // 2. 扫描空白文本层 PDF -> 触发本地 OCR 回退路径并标记需人工校正
        val scannedPdfOut = ByteArrayOutputStream()
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(scannedPdfOut)
        }
        val testOcr = object : LocalOcrEngine {
            override suspend fun recognizeText(bitmap: Bitmap): String {
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                return "高等数学 李老师 教A-102 周一 1-2节 1-16周"
            }
        }
        val ocrOutcome = pdfParser.parse(ByteArrayInputStream(scannedPdfOut.toByteArray()), customOcrEngine = testOcr).getOrThrow()
        assertTrue(ocrOutcome.usedOcr)
        assertEquals(ImportSourceType.PDF_OCR, ocrOutcome.sourceType)
        assertEquals(1, ocrOutcome.candidates.size)
        assertEquals("高等数学", ocrOutcome.candidates.first().name)
        assertTrue(ocrOutcome.candidates.first().needsManualReview)
    }

    @Test
    fun `xlsx merged rowspan cells compute full period range across rows`() {
        val sharedStringsXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "  <si><t>节次</t></si>\n" +
            "  <si><t>星期一</t></si>\n" +
            "  <si><t>星期二</t></si>\n" +
            "  <si><t>星期三</t></si>\n" +
            "  <si><t>第1节</t></si>\n" +
            "  <si><t>第2节</t></si>\n" +
            "  <si><t>计算机组成原理\n李教授\n1-16周\n教A-204</t></si>\n" +
            "</sst>"
        )
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="s"><v>2</v></c>
                  <c r="D1" t="s"><v>3</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>4</v></c>
                  <c r="B2" t="s"><v>6</v></c>
                </row>
                <row r="3">
                  <c r="A3" t="s"><v>5</v></c>
                </row>
              </sheetData>
              <mergeCells count="1">
                <mergeCell ref="B2:B3"/>
              </mergeCells>
            </worksheet>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(sharedStringsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheetXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val parsed = excelParser.parse(ByteArrayInputStream(baos.toByteArray()), "merged_periods.xlsx").getOrThrow()
        assertEquals(1, parsed.size)
        val c = parsed.first()
        assertEquals("计算机组成原理", c.name)
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startPeriod)
        assertEquals(2, c.endPeriod)
    }

    @Test
    fun `school entry classifier distinguishes login, portal, academic office, and avoids cas false positive`() {
        assertEquals(
            SchoolEntryKind.LOGIN_SYSTEM,
            SchoolEntryClassifier.classify("https://jwxt.example.edu.cn/eams/localLogin.action", "https://jwc.example.edu.cn")
        )
        assertEquals(
            SchoolEntryKind.PORTAL_HOMEPAGE,
            SchoolEntryClassifier.classify("https://info.tsinghua.edu.cn", "https://www.tsinghua.edu.cn/jwc/")
        )
        assertEquals(
            SchoolEntryKind.ACADEMIC_HOMEPAGE,
            SchoolEntryClassifier.classify("https://www.tsinghua.edu.cn/jwc/", "https://www.tsinghua.edu.cn/jwc/")
        )
        // ucass.edu.cn 不应被 "cas" 子串误判为 CAS 登录页
        assertEquals(
            SchoolEntryKind.ACADEMIC_HOMEPAGE,
            SchoolEntryClassifier.classify("https://jw.ucass.edu.cn", "https://jw.ucass.edu.cn")
        )
        assertFalse(SchoolEntryClassifier.looksLikeLoginEntry("https://jw.ucass.edu.cn"))
        assertEquals(
            SchoolEntryKind.SEARCH,
            SchoolEntryClassifier.classify("https://www.bing.com/search?q=test", "", isSearch = true)
        )
    }
}
