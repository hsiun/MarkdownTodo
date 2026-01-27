package com.hsiun.markdowntodo.data.model

// 重复类型枚举
enum class RepeatType(val value: Int, val displayName: String) {
    NONE(0, "不重复"),
    WEEKLY(1, "每周"),
    BIWEEKLY(2, "每半月"),
    MONTHLY(3, "每月"),
    QUARTERLY(4, "每季");

    companion object {
        fun fromValue(value: Int): RepeatType {
            return values().find { it.value == value } ?: NONE
        }
    }
}