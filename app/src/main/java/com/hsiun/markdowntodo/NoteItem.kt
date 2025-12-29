import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    // 保存到文件时使用新格式：第一行是隐藏的UUID注释，第二行是标题
    fun toMarkdown(): String {
        return "<!-- UUID: $uuid -->\n" +
                "# $title\n" +
                "> 创建时间: $createdAt | 更新时间: $updatedAt\n" +
                "---\n" +
                content.trim() + "\n" +
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
                if (lines.first().startsWith("<!-- UUID: ") && lines.first().endsWith(" -->")) {
                    val firstLine = lines.first()
                    extractedUuid = firstLine.substringAfter("<!-- UUID: ").substringBefore(" -->").trim()
                    currentLineIndex = 1
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

                // 使用传入的ID或生成新的
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