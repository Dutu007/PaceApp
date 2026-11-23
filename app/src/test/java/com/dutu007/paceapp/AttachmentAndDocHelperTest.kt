package com.dutu007.paceapp

import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.PushNotificationItem
import com.dutu007.paceapp.push.parseConnectedLatestId
import com.dutu007.paceapp.push.parsePushSseNotification
import com.dutu007.paceapp.widget.computeNormalizedBarRatios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun parsesSleepClocksAndAveragesAcrossMidnight() {
        assertEquals("23:59", extractClockHHMM("2026-11-20 23:59"))
        assertEquals("08:55", extractClockHHMM("2026-11-21T08:55:00+08:00"))
        assertEquals("07:05", extractClockHHMM("7:05"))
        assertEquals("", extractClockHHMM("2026-11-21"))

        assertEquals(1439, parseClockMinutes("23:59"))
        assertEquals(1439, sleepAxisOffsetMinutes(1439))
        assertEquals(1498, sleepAxisOffsetMinutes(58))
        assertEquals("00:58", formatAxisOffsetClock(1498))

        assertEquals("00:00", averageBedtimeClock(listOf("2026-11-20 23:50", "2026-11-21 00:10")))
        assertEquals("08:35", averageWakeClock(listOf("07:15", "09:55")))

        assertEquals("--", formatCompactDuration(0))
        assertEquals("8h03m", formatCompactDuration(483))
        assertEquals("9h", formatCompactDuration(540))
        assertEquals("45m", formatCompactDuration(45))

        assertEquals("--" to null, formatBarDurationLines(0))
        assertEquals("8h" to "03m", formatBarDurationLines(483))
        assertEquals("9h" to null, formatBarDurationLines(540))
        assertEquals("45m" to null, formatBarDurationLines(45))

        assertEquals(0xFF0E9F6E.toInt(), sleepScoreColorInt(90))
        assertEquals(0xFFD97706.toInt(), sleepScoreColorInt(77))
        assertEquals(0xFFDC2626.toInt(), sleepScoreColorInt(57))

        assertEquals(45 to 100, computeSleepScoreRange(listOf(87, 84, 90, 88, 57, 77)))
        assertEquals(70 to 100, computeSleepScoreRange(listOf(86, 88, 90)))
        assertEquals(50 to 100, computeSleepScoreRange(emptyList()))

        assertEquals(0f, sleepDurationIntensity(120), 0.0001f)
        assertEquals(0f, sleepDurationIntensity(180), 0.0001f)
        assertEquals(0.5f, sleepDurationIntensity(390), 0.0001f)
        assertEquals(1f, sleepDurationIntensity(600), 0.0001f)
        assertEquals(1f, sleepDurationIntensity(720), 0.0001f)

        val lightBar = sleepDurationBarColors(180)
        val midBar = sleepDurationBarColors(390)
        val deepBar = sleepDurationBarColors(600)
        assertEquals(0xFF181A66.toInt(), lightBar.textArgb)
        assertEquals(0xFFFFFFFF.toInt(), deepBar.textArgb)
        assertTrue(((deepBar.fillArgb ushr 24) and 0xFF) > ((lightBar.fillArgb ushr 24) and 0xFF))
        assertTrue(((lightBar.fillArgb shr 16) and 0xFF) > ((midBar.fillArgb shr 16) and 0xFF))
        assertTrue(((midBar.fillArgb shr 16) and 0xFF) > ((deepBar.fillArgb shr 16) and 0xFF))
    }

    @Test
    fun parsesDocxParagraphsTablesAndEmbeddedImages() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
                </Relationships>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                      <w:r><w:t>周报汇总</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:r><w:t>配图如下：</w:t></w:r>
                      <w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r>
                    </w:p>
                    <w:tbl>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>数值</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>睡眠</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>8h 03m</w:t></w:r></w:p></w:tc>
                      </w:tr>
                    </w:tbl>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/media/image1.png"))
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            zos.closeEntry()
        }

        val blocks = parseDocxBlocks(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is DocxBlock.Heading)
        assertEquals("周报汇总", (blocks[0] as DocxBlock.Heading).text)
        assertEquals("配图如下：", (blocks[1] as DocxBlock.Paragraph).text)
        assertEquals("word/media/image1.png", (blocks[2] as DocxBlock.EmbeddedImage).entryPath)
        assertEquals(listOf("睡眠", "8h 03m"), (blocks[3] as DocxBlock.Table).rows[1])
        assertTrue(docxPlainText(blocks).contains("周报汇总"))
    }

    @Test
    fun parsesPushSseNotificationJson() {
        val item = parsePushSseNotification(
            """{"id":12,"title":"COROS 每日同步","body":"昨晚睡了 8 小时","category":"coros","is_read":false}""",
        )
        assertNotNull(item)
        assertEquals(12L, item!!.id)
        assertEquals("COROS 每日同步", item.title)
        assertEquals("coros", item.category)
        assertEquals(42L, parseConnectedLatestId("""{"status":"ok","latest_id":42,"unread_count":1}"""))
        assertNull(parsePushSseNotification("""{"id":0,"body":"invalid"}"""))
    }

    @Test
    fun cleansOutOfBandSteerMarkerAndProactiveContext() {
        val rawSteer = """
            [OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position; not tool output and not a new delivery when replayed from conversation history]
            顺便把睡眠表格也发我
            [/OUT-OF-BAND USER MESSAGE]
        """.trimIndent()
        assertEquals("顺便把睡眠表格也发我", cleanDisplayUserMessage(rawSteer))

        val rawWithProactive = """
            [PACE_PROACTIVE_CONTEXT]
            小洛刚刚主动推送给用户的提醒：
            **COROS 每日同步**

            昨晚睡了 8 小时
            [/PACE_PROACTIVE_CONTEXT]

            帮我看下睡眠评分趋势
        """.trimIndent()
        assertEquals("帮我看下睡眠评分趋势", cleanDisplayUserMessage(rawWithProactive))

        val (cleanChipText, _) = extractChatAttachmentChips(rawSteer)
        assertEquals("顺便把睡眠表格也发我", cleanChipText)
    }

    @Test
    fun mergesProactiveNotificationsAndFiltersToolMessages() {
        val sessionMsgs = listOf(
            ChatMessage(id = "u1", role = "user", content = "早上好", timestamp = 1795340000.0),
            ChatMessage(id = "a_blank", role = "assistant", content = "", timestamp = 1795340001.0, isStreaming = false),
            ChatMessage(id = "t1", role = "tool", content = "tool raw output", toolName = "terminal", timestamp = 1795340002.0),
            ChatMessage(id = "a1", role = "assistant", content = "早，今天有什么安排？", timestamp = 1795340005.0),
            ChatMessage(id = "u2", role = "user", content = "中午呢", timestamp = 1795350000.0),
            ChatMessage(id = "a2", role = "assistant", content = "收到。", timestamp = 1795350005.0),
        )
        val notifications = listOf(
            PushNotificationItem(
                id = 10L,
                title = "COROS 每日同步",
                body = "昨晚睡了 8h 03m，评分 77",
                category = "coros",
                sessionId = "",
                isRead = true,
                createdAt = "2026-11-22T09:00:00Z",
            ),
            PushNotificationItem(
                id = 11L,
                title = "其他会话提醒",
                body = "仅限另一会话",
                category = "reminder",
                sessionId = "other_session",
                isRead = false,
                createdAt = "2026-11-22T09:05:00Z",
            ),
        )
        val tPush = parseIsoEpochSeconds("2026-11-22T09:00:00Z")!!
        val bracketedMsgs = listOf(
            sessionMsgs[0].copy(timestamp = tPush - 600.0),
            sessionMsgs[1],
            sessionMsgs[2],
            sessionMsgs[3].copy(timestamp = tPush - 595.0),
            sessionMsgs[4].copy(timestamp = tPush + 600.0),
            sessionMsgs[5].copy(timestamp = tPush + 605.0),
        )

        val merged = mergeChatAndProactiveMessages(
            sessionMessages = bracketedMsgs,
            notifications = notifications,
            activeSessionId = "sess_latest",
            isLatestSession = true,
            liveNotificationIds = setOf(10L),
        )

        // Should exclude blank assistant turn (a_blank), tool msg (t1), and other_session push (id=11)
        assertEquals(5, merged.size)
        assertEquals(listOf("u1", "a1", "push_10", "u2", "a2"), merged.map { it.id })
        assertTrue(merged[2].isProactive)
        assertTrue(merged[2].content.contains("COROS 每日同步"))
        assertTrue(merged[2].content.contains("昨晚睡了 8h 03m，评分 77"))

        // Fresh empty session should NOT pull in already-read notifications unless in liveNotificationIds
        val freshMerged = mergeChatAndProactiveMessages(
            sessionMessages = emptyList(),
            notifications = notifications,
            activeSessionId = "new_sess",
            isLatestSession = true,
            liveNotificationIds = emptySet(),
        )
        assertTrue(freshMerged.isEmpty())
    }

    @Test
    fun formatsProactiveBubbleAndCorosTitleSeparately() {
        val chatBubble = PushNotificationItem(
            id = 21L,
            title = "小洛",
            body = "论文引言改得怎么样了，别走神",
            category = "proactive_chat",
        )
        assertEquals("论文引言改得怎么样了，别走神", formatProactiveNotificationContent(chatBubble))

        val strictBubble = PushNotificationItem(
            id = 22L,
            title = "小洛 · 督学提醒",
            body = "下午了还没去实验室打卡，现在就动身。",
            category = "proactive_strict",
        )
        assertEquals("下午了还没去实验室打卡，现在就动身。", formatProactiveNotificationContent(strictBubble))

        val corosNotice = PushNotificationItem(
            id = 23L,
            title = "COROS 每日同步",
            body = "昨晚睡了 7h 45m",
            category = "coros",
        )
        assertEquals("**COROS 每日同步**\n\n昨晚睡了 7h 45m", formatProactiveNotificationContent(corosNotice))
    }

    @Test
    fun formatsCompactDurationForSevenDayLabBars() {
        assertEquals("--", formatCompactDuration(0))
        assertEquals("45m", formatCompactDuration(45))
        assertEquals("2h", formatCompactDuration(120))
        assertEquals("5h05m", formatCompactDuration(305))
    }
}
