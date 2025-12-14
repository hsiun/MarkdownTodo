package com.hsiun.markdowntodo

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TodoItem(
    val id: Int,
    var title: String,
    var isCompleted: Boolean = false,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    // 添加唯一标识符，防止同步冲突
    val uuid: String = UUID.randomUUID().toString()
) {
    fun toMarkdownLine(): String {
        val checkbox = if (isCompleted) "[x]" else "[ ]"
        return "$checkbox $title | ID: $id | UUID: $uuid | Created: $createdAt"
    }

    companion object {
        //按行解析md文件中的待办
        fun fromMarkdownLine(line: String): TodoItem? {
            return try {
                // 打印原始行以便调试
                Log.d("TodoItem", "原始行: $line")

                // 尝试新格式（带UUID）
                val checkboxMatch = Regex("\\[([ x])\\] (.+?) \\| ID: (\\d+) \\| UUID: ([^ ]+) \\| Created: (.+)")
                    .find(line)

                if (checkboxMatch != null) {
                    val (status, title, id, uuid, createdAt) = checkboxMatch.destructured
                    Log.d("TodoItem", "解析新格式成功: ID=$id, 状态字符='$status', 状态=${status == "x"}")
                    TodoItem(
                        id = id.toInt(),
                        title = title,
                        isCompleted = status.trim() == "x",  // 修复：确保状态字符不是空格
                        createdAt = createdAt,
                        uuid = uuid
                    )
                } else {
                    // 尝试更宽松的正则表达式
                    val looseMatch = Regex("\\[(.)\\] (.+?) \\|.*ID: (\\d+).*")
                        .find(line)

                    if (looseMatch != null) {
                        val (status, title, id) = looseMatch.destructured
                        Log.d("TodoItem", "解析宽松格式: ID=$id, 状态字符='$status'")
                        TodoItem(
                            id = id.toInt(),
                            title = title,
                            isCompleted = status.trim() == "x",
                            createdAt = SimpleDateFormat(
                                "yyyy-MM-dd HH:mm",
                                Locale.getDefault()
                            ).format(Date()),
                            uuid = UUID.randomUUID().toString()
                        )
                    } else {
                        // 尝试最简单的格式
                        val simpleMatch = Regex("\\[(.)\\] (.+)")
                            .find(line)

                        if (simpleMatch != null) {
                            val (status, title) = simpleMatch.destructured
                            Log.d("TodoItem", "解析简单格式: 标题='$title', 状态字符='$status'")
                            TodoItem(
                                id = -1,
                                title = title,
                                isCompleted = status.trim() == "x",
                                createdAt = SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.getDefault()
                                ).format(Date()),
                                uuid = UUID.randomUUID().toString()
                            )
                        } else {
                            Log.d("TodoItem", "无法解析任何格式的行: $line")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TodoItem", "解析失败: $line", e)
                null
            }
        }
    }
}