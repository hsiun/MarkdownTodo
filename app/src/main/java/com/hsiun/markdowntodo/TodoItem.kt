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
        "yyyy-MM-dd HH:mm",
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
        fun fromMarkdownLine(line: String): TodoItem? {
            return try {
                // 尝试新格式（带UUID）
                val checkboxMatch = Regex("\\[([ x])\\] (.+?) \\| ID: (\\d+) \\| UUID: ([^ ]+) \\| Created: (.+)")
                    .find(line)
                if (checkboxMatch != null) {
                    val (status, title, id, uuid, createdAt) = checkboxMatch.destructured
                    Log.d("TodoItem", "解析新格式: ID=$id, UUID=$uuid")
                    TodoItem(
                        id = id.toInt(),
                        title = title,
                        isCompleted = status == "x",
                        createdAt = createdAt,
                        uuid = uuid
                    )
                } else {
                    // 尝试旧格式（不带UUID）
                    val oldCheckboxMatch = Regex("\\[([ x])\\] (.+?) \\| ID: (\\d+) \\| Created: (.+)")
                        .find(line)
                    if (oldCheckboxMatch != null) {
                        val (status, title, id, createdAt) = oldCheckboxMatch.destructured
                        Log.d("TodoItem", "解析旧格式: ID=$id")
                        TodoItem(
                            id = id.toInt(),
                            title = title,
                            isCompleted = status == "x",
                            createdAt = createdAt,
                            uuid = UUID.randomUUID().toString()
                        )
                    } else {
                        Log.d("TodoItem", "无法解析行: $line")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("TodoItem", "解析失败: $line", e)
                null
            }
        }
    }
}