package com.hsiun.markdowntodo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

class NoteAdapter(
    private var notes: MutableList<NoteItem>,
    private val onNoteClicked: (NoteItem) -> Unit,
    private val onNoteDeleted: (NoteItem) -> Unit
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.noteTitleText)
        val previewText: TextView = view.findViewById(R.id.notePreviewText)
        val dateText: TextView = view.findViewById(R.id.noteDateText)
        val deleteButton: ImageButton = view.findViewById(R.id.noteDeleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.note_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = notes[position]

        holder.titleText.text = note.title
        holder.previewText.text = if (note.content.length > 50) {
            "${note.content.substring(0, 50)}..."
        } else {
            note.content
        }
        holder.dateText.text = note.updatedAt

        holder.itemView.setOnClickListener {
            onNoteClicked(note)
        }

        holder.deleteButton.setOnClickListener {
            onNoteDeleted(note)
        }
    }

    override fun getItemCount() = notes.size

    fun updateNotes(newNotes: List<NoteItem>) {
        notes.clear()
        // 按更新时间降序排序
        notes.addAll(newNotes.sortedByDescending {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(it.updatedAt)
        })
        notifyDataSetChanged()
    }

    fun addNote(note: NoteItem) {
        notes.add(0, note) // 添加到开头
        // 重新排序
        notes.sortByDescending {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(it.updatedAt)
        }
        notifyDataSetChanged()
    }

    fun removeNote(note: NoteItem) {
        val position = notes.indexOfFirst { it.id == note.id }
        if (position != -1) {
            notes.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}