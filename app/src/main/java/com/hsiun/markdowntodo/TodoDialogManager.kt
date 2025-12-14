// TodoDialogManager.kt - 修改时间选择部分
package com.hsiun.markdowntodo

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TodoDialogManager(private val context: Context) {

    interface TodoDialogListener {
        fun onAddTodo(title: String, setReminder: Boolean, remindTime: Long)
        fun onUpdateTodo(id: Int, newTitle: String, setReminder: Boolean, remindTime: Long)
        fun onCancel()
    }

    private var fragmentManager: FragmentManager? = null

    fun setFragmentManager(fm: FragmentManager) {
        this.fragmentManager = fm
    }

    fun showAddTodoDialog(listener: TodoDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_todo_list, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitleText)
        val inputEditText = dialogView.findViewById<EditText>(R.id.inputEditText)
        val completeButton = dialogView.findViewById<Button>(R.id.completeButton)
        val reminderCheckBox = dialogView.findViewById<CheckBox>(R.id.reminderCheckBox)
        val reminderContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.reminderContainer)
        val reminderTimeText = dialogView.findViewById<TextView>(R.id.reminderTimeText)
        val clearReminderButton = dialogView.findViewById<Button>(R.id.clearReminderButton)

        var selectedRemindTime: Long = -1L

        dialogTitle.text = "新增待办"
        inputEditText.text?.clear()

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasContent = s?.toString()?.trim()?.isNotEmpty() == true
                completeButton.isEnabled = hasContent
                completeButton.alpha = if (hasContent) 1.0f else 0.5f
            }
        }

        inputEditText.addTextChangedListener(textWatcher)

        // 设置提醒复选框监听
        reminderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            reminderContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked && selectedRemindTime <= 0) {
                // 不自动弹出时间选择器，让用户手动点击选择
            } else if (!isChecked) {
                selectedRemindTime = -1L
                reminderTimeText.text = "未选择"
            }
        }

        // 设置提醒时间文本点击监听
        reminderTimeText.setOnClickListener {
            if (fragmentManager != null) {
                showBetterTimePicker(selectedRemindTime) { timeInMillis ->
                    selectedRemindTime = timeInMillis
                    reminderTimeText.text = formatDateTime(timeInMillis)
                    reminderCheckBox.isChecked = true
                    reminderContainer.visibility = android.view.View.VISIBLE
                    clearReminderButton.visibility = android.view.View.VISIBLE
                }
            } else {
                Toast.makeText(context, "请先设置FragmentManager", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置清除提醒按钮监听
        clearReminderButton.setOnClickListener {
            selectedRemindTime = -1L
            reminderTimeText.text = "未选择"
            reminderCheckBox.isChecked = false
            reminderContainer.visibility = android.view.View.GONE
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                if (title.isNotEmpty()) {
                    listener.onAddTodo(title, reminderCheckBox.isChecked, selectedRemindTime)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        completeButton.setOnClickListener {
            val title = inputEditText.text.toString().trim()
            if (title.isNotEmpty()) {
                listener.onAddTodo(title, reminderCheckBox.isChecked, selectedRemindTime)
                dialog.dismiss()
            }
        }

        dialog.setOnCancelListener {
            listener.onCancel()
        }

        completeButton.isEnabled = false
        completeButton.alpha = 0.5f

        dialog.show()
        inputEditText.requestFocus()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun showEditTodoDialog(todo: TodoItem, listener: TodoDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_todo_list, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitleText)
        val inputEditText = dialogView.findViewById<EditText>(R.id.inputEditText)
        val todoIdInput = dialogView.findViewById<EditText>(R.id.todoIdInput)
        val completeButton = dialogView.findViewById<Button>(R.id.completeButton)
        val reminderCheckBox = dialogView.findViewById<CheckBox>(R.id.reminderCheckBox)
        val reminderContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.reminderContainer)
        val reminderTimeText = dialogView.findViewById<TextView>(R.id.reminderTimeText)
        val clearReminderButton = dialogView.findViewById<Button>(R.id.clearReminderButton)

        var selectedRemindTime = todo.remindTime

        dialogTitle.text = "编辑待办"
        inputEditText.setText(todo.title)
        inputEditText.setSelection(todo.title.length)
        todoIdInput.setText(todo.id.toString())

        // 设置提醒相关UI
        val hasReminder = todo.remindTime > 0
        reminderCheckBox.isChecked = hasReminder
        reminderContainer.visibility = if (hasReminder) android.view.View.VISIBLE else android.view.View.GONE

        if (hasReminder) {
            reminderTimeText.text = formatDateTime(todo.remindTime)
            clearReminderButton.visibility = android.view.View.VISIBLE
        } else {
            reminderTimeText.text = "未选择"
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasContent = s?.toString()?.trim()?.isNotEmpty() == true
                completeButton.isEnabled = hasContent
                completeButton.alpha = if (hasContent) 1.0f else 0.5f
            }
        }

        inputEditText.addTextChangedListener(textWatcher)

        // 设置提醒复选框监听
        reminderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            reminderContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked && selectedRemindTime <= 0) {
                // 不自动弹出时间选择器，让用户手动点击选择
            } else if (!isChecked) {
                selectedRemindTime = -1L
                reminderTimeText.text = "未选择"
                clearReminderButton.visibility = android.view.View.GONE
            }
        }

        // 设置提醒时间文本点击监听
        reminderTimeText.setOnClickListener {
            if (fragmentManager != null) {
                showBetterTimePicker(selectedRemindTime) { timeInMillis ->
                    selectedRemindTime = timeInMillis
                    reminderTimeText.text = formatDateTime(timeInMillis)
                    reminderCheckBox.isChecked = true
                    reminderContainer.visibility = android.view.View.VISIBLE
                    clearReminderButton.visibility = android.view.View.VISIBLE
                }
            } else {
                Toast.makeText(context, "请先设置FragmentManager", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置清除提醒按钮监听
        clearReminderButton.setOnClickListener {
            selectedRemindTime = -1L
            reminderTimeText.text = "未选择"
            reminderCheckBox.isChecked = false
            reminderContainer.visibility = android.view.View.GONE
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                val todoId = todoIdInput.text.toString().toIntOrNull()
                if (title.isNotEmpty() && todoId != null) {
                    listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked, selectedRemindTime)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        completeButton.setOnClickListener {
            val title = inputEditText.text.toString().trim()
            val todoId = todoIdInput.text.toString().toIntOrNull()
            if (title.isNotEmpty() && todoId != null) {
                listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked, selectedRemindTime)
                dialog.dismiss()
            }
        }

        dialog.setOnCancelListener {
            listener.onCancel()
        }

        completeButton.isEnabled = true
        completeButton.alpha = 1.0f

        dialog.show()
        inputEditText.requestFocus()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    // TodoDialogManager.kt - 替换 showBetterTimePicker 方法
// TodoDialogManager.kt - 修改 showBetterTimePicker 方法
    private fun showBetterTimePicker(
        currentTime: Long,
        onTimeSelected: (Long) -> Unit
    ) {
        val initialTime = if (currentTime > 0) currentTime else {
            // 修改这里：将默认设置为当前时间，而不是当前时间+1小时
            Calendar.getInstance().apply {
                // 移除原来的+1小时设置
                // add(Calendar.HOUR_OF_DAY, 1)
                // 设置为整分钟，这样更友好
                set(Calendar.SECOND, 0)
                // 如果当前分钟接近60，增加一小时并将分钟设为0
                if (get(Calendar.MINUTE) >= 58) {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                }
            }.timeInMillis
        }

        val timePickerDialog = OneRowTimePickerDialog.newInstance(
            initialTime = initialTime,
            minTime = System.currentTimeMillis()
        )

        timePickerDialog.listener = object : OneRowTimePickerDialog.TimePickerListener {
            override fun onTimeSelected(timeInMillis: Long) {
                onTimeSelected(timeInMillis)
            }

            override fun onCancelled() {
                // 用户取消选择，不做任何操作
            }
        }

        fragmentManager?.let {
            timePickerDialog.show(it, "OneRowTimePickerDialog")
        }
    }

    // 修改 formatDateTime 方法，使其显示秒
    private fun formatDateTime(timeInMillis: Long): String {
        if (timeInMillis <= 0) return "未选择"

        val date = Date(timeInMillis)
        val now = Date()

        // 检查是否是今天
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return if (dateFormat.format(date) == dateFormat.format(now)) {
            "今天 ${timeFormat.format(date)}"
        } else {
            val calendarNow = Calendar.getInstance()
            val calendarDate = Calendar.getInstance().apply { time = date }

            // 检查是否是明天
            calendarNow.add(Calendar.DAY_OF_MONTH, 1)
            if (dateFormat.format(calendarNow.time) == dateFormat.format(date)) {
                "明天 ${timeFormat.format(date)}"
            } else {
                // 检查是否是后天
                calendarNow.add(Calendar.DAY_OF_MONTH, 1)
                if (dateFormat.format(calendarNow.time) == dateFormat.format(date)) {
                    "后天 ${timeFormat.format(date)}"
                } else {
                    val fullFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    fullFormat.format(date)
                }
            }
        }
    }
}