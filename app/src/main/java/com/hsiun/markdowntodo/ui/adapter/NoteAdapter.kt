package com.hsiun.markdowntodo.ui.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.data.model.NoteItem
import com.hsiun.markdowntodo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val dateAndPreviewText: TextView = view.findViewById(R.id.noteDateAndPreviewText)
        val itemContainer: LinearLayout = view.findViewById(R.id.noteItemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.note_item, parent, false)
        return ViewHolder(view)
    }

    // NoteAdapter.kt
    // 确保onBindViewHolder中的点击事件设置正确
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < 0 || position >= notes.size) {
            Log.e(TAG, "无效的position: $position, 列表大小: ${notes.size}")
            return
        }

        val note = notes[position]
        Log.d(TAG, "绑定笔记: UUID=${note.uuid}, 标题='${note.title}'")

        // 设置标题
        holder.titleText.text = note.title

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

        // 设置内容预览（移除Markdown标记）
        val cleanContent = removeMarkdown(note.content)
        val preview = if (cleanContent.length > 50) {
            cleanContent.substring(0, 50) + "..."
        } else {
            cleanContent
        }

        // 组合日期和内容预览，用 | 分隔
        holder.dateAndPreviewText.text = "$createdAt | $preview"

        // 设置点击事件 - 确保点击整个容器
        holder.itemContainer.setOnClickListener {
            Log.d(TAG, "点击笔记项目: UUID=${note.uuid}")
            onNoteClicked(note)
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

    fun getNoteAtPosition(position: Int): NoteItem? {
        return getItemAtPosition(position)
    }

    fun updateNotes(newNotes: List<NoteItem>) {
        notes.clear()
        notes.addAll(newNotes.sortedByDescending {
            try {
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).parse(it.updatedAt) ?: Date()
            } catch (e: Exception) {
                Date()
            }
        })
        notifyDataSetChanged()
        Log.d(TAG, "更新笔记列表: ${notes.size} 条")
    }

    fun removeNote(note: NoteItem) {
        val position = notes.indexOfFirst { it.uuid == note.uuid }
        if (position != -1) {
            notes.removeAt(position)
            notifyItemRemoved(position)
            Log.d(TAG, "移除笔记: UUID=${note.uuid}")
        } else {
            Log.w(TAG, "尝试移除不存在的笔记: UUID=${note.uuid}")
        }
    }
}