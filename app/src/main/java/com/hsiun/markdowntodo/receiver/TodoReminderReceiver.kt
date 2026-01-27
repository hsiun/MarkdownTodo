package com.hsiun.markdowntodo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hsiun.markdowntodo.data.model.RepeatType
import com.hsiun.markdowntodo.data.model.TodoItem
import com.hsiun.markdowntodo.ui.activity.MainActivity
import com.hsiun.markdowntodo.util.ReminderScheduler

class TodoReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TodoReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到提醒广播")

        val todoId = intent.getIntExtra("todo_id", -1)
        val todoTitle = intent.getStringExtra("todo_title") ?: "待办事项"
        val todoUuid = intent.getStringExtra("todo_uuid") ?: ""
        val remindTime = intent.getLongExtra("remind_time", 0L)
        val repeatType = intent.getIntExtra("repeat_type", RepeatType.NONE.value)

        if (todoId == -1) {
            Log.w(TAG, "无效的待办ID")
            return
        }

        // 获取MainActivity实例
        val mainActivity = MainActivity.Companion.getInstance()
        if (mainActivity != null) {
            // 如果应用正在运行，通过MainActivity处理
            val todo = mainActivity.todoManager.getTodoById(todoId)
            if (todo != null) {
                // 触发提醒通知
                ReminderScheduler.Companion.triggerReminder(context, todo)
                // 处理重复逻辑
                mainActivity.todoManager.handleRepeatedReminder(todoId)
                Log.d(TAG, "已处理提醒: ${todo.title}, 重复类型=${RepeatType.Companion.fromValue(repeatType).displayName}")
            }
        } else {
            // 如果应用不在运行，创建临时TodoItem并发送通知
            val tempTodo = TodoItem(
                id = todoId,
                title = todoTitle,
                uuid = todoUuid,
                remindTime = remindTime,
                repeatType = repeatType
            )
            ReminderScheduler.Companion.triggerReminder(context, tempTodo)

            // 下次应用启动时会从文件加载并更新状态
            Log.d(TAG, "应用未运行，发送提醒通知: $todoTitle")
        }
    }
}