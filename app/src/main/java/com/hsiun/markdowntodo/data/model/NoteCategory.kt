package com.hsiun.markdowntodo.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// 笔记分类数据结构
data class NoteCategory(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var folderName: String = "", // 对应文件夹名称
    var noteCount: Int = 0,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    var isDefault: Boolean = false,
    var isSelected: Boolean = false
) {
    fun getDisplayName(): String {
        return if (isDefault) {
            "默认笔记 ($noteCount)"
        } else {
            "$name ($noteCount)"
        }
    }

    fun getDisplayText(): String {
        return if (isDefault) "默认笔记" else name
    }
}
