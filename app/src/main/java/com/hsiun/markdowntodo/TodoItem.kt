// TodoItem.kt
package com.hsiun.markdowntodo

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
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
    val uuid: String = UUID.randomUUID().toString(),
    // 新增：提醒时间（时间戳，-1表示未设置）
    var remindTime: Long = -1L,
    // 新增：是否已提醒过
    var hasReminded: Boolean = false,
    // 新增：重复类型
    var repeatType: Int = RepeatType.NONE.value,
    // 新增：下次提醒时间（用于重复提醒）
    var nextRemindTime: Long = -1L,
    // 新增：原始提醒时间（用于重复计算）
    var originalRemindTime: Long = -1L
) {
    fun toMarkdownLine(): String {
        val checkbox = if (isCompleted) "[x]" else "[ ]"
        return "$checkbox $title | ID: $id | UUID: $uuid | Created: $createdAt | " +
                "RemindTime: $remindTime | HasReminded: $hasReminded | " +
                "RepeatType: $repeatType | NextRemindTime: $nextRemindTime | " +
                "OriginalRemindTime: $originalRemindTime"
    }

    // 新增：格式化时间显示（移出companion object）
    fun formatRemindTime(): String {
        val timeToFormat = if (remindTime > 0) remindTime else originalRemindTime
        if (timeToFormat <= 0) return ""
        val date = Date(timeToFormat)
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return format.format(date)
    }

    // 新增：检查是否需要提醒（移出companion object）
    fun shouldRemind(): Boolean {
        val timeToCheck = if (nextRemindTime > 0) nextRemindTime else remindTime
        if (timeToCheck <= 0) return false

        val now = System.currentTimeMillis()
        return !hasReminded && !isCompleted && now >= timeToCheck
    }

    // 新增：获取提醒状态的描述（移出companion object）
    fun getReminderStatus(): String {
        if (remindTime <= 0) return ""
        if (hasReminded && repeatType == RepeatType.NONE.value) return "已提醒"

        val timeToCheck = if (nextRemindTime > 0) nextRemindTime else remindTime
        if (timeToCheck <= 0) return ""

        val now = System.currentTimeMillis()
        return if (now >= timeToCheck) {
            "待提醒"
        } else {
            // 显示友好的时间格式
            val date = Date(timeToCheck)
            val today = Date()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            return if (dateFormat.format(date) == dateFormat.format(today)) {
                "今天 ${timeFormat.format(date)}"
            } else {
                val calendarToday = Calendar.getInstance()
                val calendarDate = Calendar.getInstance().apply { time = date }

                // 检查是否是明天
                calendarToday.add(Calendar.DAY_OF_MONTH, 1)
                if (dateFormat.format(calendarToday.time) == dateFormat.format(date)) {
                    "明天 ${timeFormat.format(date)}"
                } else {
                    formatRemindTime()
                }
            }
        }
    }

    // 新增：获取重复类型显示名称
    fun getRepeatTypeName(): String {
        return when (repeatType) {
            RepeatType.WEEKLY.value -> "每周"
            RepeatType.BIWEEKLY.value -> "每半月"
            RepeatType.MONTHLY.value -> "每月"
            RepeatType.QUARTERLY.value -> "每季"
            else -> "不重复"
        }
    }

    // 新增：计算下一次提醒时间
    fun calculateNextRemindTime(): Long {
        if (repeatType == RepeatType.NONE.value || originalRemindTime <= 0) {
            return -1L
        }

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = if (nextRemindTime > 0) nextRemindTime else originalRemindTime

        when (RepeatType.fromValue(repeatType)) {
            RepeatType.WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            RepeatType.BIWEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, 2)
            RepeatType.MONTHLY -> calendar.add(Calendar.MONTH, 1)
            RepeatType.QUARTERLY -> calendar.add(Calendar.MONTH, 3)
            else -> return -1L
        }

        return calendar.timeInMillis
    }

    companion object {
        //按行解析md文件中的待办
        fun fromMarkdownLine(line: String): TodoItem? {
            return try {
                // 打印原始行以便调试
                Log.d("TodoItem", "原始行: $line")

                // 尝试最新格式（带重复类型和下次提醒时间）
                val newPattern = Regex("""\[([ x])\] (.+?) \| ID: (\d+) \| UUID: ([^ ]+) \| Created: (.+?) \| """ +
                        """RemindTime: (-?\d+) \| HasReminded: (true|false) \| """ +
                        """RepeatType: (\d+) \| NextRemindTime: (-?\d+) \| """ +
                        """OriginalRemindTime: (-?\d+)""")
                val newMatch = newPattern.find(line)

                if (newMatch != null) {
                    val (status, title, id, uuid, createdAt, remindTimeStr, hasRemindedStr,
                        repeatTypeStr, nextRemindTimeStr, originalRemindTimeStr) = newMatch.destructured
                    Log.d("TodoItem", "解析新格式成功: ID=$id, 状态字符='$status', 状态=${status == "x"}")
                    return TodoItem(
                        id = id.toInt(),
                        title = title,
                        isCompleted = status.trim() == "x",
                        createdAt = createdAt,
                        uuid = uuid,
                        remindTime = remindTimeStr.toLong(),
                        hasReminded = hasRemindedStr.toBoolean(),
                        repeatType = repeatTypeStr.toInt(),
                        nextRemindTime = nextRemindTimeStr.toLong(),
                        originalRemindTime = originalRemindTimeStr.toLong()
                    )
                }

                // 尝试旧格式（不带重复相关字段）
                val oldPattern = Regex("\\[([ x])\\] (.+?) \\| ID: (\\d+) \\| UUID: ([^ ]+) \\| Created: (.+)")
                val oldMatch = oldPattern.find(line)

                if (oldMatch != null) {
                    val (status, title, id, uuid, createdAt) = oldMatch.destructured
                    Log.d("TodoItem", "解析旧格式: ID=$id, 状态字符='$status'")
                    return TodoItem(
                        id = id.toInt(),
                        title = title,
                        isCompleted = status.trim() == "x",
                        createdAt = createdAt,
                        uuid = uuid,
                        remindTime = -1L,
                        hasReminded = false,
                        repeatType = RepeatType.NONE.value,
                        nextRemindTime = -1L,
                        originalRemindTime = -1L
                    )
                }

                // 尝试最简单的格式
                val simplePattern = Regex("\\[(.)\\] (.+)")
                val simpleMatch = simplePattern.find(line)

                if (simpleMatch != null) {
                    val (status, title) = simpleMatch.destructured
                    Log.d("TodoItem", "解析简单格式: 标题='$title', 状态字符='$status'")
                    return TodoItem(
                        id = -1,
                        title = title,
                        isCompleted = status.trim() == "x",
                        createdAt = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date()),
                        uuid = UUID.randomUUID().toString(),
                        remindTime = -1L,
                        hasReminded = false,
                        repeatType = RepeatType.NONE.value,
                        nextRemindTime = -1L,
                        originalRemindTime = -1L
                    )
                }

                Log.d("TodoItem", "无法解析任何格式的行: $line")
                null
            } catch (e: Exception) {
                Log.e("TodoItem", "解析失败: $line", e)
                null
            }
        }
    }
}