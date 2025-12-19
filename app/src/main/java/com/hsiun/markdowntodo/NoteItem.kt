import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

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
    // 保存到文件时使用完整格式（包含分隔符）
    fun toMarkdown(): String {
        return "# $title\n" +
                "> 创建时间: $createdAt | 更新时间: $updatedAt\n" +
                "---\n" +
                content.trim() + "\n" +
                "---\n"
    }

    companion object {
        // 从文件读取时解析完整格式
        fun fromMarkdown(text: String, id: Int? = null, uuid: String? = null): NoteItem? {
            return try {
                val lines = text.lines()
                if (lines.isEmpty()) return null

                // 1. 提取标题
                val titleLine = lines.first()
                val title = if (titleLine.startsWith("# ")) {
                    titleLine.substring(2).trim()
                } else {
                    titleLine.trim()
                }

                // 2. 解析元数据
                var createdAt = ""
                var updatedAt = ""

                for (i in 1 until min(5, lines.size)) {
                    val line = lines[i]
                    if (line.startsWith("> 创建时间:")) {
                        val parts = line.split("|")
                        if (parts.isNotEmpty()) {
                            createdAt = parts[0].replace("> 创建时间:", "").trim()
                        }
                        if (parts.size > 1) {
                            updatedAt = parts[1].replace("更新时间:", "").trim()
                        }
                        break
                    }
                }

                // 3. 提取内容（在第一个---和第二个---之间）
                val contentBuilder = StringBuilder()
                var inContent = false
                var separatorCount = 0

                for (i in lines.indices) {
                    val line = lines[i]

                    if (line == "---") {
                        separatorCount++
                        if (separatorCount == 1) {
                            inContent = true
                            continue  // 跳过分隔符本身
                        } else if (separatorCount == 2) {
                            break  // 内容结束
                        }
                    }

                    if (inContent && separatorCount == 1) {
                        contentBuilder.append(line).append("\n")
                    }
                }

                var content = contentBuilder.toString().trim()

                // 如果没找到标准格式，尝试兼容旧格式
                if (content.isEmpty() && lines.size > 2) {
                    // 可能是旧格式，从第三行开始是内容
                    val contentLines = mutableListOf<String>()
                    for (i in 2 until lines.size) {
                        val line = lines[i]
                        if (!line.startsWith("ID:") && !line.contains("UUID:")) {
                            contentLines.add(line)
                        }
                    }
                    content = contentLines.joinToString("\n").trim()
                }

                // 如果没找到时间，使用当前时间
                if (createdAt.isEmpty()) {
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                        Date()
                    )
                }
                if (updatedAt.isEmpty()) {
                    updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }

                // 使用传入的ID和UUID
                val finalId = id ?: -1
                val finalUuid = uuid ?: UUID.randomUUID().toString()

                NoteItem(
                    id = finalId,
                    title = title,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    uuid = finalUuid
                )
            } catch (e: Exception) {
                Log.e("NoteItem", "解析笔记失败: ${e.message}")
                null
            }
        }
    }
}