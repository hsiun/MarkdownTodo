package com.hsiun.markdowntodo

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

    // 修改 toMarkdownLine 方法，添加更新时间
    fun toMarkdownLine(): String {
        val checkbox = if (isCompleted) "[x]" else "[ ]"
        return "$checkbox $title | ID: $id | UUID: $uuid | Created: $createdAt | Updated: $updatedAt | " +
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

                // 尝试最新格式（带更新时间、重复类型和下次提醒时间）
                val latestPattern = Regex("""\[([ x])\] (.+?) \| ID: (\d+) \| UUID: ([^ ]+) \| Created: (.+?) \| Updated: (.+?) \| """ +
                        """RemindTime: (-?\d+) \| HasReminded: (true|false) \| """ +
                        """RepeatType: (\d+) \| NextRemindTime: (-?\d+) \| """ +
                        """OriginalRemindTime: (-?\d+)""")
                val latestMatch = latestPattern.find(line)

                if (latestMatch != null) {
                    // 使用groupValues而不是解构声明，因为解构最多只支持10个组件
                    val groups = latestMatch.groupValues
                    if (groups.size >= 12) { // groupValues[0]是整个匹配，后续是捕获组
                        val status = groups[1]
                        val title = groups[2]
                        val id = groups[3]
                        val uuid = groups[4]
                        val createdAt = groups[5]
                        val updatedAt = groups[6]
                        val remindTimeStr = groups[7]
                        val hasRemindedStr = groups[8]
                        val repeatTypeStr = groups[9]
                        val nextRemindTimeStr = groups[10]
                        val originalRemindTimeStr = groups[11]

                        Log.d("TodoItem", "解析最新格式成功: ID=$id, 状态字符='$status', 状态=${status == "x"}")
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
                        ).apply {
                            this.updatedAt = updatedAt
                        }
                    }
                }

                // 尝试旧的新格式（没有UpdatedAt字段）
                val newPattern = Regex("""\[([ x])\] (.+?) \| ID: (\d+) \| UUID: ([^ ]+) \| Created: (.+?) \| """ +
                        """RemindTime: (-?\d+) \| HasReminded: (true|false) \| """ +
                        """RepeatType: (\d+) \| NextRemindTime: (-?\d+) \| """ +
                        """OriginalRemindTime: (-?\d+)""")
                val newMatch = newPattern.find(line)

                if (newMatch != null) {
                    // 使用解构声明，因为只有10个组件
                    val (status, title, id, uuid, createdAt, remindTimeStr, hasRemindedStr,
                        repeatTypeStr, nextRemindTimeStr, originalRemindTimeStr) = newMatch.destructured
                    Log.d("TodoItem", "解析旧的新格式成功: ID=$id, 状态字符='$status', 状态=${status == "x"}")
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
                    ).apply {
                        // 对于旧格式，将updatedAt设置为createdAt，保持向后兼容
                        this.updatedAt = createdAt
                    }
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