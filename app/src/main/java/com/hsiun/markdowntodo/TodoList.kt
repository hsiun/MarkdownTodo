package com.hsiun.markdowntodo

import java.text.SimpleDateFormat
import java.util.*

// 待办列表数据结构
data class TodoList(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var fileName: String = "",
    var todoCount: Int = 0,
    var activeCount: Int = 0,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    var isDefault: Boolean = false,
    var isSelected: Boolean = false
) {
    fun getDisplayName(): String {
        return if (isDefault) {
            // 默认待办显示未完成数量
            "默认待办 ($activeCount)"
        } else {
            // 其他列表显示总数
            "$name ($todoCount)"
        }
    }

    fun getDisplayText(): String {
        return if (isDefault) "默认待办" else name
    }
}

