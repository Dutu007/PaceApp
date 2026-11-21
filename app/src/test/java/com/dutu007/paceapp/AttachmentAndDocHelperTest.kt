package com.dutu007.paceapp

import com.dutu007.paceapp.widget.computeNormalizedBarRatios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentAndDocHelperTest {
    @Test
    fun extractsPaceDocAndLocalAttachmentChips() {
        val docToken = encodePaceDocMarkdown("季度汇报.docx", "uploads/20261120_report.docx", isImage = false)
        val imgToken = encodePaceLocalMarkdown("run.jpg", "file:///cache/sent_images/1.jpg", isImage = true)
        val raw = "$docToken $imgToken\n请帮我总结重点"

        val (cleanText, chips) = extractChatAttachmentChips(raw)

        assertEquals("请帮我总结重点", cleanText)
        assertEquals(2, chips.size)

        assertEquals("季度汇报.docx", chips[0].label)
        assertEquals("uploads/20261120_report.docx", chips[0].docPath)
        assertNull(chips[0].localUri)
        assertFalse(chips[0].isImage)

        assertEquals("run.jpg", chips[1].label)
        assertNull(chips[1].docPath)
        assertEquals("file:///cache/sent_images/1.jpg", chips[1].localUri)
        assertTrue(chips[1].isImage)
    }

    @Test
    fun extractsReloadedHermesUploadedDocBlock() {
        val raw = """
            [附件文档已上传至服务器：/home/dutu/workspace/personal-assistant/data/uploads/20261120_plan.xlsx（原文件名：plan.xlsx）]
            文档内容提取：
            | 项目 | 预算 |
            | --- | --- |
            | A | 100 |

            帮我核对一下金额
        """.trimIndent()

        val (cleanText, chips) = extractChatAttachmentChips(raw)

        assertEquals("帮我核对一下金额", cleanText)
        assertEquals(1, chips.size)
        assertEquals("plan.xlsx", chips[0].label)
        assertEquals("uploads/20261120_plan.xlsx", chips[0].docPath)
        assertFalse(chips[0].isImage)
    }

    @Test
    fun resolvesOfficeMimeTypesAndBadges() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            mimeTypeForFileName("contract.DOCX"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            mimeTypeForFileName("sheet.xlsx"),
        )
        assertEquals("application/pdf", mimeTypeForFileName("paper.pdf"))
        assertEquals("PDF", fileTypeBadge("paper.pdf").first)
        assertEquals("WORD", fileTypeBadge("contract.docx").first)
        assertEquals("EXCEL", fileTypeBadge("sheet.xlsx").first)
        assertEquals("PPT", fileTypeBadge("slides.pptx").first)
    }

    @Test
    fun buildsValid16kMonoWavHeader() {
        val pcm = ByteArray(3200) { 1 }
        val wav = buildWavBytes(pcm, sampleRate = 16000)

        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun appendsVoiceTranscriptAtEnd() {
        assertEquals("第一句。", appendTranscriptText("", "第一句。"))
        assertEquals("第一句。第二句。", appendTranscriptText("第一句。", "第二句。"))
        assertEquals("Hermes 你好", appendTranscriptText("Hermes", "你好"))
    }

    @Test
    fun formatsDurationMinutesCleanly() {
        assertEquals("0m", formatDurationMinutes(0))
        assertEquals("45m", formatDurationMinutes(45))
        assertEquals("2h", formatDurationMinutes(120))
        assertEquals("2h 8m", formatDurationMinutes(128))
        assertEquals("0m", formatDurationMinutes(-10))
    }

    @Test
    fun computesNormalizedWidgetBarRatios() {
        assertTrue(computeNormalizedBarRatios(emptyList()).isEmpty())
        assertEquals(listOf(0f, 0f), computeNormalizedBarRatios(listOf(0.0, 0.0)))
        val ratios = computeNormalizedBarRatios(listOf(0.0, 1.0, 100.0))
        assertEquals(0f, ratios[0], 0.0001f)
        assertEquals(0.10f, ratios[1], 0.0001f)
        assertEquals(1.0f, ratios[2], 0.0001f)
    }

    @Test
    fun extractsAssistantDocLinksMediaAndPaths() {
        val raw = """
            已为你生成运动记录文档：
            [📄 运动记录.docx](pace-doc://exports/运动记录.docx)
            另外附带表格 /home/dutu/workspace/personal-assistant/data/exports/周汇总.xlsx。
            MEDIA:/home/dutu/workspace/personal-assistant/data/exports/运动记录.docx
        """.trimIndent()

        val (cleanText, chips) = extractChatAttachmentChips(raw)

        assertTrue(cleanText.contains("已为你生成运动记录文档"))
        assertFalse(cleanText.contains("pace-doc://"))
        assertFalse(cleanText.contains("MEDIA:"))
        assertEquals(2, chips.size)
        assertEquals("运动记录.docx", chips[0].label)
        assertEquals("exports/运动记录.docx", chips[0].docPath)
        assertFalse(chips[0].isImage)
        assertEquals("周汇总.xlsx", chips[1].label)
        assertEquals("exports/周汇总.xlsx", chips[1].docPath)
        assertFalse(chips[1].isImage)
    }
}
