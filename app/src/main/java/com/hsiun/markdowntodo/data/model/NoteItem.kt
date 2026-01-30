package com.hsiun.markdowntodo.data.model

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 笔记数据类
 * 
 * 表示一个笔记，包含标题、内容和元数据。
 * 支持Markdown格式的序列化和反序列化。
 * 
 * @param id 笔记ID（已废弃，仅保留用于兼容，不再使用）
 * @param title 笔记标题
 * @param content 笔记内容（Markdown格式）
 * @param createdAt 创建时间（格式：yyyy-MM-dd HH:mm:ss）
 * @param updatedAt 更新时间（格式：yyyy-MM-dd HH:mm:ss）
 * @param uuid 唯一标识符（UUID格式，用于标识笔记）
 */
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
    // 生成安全的文件名：移除非法字符，用下划线替换空格，限制长度
    fun getSafeFileName(): String {
        // 移除非法文件名字符
        var safeName = title.replace(Regex("""[\\/:*?"<>|]"""), "_")
        // 替换空格为下划线
        safeName = safeName.replace(" ", "_")
        // 限制长度，避免文件名过长
        safeName = if (safeName.length > 50) {
            safeName.substring(0, 47) + "..."
        } else {
            safeName
        }
        // 确保文件名以字母或数字开头
        if (safeName.isNotEmpty() && !safeName[0].isLetterOrDigit()) {
            safeName = "note_$safeName"
        }
        // 如果没有有效字符，使用默认名称
        if (safeName.isEmpty() || safeName == "_") {
            safeName = "untitled_note"
        }
        return safeName
    }

    // 保存到文件时使用新格式：第一行是隐藏的UUID注释，第二行是标题；正文与结束符 --- 之间保留一个空行
    fun toMarkdown(): String {
        return "<!-- UUID: $uuid -->\n" +
                "# $title\n" +
                "> 创建时间: $createdAt | 更新时间: $updatedAt\n" +
                "---\n" +
                content.trim() + "\n\n" +
                "---\n"
    }

    companion object {
        // 从文件读取时解析新格式
        fun fromMarkdown(text: String, id: Int? = null, uuid: String? = null): NoteItem? {
            return try {
                val lines = text.lines()
                if (lines.isEmpty()) return null

                var currentLineIndex = 0
                var extractedUuid = uuid ?: ""

                // 尝试从第一行的HTML注释中提取UUID
                if (currentLineIndex < lines.size && lines[currentLineIndex].startsWith("<!-- UUID: ") && lines[currentLineIndex].endsWith(" -->")) {
                    val firstLine = lines[currentLineIndex]
                    extractedUuid = firstLine.substringAfter("<!-- UUID: ").substringBefore(" -->").trim()
                    currentLineIndex++
                }

                // 兼容旧格式：如果第二行是ID注释，跳过它
                if (currentLineIndex < lines.size && lines[currentLineIndex].startsWith("<!-- ID: ") && lines[currentLineIndex].endsWith(" -->")) {
                    currentLineIndex++
                }

                // 如果没有找到UUID，生成一个新的
                if (extractedUuid.isEmpty()) {
                    extractedUuid = UUID.randomUUID().toString()
                }

                // 跳过可能的空行
                while (currentLineIndex < lines.size && lines[currentLineIndex].isBlank()) {
                    currentLineIndex++
                }

                // 标题行（现在是第一行或第二行）
                if (currentLineIndex >= lines.size) return null
                val titleLine = lines[currentLineIndex]
                val title = if (titleLine.startsWith("# ")) {
                    titleLine.substring(2).trim()
                } else {
                    titleLine.trim()
                }
                currentLineIndex++

                // 解析元数据（创建时间和更新时间）
                var createdAt = ""
                var updatedAt = ""

                while (currentLineIndex < lines.size && !lines[currentLineIndex].startsWith("---")) {
                    val line = lines[currentLineIndex]
                    if (line.startsWith("> 创建时间:")) {
                        val parts = line.split("|")
                        if (parts.isNotEmpty()) {
                            createdAt = parts[0].replace("> 创建时间:", "").trim()
                        }
                        if (parts.size > 1) {
                            updatedAt = parts[1].replace("更新时间:", "").trim()
                        }
                    }
                    currentLineIndex++
                }

                // 跳过分隔线
                while (currentLineIndex < lines.size && lines[currentLineIndex] != "---") {
                    currentLineIndex++
                }
                currentLineIndex++ // 跳过第一个"---"

                // 收集内容，直到遇到第二个"---"
                val contentBuilder = StringBuilder()
                while (currentLineIndex < lines.size && lines[currentLineIndex] != "---") {
                    contentBuilder.append(lines[currentLineIndex]).append("\n")
                    currentLineIndex++
                }

                val content = contentBuilder.toString().trim()

                // 如果没找到时间，使用当前时间
                if (createdAt.isEmpty()) {
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }
                if (updatedAt.isEmpty()) {
                    updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }

                // 使用传入的ID（兼容旧代码），如果没有则使用-1（不再使用ID，仅保留用于兼容）
                val finalId = id ?: -1

                NoteItem(
                    id = finalId,
                    title = title,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    uuid = extractedUuid
                )
            } catch (e: Exception) {
                Log.e("NoteItem", "解析笔记失败: ${e.message}")
                null
            }
        }
    }
}