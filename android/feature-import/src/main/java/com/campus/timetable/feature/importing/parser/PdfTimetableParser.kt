package com.campus.timetable.feature.importing.parser

import android.graphics.Bitmap
import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.model.ImportSourceType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 OCR 接口抽象：生产环境使用 Google ML Kit 本地中文文字识别；测试环境可注入可控实现。
 */
interface LocalOcrEngine {
    suspend fun recognizeText(bitmap: Bitmap): String
}

@Singleton
class MlKitChineseOcrEngine @Inject constructor() : LocalOcrEngine {
    override suspend fun recognizeText(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }
}

data class PdfParseOutcome(
    val sourceType: ImportSourceType,
    val candidates: List<ParsedCourseCandidate>,
    val extractedRawText: String,
    val usedOcr: Boolean,
    val summary: String
)

/**
 * 真实 PDF 课表解析器（PDFBox-Android 文本提取 + 扫描版 PDF 自动转图并调用 ML Kit 中文 OCR）。
 */
@Singleton
class PdfTimetableParser @Inject constructor(
    private val ocrEngine: MlKitChineseOcrEngine
) {

    suspend fun parse(
        inputStream: InputStream,
        customOcrEngine: LocalOcrEngine = ocrEngine
    ): Result<PdfParseOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = inputStream.readBytes()
            if (bytes.isEmpty()) throw IllegalArgumentException("所选 PDF 文件为空")

            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                val directText = stripper.getText(document).orEmpty().trim()

                // 判断是否为扫描图片型 PDF（无可选文本或有效字符极少）
                val nonWhitespaceLen = directText.count { !it.isWhitespace() }
                if (nonWhitespaceLen >= 20) {
                    val candidates = ChineseCourseTextParser.parseFreeTextLines(directText)
                    return@use PdfParseOutcome(
                        sourceType = ImportSourceType.PDF,
                        candidates = candidates,
                        extractedRawText = directText,
                        usedOcr = false,
                        summary = if (candidates.isNotEmpty()) {
                            "已通过 PDFBox 提取文本并识别出 ${candidates.size} 条排课，请检查预览并按需校正"
                        } else {
                            "已提取 PDF 文本，但未匹配到标准排课格式；您可在下方手动添加或校正"
                        }
                    )
                }

                // 扫描版 PDF -> 使用 PDFRenderer 渲染每页为高清 Bitmap 并执行本地 ML Kit 中文 OCR
                val renderer = PDFRenderer(document)
                val ocrTextBuilder = StringBuilder()
                val pageLimit = document.numberOfPages.coerceAtMost(8)
                for (pageIndex in 0 until pageLimit) {
                    val bitmap = renderer.renderImageWithDPI(pageIndex, 180f, ImageType.RGB)
                    try {
                        val pageText = customOcrEngine.recognizeText(bitmap)
                        if (pageText.isNotBlank()) {
                            ocrTextBuilder.appendLine(pageText)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                val ocrText = ocrTextBuilder.toString().trim()
                val ocrCandidates = ChineseCourseTextParser.parseFreeTextLines(ocrText)
                    .map {
                        it.copy(
                            needsManualReview = true,
                            reviewWarning = it.reviewWarning ?: "来自扫描件 OCR 识别，建议核对教室与周次"
                        )
                    }

                PdfParseOutcome(
                    sourceType = ImportSourceType.PDF_OCR,
                    candidates = ocrCandidates,
                    extractedRawText = ocrText,
                    usedOcr = true,
                    summary = if (ocrCandidates.isNotEmpty()) {
                        "检测到扫描版 PDF，已通过本地中文 OCR 识别出 ${ocrCandidates.size} 条课程，请务必核对校正后保存"
                    } else {
                        "已对扫描版 PDF 执行本地 OCR（共扫描 $pageLimit 页），未能自动结构化课表，请查看原始文本或手动录入"
                    }
                )
            }
        }
    }
}
