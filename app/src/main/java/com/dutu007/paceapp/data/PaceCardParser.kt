package com.dutu007.paceapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface MessageSegment {
    data class Markdown(val text: String) : MessageSegment
    data class Card(val card: PaceCardData) : MessageSegment
}

object PaceCardParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val cardBlockRegex = Regex("```pace-card\\s*\\n([\\s\\S]*?)```")

    fun splitMessage(raw: String): List<MessageSegment> {
        if (!raw.contains("```pace-card")) {
            return if (raw.isBlank()) emptyList() else listOf(MessageSegment.Markdown(raw))
        }
        val segments = mutableListOf<MessageSegment>()
        var cursor = 0
        for (match in cardBlockRegex.findAll(raw)) {
            val before = raw.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) {
                segments += MessageSegment.Markdown(before)
            }
            val parsed = parseCardJson(match.groupValues[1])
            if (parsed != null) {
                segments += MessageSegment.Card(parsed)
            } else {
                segments += MessageSegment.Markdown(match.value)
            }
            cursor = match.range.last + 1
        }
        val tail = raw.substring(cursor).trim()
        if (tail.isNotEmpty()) {
            segments += MessageSegment.Markdown(tail)
        }
        return segments
    }

    fun parseCardJson(rawJson: String): PaceCardData? {
        val obj = runCatching { json.parseToJsonElement(rawJson.trim()).jsonObject }.getOrNull() ?: return null
        return parseCardObject(obj)
    }

    fun parseCardObject(obj: JsonObject): PaceCardData {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() } ?: "note"
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "展示卡片" }
        val subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val items = (obj["items"] as? JsonArray)?.mapNotNull { el ->
            val itemObj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = itemObj["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            PaceCardItem(
                label = label,
                done = itemObj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                detail = itemObj["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        } ?: emptyList()

        val metrics = (obj["metrics"] as? JsonArray)?.mapNotNull { el ->
            val mObj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = mObj["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val value = mObj["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (label.isBlank() && value.isBlank()) return@mapNotNull null
            PaceCardMetric(
                label = label,
                value = value,
                unit = mObj["unit"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        } ?: emptyList()

        val columns = (obj["columns"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?: emptyList()

        val rows = (obj["rows"] as? JsonArray)?.mapNotNull { rowEl ->
            runCatching {
                rowEl.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull.orEmpty() }
            }.getOrNull()
        } ?: emptyList()

        return PaceCardData(
            type = type,
            title = title,
            subtitle = subtitle,
            items = items,
            metrics = metrics,
            columns = columns,
            rows = rows,
            content = content,
            rawJson = obj,
        )
    }
}
