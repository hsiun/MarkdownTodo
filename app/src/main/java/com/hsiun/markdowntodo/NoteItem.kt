package com.hsiun.markdowntodo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "新笔记",
    var content: String = "",
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm",
        Locale.getDefault()
    ).format(Date()),
    var updatedAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm",
        Locale.getDefault()
    ).format(Date())
) {
    fun toMarkdown(): String {
        val header = "---\n" +
                "title: $title\n" +
                "created: $createdAt\n" +
                "updated: $updatedAt\n" +
                "id: $id\n" +
                "---\n\n"
        return header + content
    }

    companion object {
        fun fromMarkdown(markdown: String): NoteItem? {
            return try {
                val lines = markdown.lines()

                // 解析YAML头部
                if (!lines.firstOrNull().equals("---")) {
                    return NoteItem(title = "未命名笔记", content = markdown)
                }

                val headerEndIndex = lines.drop(1).indexOfFirst { it == "---" } + 1
                if (headerEndIndex <= 1) {
                    return NoteItem(title = "未命名笔记", content = markdown)
                }

                val headerLines = lines.subList(1, headerEndIndex)
                val contentStart = headerEndIndex + 1
                val content = if (contentStart < lines.size) {
                    lines.subList(contentStart, lines.size).joinToString("\n")
                } else {
                    ""
                }

                var title = "未命名笔记"
                var id = UUID.randomUUID().toString()
                var createdAt = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    Locale.getDefault()
                ).format(Date())
                var updatedAt = createdAt

                headerLines.forEach { line ->
                    when {
                        line.startsWith("title:") -> title = line.substring(6).trim()
                        line.startsWith("id:") -> id = line.substring(3).trim()
                        line.startsWith("created:") -> createdAt = line.substring(8).trim()
                        line.startsWith("updated:") -> updatedAt = line.substring(8).trim()
                    }
                }

                NoteItem(
                    id = id,
                    title = title,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}