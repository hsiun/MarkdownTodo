// NoteAdapter.kt
package com.hsiun.markdowntodo

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NoteAdapter(
    private var notes: MutableList<NoteItem>,
    private val onNoteClicked: (NoteItem) -> Unit,
    private val onNoteDeleted: (NoteItem) -> Unit
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "NoteAdapter"
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.noteTitleText)
        val contentPreviewText: TextView = view.findViewById(R.id.noteContentPreviewText)
        val dateText: TextView = view.findViewById(R.id.noteDateText)
        val deleteButton: ImageButton = view.findViewById(R.id.noteDeleteButton)
        val itemContainer: LinearLayout = view.findViewById(R.id.noteItemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.note_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < 0 || position >= notes.size) {
            Log.e(TAG, "无效的position: $position, 列表大小: ${notes.size}")
            return
        }

        val note = notes[position]

        // 设置标题
        holder.titleText.text = note.title

        // 设置内容预览（限制长度，移除Markdown标记）
        val plainContent = removeMarkdown(note.content)
        val preview = if (plainContent.length > 100) {
            plainContent.substring(0, 100) + "..."
        } else {
            plainContent
        }
        holder.contentPreviewText.text = preview

        // 设置日期
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val createdAt = try {
            val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(note.createdAt)
            if (parsedDate != null) {
                dateFormat.format(parsedDate)
            } else {
                note.createdAt
            }
        } catch (e: Exception) {
            note.createdAt
        }
        holder.dateText.text = createdAt

        // 设置点击事件
        holder.itemContainer.setOnClickListener {
            onNoteClicked(note)
        }

        // 设置删除按钮事件
        holder.deleteButton.setOnClickListener {
            onNoteDeleted(note)
        }
    }

    private fun removeMarkdown(text: String): String {
        // 移除Markdown标记的简单实现
        return text
            .replace(Regex("#{1,6}\\s*"), "")  // 移除标题标记
            .replace(Regex("\\*\\*|__"), "")    // 移除粗体标记
            .replace(Regex("\\*|_"), "")        // 移除斜体标记
            .replace(Regex("`{1,3}"), "")       // 移除代码标记
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")  // 替换链接为文本
            .replace(Regex("!\\[([^\\]]*)\\]\\([^)]+\\)"), "$1") // 替换图片为替代文本
            .replace(Regex(">\\s*"), "")         // 移除引用标记
            .replace(Regex("-{3,}"), "")         // 移除分隔线
            .trim()
    }

    override fun getItemCount() = notes.size

    fun getItemAtPosition(position: Int): NoteItem? {
        return if (position in 0 until notes.size) {
            notes[position]
        } else {
            null
        }
    }

    fun updateNotes(newNotes: List<NoteItem>) {
        notes.clear()
        notes.addAll(newNotes.sortedByDescending {
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(it.updatedAt) ?: Date()
            } catch (e: Exception) {
                Date()
            }
        })
        notifyDataSetChanged()
        Log.d(TAG, "更新笔记列表: ${notes.size} 条")
    }

    fun removeNote(note: NoteItem) {
        val position = notes.indexOfFirst { it.id == note.id }
        if (position != -1) {
            notes.removeAt(position)
            notifyItemRemoved(position)
            Log.d(TAG, "移除笔记: ID=${note.id}")
        } else {
            Log.w(TAG, "尝试移除不存在的笔记: ID=${note.id}")
        }
    }
}