package com.hsiun.markdowntodo.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hsiun.markdowntodo.R
import com.hsiun.markdowntodo.data.model.RepeatType
import com.hsiun.markdowntodo.data.model.TodoItem
import com.hsiun.markdowntodo.receiver.TodoReminderReceiver
import com.hsiun.markdowntodo.ui.activity.MainActivity
import java.text.SimpleDateFormat
import java.util.Date

class ReminderScheduler {

    companion object {
        private const val TAG = "ReminderScheduler"
        private const val CHANNEL_ID = "todo_reminder_channel"
        private const val CHANNEL_NAME = "待办提醒"
        private const val NOTIFICATION_ID_PREFIX = 1000

        // 创建通知渠道
        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "待办事项提醒通知"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    setShowBadge(true)
                }

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        // 检查通知权限
        private fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                // Android 13 以下版本不需要运行时权限
                true
            }
        }

        // 调度提醒
        fun scheduleReminder(context: Context, todo: TodoItem) {
            val timeToSchedule = if (todo.nextRemindTime > 0) todo.nextRemindTime else todo.remindTime
            if (timeToSchedule <= 0 || todo.isCompleted) {
                return
            }

            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, TodoReminderReceiver::class.java).apply {
                    putExtra("todo_id", todo.id)
                    putExtra("todo_title", todo.title)
                    putExtra("todo_uuid", todo.uuid)
                    putExtra("remind_time", timeToSchedule)
                    putExtra("repeat_type", todo.repeatType)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    todo.id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 使用精确闹钟
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ 需要请求精确闹钟权限
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            timeToSchedule,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            timeToSchedule,
                            pendingIntent
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        timeToSchedule,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        timeToSchedule,
                        pendingIntent
                    )
                }

                Log.d(TAG, "已调度提醒: ${todo.title} at ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                        Date(timeToSchedule)
                )}, 重复类型=${RepeatType.Companion.fromValue(todo.repeatType).displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "调度提醒失败", e)
            }
        }

        // 取消提醒
        fun cancelReminder(context: Context, todo: TodoItem) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, TodoReminderReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    todo.id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()

                Log.d(TAG, "已取消提醒: ${todo.title}")
            } catch (e: Exception) {
                Log.e(TAG, "取消提醒失败", e)
            }
        }

        // 触发提醒（发送通知）
        fun triggerReminder(context: Context, todo: TodoItem) {
            try {
                // 检查通知权限
                if (!hasNotificationPermission(context)) {
                    Log.w(TAG, "没有通知权限，无法发送提醒: ${todo.title}")
                    // 可以在这里尝试其他提醒方式
                    return
                }

                createNotificationChannel(context)

                // 创建点击通知后跳转到应用的Intent
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("open_todo_id", todo.id)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    todo.id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 构建通知
                val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("待办提醒")
                    .setContentText("${todo.title}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)

                // 添加重复信息
                if (todo.repeatType != RepeatType.NONE.value) {
                    notificationBuilder.setContentText("${todo.title} (${todo.getRepeatTypeName()})")
                }

                val notification = notificationBuilder.build()

                // 显示通知 - 使用 try-catch 捕获权限异常
                with(NotificationManagerCompat.from(context)) {
                    try {
                        notify(NOTIFICATION_ID_PREFIX + todo.id, notification)
                        Log.d(TAG, "已触发提醒通知: ${todo.title}")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "发送通知权限被拒绝", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "发送通知失败", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "触发提醒通知失败", e)
            }
        }

        // 重新调度所有未触发的提醒
        fun rescheduleAllReminders(context: Context, todos: List<TodoItem>) {
            val now = System.currentTimeMillis()
            todos.forEach { todo ->
                if ((todo.remindTime > 0 || todo.nextRemindTime > 0) &&
                    !todo.isCompleted &&
                    !todo.hasReminded &&
                    (if (todo.nextRemindTime > 0) todo.nextRemindTime else todo.remindTime) > now) {
                    scheduleReminder(context, todo)
                }
            }
        }
    }
}