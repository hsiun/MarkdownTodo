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
import com.google.android.material.button.MaterialButton

class TodoDialogManager(private val context: Context) {

    interface TodoDialogListener {
        fun onAddTodo(title: String, setReminder: Boolean)
        fun onUpdateTodo(id: Int, newTitle: String, setReminder: Boolean)
        fun onCancel()
    }

    fun showAddTodoDialog(listener: TodoDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_todo_list, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitleText)
        val inputEditText = dialogView.findViewById<EditText>(R.id.inputEditText)
        val completeButton = dialogView.findViewById<Button>(R.id.completeButton)
        val reminderCheckBox = dialogView.findViewById<CheckBox>(R.id.reminderCheckBox)

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

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                if (title.isNotEmpty()) {
                    listener.onAddTodo(title, reminderCheckBox.isChecked)
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
                listener.onAddTodo(title, reminderCheckBox.isChecked)
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

        dialogTitle.text = "编辑待办"
        inputEditText.setText(todo.title)
        inputEditText.setSelection(todo.title.length)
        todoIdInput.setText(todo.id.toString())

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

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                val todoId = todoIdInput.text.toString().toIntOrNull()
                if (title.isNotEmpty() && todoId != null) {
                    listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked)
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
                listener.onUpdateTodo(todoId, title, reminderCheckBox.isChecked)
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
}