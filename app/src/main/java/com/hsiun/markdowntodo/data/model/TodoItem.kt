package com.hsiun.markdowntodo.data.model

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 待办事项数据类
 * 
 * 表示一个待办事项，包含标题、完成状态、提醒时间等信息。
 * 支持Markdown格式的序列化和反序列化。
 * 
 * @param id 待办事项的唯一ID
 * @param title 待办事项标题
 * @param isCompleted 是否已完成
 * @param remindTime 提醒时间（时间戳，毫秒）
 * @param repeatType 重复类型（参见RepeatType枚举）
 * @param createdAt 创建时间（格式：yyyy-MM-dd HH:mm:ss）
 * @param uuid 唯一标识符（UUID格式）
 * @param originalRemindTime 原始提醒时间（用于重复提醒）
 * @param nextRemindTime 下次提醒时间（用于重复提醒）
 * @param hasReminded 是否已提醒
 * @param updatedAt 更新时间（格式：yyyy-MM-dd HH:mm:ss）
 */
data class TodoItem(
    val id: Int,
    var title: String,
    var isCompleted: Boolean = false,
    var remindTime: Long = -1L,
    var repeatType: Int = RepeatType.NONE.value,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    val uuid: String = UUID.randomUUID().toString(),
    var originalRemindTime: Long = -1L,
    var nextRemindTime: Long = -1L,
    var hasReminded: Boolean = false,
    // 新增 updatedAt 字段
    var updatedAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date())
) {

    /** 输出为标准 Markdown 任务列表格式：- [x] 标题 <!-- 元数据 -->，元数据对解析器不可见 */
    fun toMarkdownLine(): String {
        val checkbox = if (isCompleted) "[x]" else "[ ]"
        val meta = "ID: $id | UUID: $uuid | Created: $createdAt | Updated: $updatedAt | " +
                "RemindTime: $remindTime | HasReminded: $hasReminded | " +
                "RepeatType: $repeatType | NextRemindTime: $nextRemindTime | OriginalRemindTime: $originalRemindTime"
        return "- $checkbox $title <!--  $meta -->"
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
        /** 按行解析 md 中的待办，格式：- [x] 标题 <!--  ID: ... | UUID: ... | ... --> */
        fun fromMarkdownLine(line: String): TodoItem? {
            return try {
                val trimmed = line.trim()
                if (!trimmed.startsWith("- ")) return null
                val rest = trimmed.removePrefix("- ").trim()
                val commentStart = " <!-- "
                val commentEnd = " -->"
                val idx = rest.indexOf(commentStart)
                if (idx < 0) return null
                val prefix = rest.substring(0, idx).trim()
                val afterStart = rest.substring(idx + commentStart.length)
                val endIdx = afterStart.indexOf(commentEnd)
                if (endIdx < 0) return null
                val metaStr = afterStart.substring(0, endIdx).trim()

                val prefixRegex = Regex("""\[([ x])\]\s*(.*)""")
                val prefixMatch = prefixRegex.find(prefix) ?: return null
                val status = prefixMatch.groupValues[1]
                val title = prefixMatch.groupValues[2].trim()

                val meta = metaStr.split(" | ").mapNotNull { part ->
                    val colonIdx = part.indexOf(": ")
                    if (colonIdx < 0) null else part.substring(0, colonIdx).trim() to part.substring(colonIdx + 2).trim()
                }.toMap()

                val id = meta["ID"]?.toIntOrNull() ?: return null
                val uuid = meta["UUID"] ?: return null
                val createdAt = meta["Created"] ?: return null
                val updatedAt = meta["Updated"] ?: createdAt
                val remindTime = meta["RemindTime"]?.toLongOrNull() ?: -1L
                val hasReminded = meta["HasReminded"]?.toBooleanStrictOrNull() ?: false
                val repeatType = meta["RepeatType"]?.toIntOrNull() ?: 0
                val nextRemindTime = meta["NextRemindTime"]?.toLongOrNull() ?: -1L
                val originalRemindTime = meta["OriginalRemindTime"]?.toLongOrNull() ?: -1L

                Log.d("TodoItem", "解析成功: ID=$id, 状态='$status', 标题=$title")
                TodoItem(
                    id = id,
                    title = title,
                    isCompleted = status.trim() == "x",
                    createdAt = createdAt,
                    uuid = uuid,
                    remindTime = remindTime,
                    hasReminded = hasReminded,
                    repeatType = repeatType,
                    nextRemindTime = nextRemindTime,
                    originalRemindTime = originalRemindTime
                ).apply {
                    this.updatedAt = updatedAt
                }
            } catch (e: Exception) {
                Log.e("TodoItem", "解析失败: $line", e)
                null
            }
        }
    }
}