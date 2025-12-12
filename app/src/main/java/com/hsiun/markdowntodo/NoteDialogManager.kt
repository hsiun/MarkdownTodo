// NoteDialogManager.kt
package com.hsiun.markdowntodo

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
        fun onUpdateNote(id: Int, title: String, content: String)
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

    fun showEditNoteDialog(note: NoteItem, listener: NoteDialogListener) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_note_edit, null)

        val titleEditText = dialogView.findViewById<EditText>(R.id.noteTitleEditText)
        val contentEditText = dialogView.findViewById<EditText>(R.id.noteContentEditText)
        val saveButton = dialogView.findViewById<Button>(R.id.noteSaveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.noteCancelButton)

        // 填充现有内容
        titleEditText.setText(note.title)
        contentEditText.setText(note.content)

        // 设置光标位置
        titleEditText.setSelection(note.title.length)

        // 保存笔记ID到tag
        saveButton.tag = note.id

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        saveButton.setOnClickListener {
            val title = titleEditText.text.toString().trim()
            val content = contentEditText.text.toString().trim()
            val noteId = saveButton.tag as? Int
            if (title.isNotEmpty() && noteId != null) {
                listener.onUpdateNote(noteId, title, content)
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