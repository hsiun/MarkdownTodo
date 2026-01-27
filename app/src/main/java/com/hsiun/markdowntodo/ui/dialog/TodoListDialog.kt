package com.hsiun.markdowntodo.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.hsiun.markdowntodo.R

class TodoListDialog : DialogFragment() {

    interface CreateTodoListListener {
        fun onTodoListCreated(name: String)
        fun onCancel()
    }

    var listener: CreateTodoListListener? = null
    private lateinit var nameEditText: EditText
    private lateinit var createButton: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_todo_list, null)

        nameEditText = view.findViewById(R.id.listNameEditText)
        createButton = view.findViewById(R.id.createButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)

        // 设置文本监听器
        nameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasContent = s?.toString()?.trim()?.isNotEmpty() == true
                createButton.isEnabled = hasContent
                createButton.alpha = if (hasContent) 1.0f else 0.5f
            }
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("新建待办列表")
            .setView(view)
            .create()

        createButton.setOnClickListener {
            val name = nameEditText.text.toString().trim()
            if (name.isNotEmpty()) {
                listener?.onTodoListCreated(name)
                dismiss()
            }
        }

        cancelButton.setOnClickListener {
            listener?.onCancel()
            dismiss()
        }

        return dialog
    }
}