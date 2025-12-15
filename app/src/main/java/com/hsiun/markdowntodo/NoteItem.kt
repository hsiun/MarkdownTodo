import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// NoteItem.kt
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
                content + "\n"
        // 不再包含 ID 和 UUID
    }

    companion object {
        fun fromMarkdown(text: String, id: Int? = null, uuid: String? = null): NoteItem? {
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
                var createdAt = ""
                var updatedAt = ""
                var contentStartIndex = 0
                var separatorCount = 0

                // 查找元数据
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
                        line == "---" -> {
                            separatorCount++
                            if (separatorCount == 2) {
                                contentStartIndex = i + 1
                                break
                            }
                        }
                    }
                }

                // 提取内容（在第二个---之后的部分）
                val contentLines = mutableListOf<String>()
                if (contentStartIndex > 0 && contentStartIndex < lines.size) {
                    for (i in contentStartIndex until lines.size) {
                        contentLines.add(lines[i])
                    }
                }

                val content = contentLines.joinToString("\n").trim()

                // 如果没有解析到创建时间，使用当前时间
                val finalCreatedAt = if (createdAt.isNotEmpty()) {
                    createdAt
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }

                // 如果没有解析到更新时间，使用当前时间
                val finalUpdatedAt = if (updatedAt.isNotEmpty()) {
                    updatedAt
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }

                // 使用传入的ID和UUID，如果没有则生成新的
                val finalId = id ?: -1
                val finalUuid = uuid ?: UUID.randomUUID().toString()

                NoteItem(
                    id = finalId,
                    title = title,
                    content = content,
                    createdAt = finalCreatedAt,
                    updatedAt = finalUpdatedAt,
                    uuid = finalUuid
                )
            } catch (e: Exception) {
                Log.e("NoteItem", "解析笔记失败: ${e.message}")
                null
            }
        }
    }
}