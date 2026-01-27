package com.hsiun.markdowntodo.ui.dialog

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.hsiun.markdowntodo.R
import com.hsiun.markdowntodo.data.model.RepeatType
import com.hsiun.markdowntodo.data.model.TodoItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TodoDialogManager(private val context: Context) {

    interface TodoDialogListener {
        fun onAddTodo(title: String, setReminder: Boolean, remindTime: Long, repeatType: Int)
        fun onUpdateTodo(id: Int, newTitle: String, setReminder: Boolean, remindTime: Long, repeatType: Int)
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
        val reminderContainer = dialogView.findViewById<LinearLayout>(R.id.reminderContainer)
        val reminderTimeText = dialogView.findViewById<TextView>(R.id.reminderTimeText)
        val clearReminderButton = dialogView.findViewById<Button>(R.id.clearReminderButton)
        val repeatSpinner = dialogView.findViewById<Spinner>(R.id.repeatSpinner)

        var selectedRemindTime: Long = -1L
        var selectedRepeatType = RepeatType.NONE.value

        dialogTitle.text = "新增待办"
        inputEditText.text?.clear()

        // 设置重复下拉框
        val repeatAdapter = ArrayAdapter.createFromResource(
            context,
            R.array.repeat_options,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        repeatSpinner.adapter = repeatAdapter

        // 设置重复下拉框选择监听
        repeatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRepeatType = when (position) {
                    0 -> RepeatType.NONE.value
                    1 -> RepeatType.WEEKLY.value
                    2 -> RepeatType.BIWEEKLY.value
                    3 -> RepeatType.MONTHLY.value
                    4 -> RepeatType.QUARTERLY.value
                    else -> RepeatType.NONE.value
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedRepeatType = RepeatType.NONE.value
            }
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
            reminderContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && selectedRemindTime <= 0) {
                // 不自动弹出时间选择器，让用户手动点击选择
            } else if (!isChecked) {
                selectedRemindTime = -1L
                reminderTimeText.text = "未选择"
                // 重置重复类型
                repeatSpinner.setSelection(0)
                selectedRepeatType = RepeatType.NONE.value
            }
        }

        // 设置提醒时间文本点击监听
        reminderTimeText.setOnClickListener {
            if (fragmentManager != null) {
                showBetterTimePicker(selectedRemindTime) { timeInMillis ->
                    selectedRemindTime = timeInMillis
                    reminderTimeText.text = formatDateTime(timeInMillis)
                    reminderCheckBox.isChecked = true
                    reminderContainer.visibility = View.VISIBLE
                    clearReminderButton.visibility = View.VISIBLE
                }
            } else {
                Toast.makeText(context, "请先设置FragmentManager", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置清除提醒按钮监听
        clearReminderButton.setOnClickListener {
            selectedRemindTime = -1L
            selectedRepeatType = RepeatType.NONE.value
            reminderTimeText.text = "未选择"
            reminderCheckBox.isChecked = false
            reminderContainer.visibility = View.GONE
            repeatSpinner.setSelection(0)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                if (title.isNotEmpty()) {
                    listener.onAddTodo(title, reminderCheckBox.isChecked, selectedRemindTime, selectedRepeatType)
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
                listener.onAddTodo(title, reminderCheckBox.isChecked, selectedRemindTime, selectedRepeatType)
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
        val reminderContainer = dialogView.findViewById<LinearLayout>(R.id.reminderContainer)
        val reminderTimeText = dialogView.findViewById<TextView>(R.id.reminderTimeText)
        val clearReminderButton = dialogView.findViewById<Button>(R.id.clearReminderButton)
        val repeatSpinner = dialogView.findViewById<Spinner>(R.id.repeatSpinner)

        var selectedRemindTime = todo.remindTime
        var selectedRepeatType = todo.repeatType

        dialogTitle.text = "编辑待办"
        inputEditText.setText(todo.title)
        inputEditText.setSelection(todo.title.length)
        todoIdInput.setText(todo.id.toString())

        // 设置重复下拉框
        val repeatAdapter = ArrayAdapter.createFromResource(
            context,
            R.array.repeat_options,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        repeatSpinner.adapter = repeatAdapter

        // 设置重复下拉框选择监听
        repeatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRepeatType = when (position) {
                    0 -> RepeatType.NONE.value
                    1 -> RepeatType.WEEKLY.value
                    2 -> RepeatType.BIWEEKLY.value
                    3 -> RepeatType.MONTHLY.value
                    4 -> RepeatType.QUARTERLY.value
                    else -> RepeatType.NONE.value
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedRepeatType = RepeatType.NONE.value
            }
        }

        // 设置提醒相关UI
        val hasReminder = todo.remindTime > 0 || todo.originalRemindTime > 0
        val currentRemindTime = if (todo.remindTime > 0) todo.remindTime else todo.originalRemindTime

        reminderCheckBox.isChecked = hasReminder
        reminderContainer.visibility = if (hasReminder) View.VISIBLE else View.GONE

        if (hasReminder && currentRemindTime > 0) {
            reminderTimeText.text = formatDateTime(currentRemindTime)
            clearReminderButton.visibility = View.VISIBLE

            // 设置重复类型选择
            repeatSpinner.setSelection(when (todo.repeatType) {
                RepeatType.WEEKLY.value -> 1
                RepeatType.BIWEEKLY.value -> 2
                RepeatType.MONTHLY.value -> 3
                RepeatType.QUARTERLY.value -> 4
                else -> 0
            })
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
            reminderContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && selectedRemindTime <= 0) {
                // 不自动弹出时间选择器，让用户手动点击选择
            } else if (!isChecked) {
                selectedRemindTime = -1L
                selectedRepeatType = RepeatType.NONE.value
                reminderTimeText.text = "未选择"
                clearReminderButton.visibility = View.GONE
                repeatSpinner.setSelection(0)
            }
        }

        // 设置提醒时间文本点击监听
        reminderTimeText.setOnClickListener {
            if (fragmentManager != null) {
                val currentTime = if (selectedRemindTime > 0) selectedRemindTime else currentRemindTime
                showBetterTimePicker(currentTime) { timeInMillis ->
                    selectedRemindTime = timeInMillis
                    reminderTimeText.text = formatDateTime(timeInMillis)
                    reminderCheckBox.isChecked = true
                    reminderContainer.visibility = View.VISIBLE
                    clearReminderButton.visibility = View.VISIBLE
                }
            } else {
                Toast.makeText(context, "请先设置FragmentManager", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置清除提醒按钮监听
        clearReminderButton.setOnClickListener {
            selectedRemindTime = -1L
            selectedRepeatType = RepeatType.NONE.value
            reminderTimeText.text = "未选择"
            reminderCheckBox.isChecked = false
            reminderContainer.visibility = View.GONE
            repeatSpinner.setSelection(0)
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
                    listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked, selectedRemindTime, selectedRepeatType)
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
                listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked, selectedRemindTime, selectedRepeatType)
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

    private fun showBetterTimePicker(
        currentTime: Long,
        onTimeSelected: (Long) -> Unit
    ) {
        val initialTime = if (currentTime > 0) currentTime else {
            // 如果当前没有设置时间，默认设置为当前时间+1小时
            Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
        }

        val timePickerDialog = OneRowTimePickerDialog.Companion.newInstance(
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