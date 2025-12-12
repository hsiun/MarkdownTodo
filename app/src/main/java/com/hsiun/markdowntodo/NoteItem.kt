// NoteItem.kt
package com.hsiun.markdowntodo

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

data class NoteItem(
    val id: Int,
    var title: String,
    var content: String,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    val updatedAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    val uuid: String = UUID.randomUUID().toString()
) {
    fun toMarkdown(): String {
        return "# $title\n\n" +
                "> 创建时间: $createdAt  |  更新时间: $updatedAt\n\n" +
                "---\n\n" +
                content +
                "\n\n---\n" +
                "ID: $id | UUID: $uuid\n"
    }

    companion object {
        fun fromMarkdown(text: String): NoteItem? {
            return try {
                val lines = text.lines()
                if (lines.isEmpty()) return null

                // 提取标题 (第一行去掉 # 和空格)
                val titleLine = lines.first()
                val title = if (titleLine.startsWith("# ")) {
                    titleLine.substring(2).trim()
                } else {
                    titleLine.trim()
                }

                // 解析元数据行
                var id = -1
                var uuid = ""
                var createdAt = ""
                var updatedAt = ""
                var contentStartIndex = 0

                for (i in lines.indices) {
                    val line = lines[i]
                    when {
                        line.startsWith("> 创建时间:") -> {
                            val parts = line.split("|")
                            if (parts.isNotEmpty()) {
                                createdAt = parts[0].replace("> 创建时间:", "").trim()
                            }
                            if (parts.size > 1) {
                                updatedAt = parts[1].replace("更新时间:", "").trim()
                            }
                        }
                        line.startsWith("ID:") -> {
                            val idMatch = Regex("ID: (\\d+)").find(line)
                            idMatch?.let {
                                id = it.groupValues[1].toInt()
                            }
                            val uuidMatch = Regex("UUID: ([^ ]+)").find(line)
                            uuidMatch?.let {
                                uuid = it.groupValues[1]
                            }
                        }
                        line == "---" -> {
                            // 找到第二个分隔符后的内容开始位置
                            if (contentStartIndex == 0) {
                                contentStartIndex = i + 1
                            }
                        }
                    }
                }

                // 提取内容（在两个---之间的部分）
                val contentLines = mutableListOf<String>()
                var inContent = false
                var separatorCount = 0

                for (i in lines.indices) {
                    val line = lines[i]
                    when {
                        line == "---" -> {
                            separatorCount++
                            if (separatorCount == 2) {
                                inContent = true
                            } else if (separatorCount == 3) {
                                break
                            }
                        }
                        inContent && separatorCount == 2 -> {
                            contentLines.add(line)
                        }
                    }
                }

                val content = contentLines.joinToString("\n").trim()

                NoteItem(
                    id = id,
                    title = title,
                    content = content,
                    createdAt = createdAt.ifEmpty { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) },
                    updatedAt = updatedAt.ifEmpty { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) },
                    uuid = uuid.ifEmpty { UUID.randomUUID().toString() }
                )
            } catch (e: Exception) {
                Log.e("NoteItem", "解析笔记失败: ${e.message}")
                null
            }
        }
    }
}