package com.dutu007.paceapp

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
}
