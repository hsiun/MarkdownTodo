package com.hsiun.markdowntodo.ui.dialog

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.hsiun.markdowntodo.R

class NoteCategoryDialog(private val context: Context) {

    fun showCreateCategoryDialog(onCreated: (String) -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(context).apply {
            hint = "请输入分类名称"
        }
        layout.addView(input)

        AlertDialog.Builder(context)
            .setTitle("新建笔记分类")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    onCreated(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showSelectCategoryDialog(
        categories: List<com.hsiun.markdowntodo.data.model.NoteCategory>,
        currentId: String,
        onSelected: (String) -> Unit,
        onCreateNew: () -> Unit
    ) {
        val names = categories.map { it.getDisplayText() }.toTypedArray()
        val currentIndex = categories.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("选择笔记分类")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val selected = categories[which]
                onSelected(selected.id)
                dialog.dismiss()
            }
            .setNeutralButton("新建分类") { _, _ ->
                onCreateNew()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
