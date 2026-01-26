package com.hsiun.markdowntodo

import com.hsiun.markdowntodo.NoteItem
import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText

class NoteDialogManager(private val context: Context) {

    interface NoteDialogListener {
        fun onAddNote(title: String, content: String)
        fun onUpdateNote(uuid: String, title: String, content: String)
        fun onCancel()
    }

    fun showAddNoteDialog(listener: NoteDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_note_edit, null)

        val titleEditText = dialogView.findViewById<EditText>(R.id.noteTitleEditText)
        val contentEditText = dialogView.findViewById<EditText>(R.id.noteContentEditText)
        val saveButton = dialogView.findViewById<Button>(R.id.noteSaveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.noteCancelButton)

        // 设置文本监听器
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasTitle = titleEditText.text.toString().trim().isNotEmpty()
                saveButton.isEnabled = hasTitle
                saveButton.alpha = if (hasTitle) 1.0f else 0.5f
            }
        }

        titleEditText.addTextChangedListener(textWatcher)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        saveButton.setOnClickListener {
            val title = titleEditText.text.toString().trim()
            val content = contentEditText.text.toString().trim()
            if (title.isNotEmpty()) {
                listener.onAddNote(title, content)
                dialog.dismiss()
            }
        }

        cancelButton.setOnClickListener {
            listener.onCancel()
            dialog.dismiss()
        }

        dialog.show()
    }

    // NoteDialogManager.kt
    fun showEditNoteDialog(note: NoteItem, listener: NoteDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_note_edit, null)

        val titleEditText = dialogView.findViewById<EditText>(R.id.noteTitleEditText)
        val contentEditText = dialogView.findViewById<EditText>(R.id.noteContentEditText)
        val saveButton = dialogView.findViewById<Button>(R.id.noteSaveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.noteCancelButton)

        // 只填充纯内容，不包含任何格式
        titleEditText.setText(note.title)
        contentEditText.setText(note.content)  // 只显示纯内容

        // 设置光标位置
        titleEditText.setSelection(note.title.length)

        // 保存笔记UUID到tag
        saveButton.tag = note.uuid

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        saveButton.setOnClickListener {
            val title = titleEditText.text.toString().trim()
            val content = contentEditText.text.toString().trim()  // 纯内容
            val noteUuid = saveButton.tag as? String
            if (title.isNotEmpty() && noteUuid != null) {
                listener.onUpdateNote(noteUuid, title, content)  // 只传递纯内容
                dialog.dismiss()
            }
        }

        cancelButton.setOnClickListener {
            listener.onCancel()
            dialog.dismiss()
        }

        dialog.show()
    }
}