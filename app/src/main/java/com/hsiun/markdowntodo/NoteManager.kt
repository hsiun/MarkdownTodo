// NoteManager.kt
package com.hsiun.markdowntodo

import android.content.Context
import android.util.Log
import java.io.File

class NoteManager(private val context: Context) {

    companion object {
        private const val TAG = "NoteManager"
        private const val NOTES_DIR = "notes"
        private const val MAX_ID_FILE = "note_max_id.json"
    }

    private val notesDir: File
    private var notes = mutableListOf<NoteItem>()
    private var nextId = 1

    interface NoteChangeListener {
        fun onNotesChanged(notes: List<NoteItem>)
        fun onNoteAdded(note: NoteItem)
        fun onNoteUpdated(note: NoteItem)
        fun onNoteDeleted(note: NoteItem)
        fun onNoteError(message: String)
    }

    private var noteChangeListener: NoteChangeListener? = null

    init {
        notesDir = File(context.filesDir, NOTES_DIR)
        if (!notesDir.exists()) {
            notesDir.mkdirs()
        }
        loadMaxId()
        loadAllNotes()
    }

    fun setNoteChangeListener(listener: NoteChangeListener) {
        this.noteChangeListener = listener
    }

    private fun loadMaxId() {
        val maxIdFile = File(context.filesDir, MAX_ID_FILE)
        if (maxIdFile.exists()) {
            try {
                val json = maxIdFile.readText()
                val jsonObject = org.json.JSONObject(json)
                nextId = jsonObject.getInt("max_id")
                Log.d(TAG, "加载笔记最大ID: $nextId")
            } catch (e: Exception) {
                Log.e(TAG, "加载笔记最大ID失败", e)
                nextId = 1
            }
        }
    }

    private fun saveMaxId() {
        try {
            val maxIdFile = File(context.filesDir, MAX_ID_FILE)
            val jsonObject = org.json.JSONObject().apply {
                put("max_id", nextId)
            }
            maxIdFile.writeText(jsonObject.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存笔记最大ID失败", e)
        }
    }

    fun loadAllNotes() {
        try {
            notes.clear()

            if (!notesDir.exists() || !notesDir.isDirectory) {
                Log.d(TAG, "笔记目录不存在，创建目录")
                notesDir.mkdirs()
                noteChangeListener?.onNotesChanged(emptyList())
                return
            }

            val noteFiles = notesDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md")
            } ?: emptyArray()

            Log.d(TAG, "找到 ${noteFiles.size} 个笔记文件")

            val loadedNotes = mutableListOf<NoteItem>()
            noteFiles.forEach { file ->
                try {
                    val content = file.readText()
                    val note = NoteItem.fromMarkdown(content)
                    note?.let {
                        loadedNotes.add(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取笔记文件失败: ${file.name}", e)
                }
            }

            // 按ID排序
            notes.addAll(loadedNotes.sortedBy { it.id })

            if (notes.isNotEmpty()) {
                nextId = notes.maxOf { it.id } + 1
            } else {
                nextId = 1
            }

            noteChangeListener?.onNotesChanged(notes)
            Log.d(TAG, "加载笔记完成: ${notes.size} 条")

        } catch (e: Exception) {
            Log.e(TAG, "加载笔记失败", e)
            noteChangeListener?.onNoteError("加载笔记失败: ${e.message}")
        }
    }

    fun getAllNotes(): List<NoteItem> {
        return notes.toList()
    }

    fun getNoteById(id: Int): NoteItem? {
        return notes.find { it.id == id }
    }

    fun getNoteByUuid(uuid: String): NoteItem? {
        return notes.find { it.uuid == uuid }
    }

    fun addNote(title: String, content: String): NoteItem {
        return try {
            if (title.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val newId = nextId++
            val note = NoteItem(id = newId, title = title, content = content)

            Log.d(TAG, "添加笔记: ID=$newId, 标题='$title'")

            notes.add(note)
            saveNoteToFile(note)
            saveMaxId()

            noteChangeListener?.onNoteAdded(note)
            note
        } catch (e: Exception) {
            Log.e(TAG, "添加笔记失败", e)
            noteChangeListener?.onNoteError("添加笔记失败: ${e.message}")
            throw e
        }
    }

    fun updateNote(id: Int, title: String, content: String): NoteItem {
        return try {
            if (title.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val noteIndex = notes.indexOfFirst { it.id == id }
            if (noteIndex == -1) {
                throw IllegalArgumentException("未找到笔记")
            }

            val oldNote = notes[noteIndex]
            val updatedNote = oldNote.copy(
                title = title,
                content = content,
                updatedAt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())
            )

            notes[noteIndex] = updatedNote
            saveNoteToFile(updatedNote)

            noteChangeListener?.onNoteUpdated(updatedNote)
            updatedNote
        } catch (e: Exception) {
            Log.e(TAG, "更新笔记失败", e)
            noteChangeListener?.onNoteError("更新笔记失败: ${e.message}")
            throw e
        }
    }

    fun deleteNote(id: Int): NoteItem {
        return try {
            val noteIndex = notes.indexOfFirst { it.id == id }
            if (noteIndex == -1) {
                throw IllegalArgumentException("未找到笔记")
            }

            val noteToDelete = notes[noteIndex]
            notes.removeAt(noteIndex)

            // 删除对应的文件
            val noteFile = File(notesDir, "note_${noteToDelete.id}_${noteToDelete.uuid}.md")
            if (noteFile.exists()) {
                noteFile.delete()
            }

            noteChangeListener?.onNoteDeleted(noteToDelete)
            noteToDelete
        } catch (e: Exception) {
            Log.e(TAG, "删除笔记失败", e)
            noteChangeListener?.onNoteError("删除笔记失败: ${e.message}")
            throw e
        }
    }

    fun searchNotes(query: String): List<NoteItem> {
        val lowerQuery = query.lowercase(java.util.Locale.getDefault())
        return notes.filter {
            it.title.lowercase(java.util.Locale.getDefault()).contains(lowerQuery) ||
                    it.content.lowercase(java.util.Locale.getDefault()).contains(lowerQuery)
        }
    }

    private fun saveNoteToFile(note: NoteItem) {
        try {
            val noteFile = File(notesDir, "note_${note.id}_${note.uuid}.md")
            noteFile.writeText(note.toMarkdown())
            Log.d(TAG, "保存笔记到文件: ${noteFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "保存笔记文件失败", e)
            throw e
        }
    }

    fun saveAllNotes() {
        try {
            notes.forEach { note ->
                saveNoteToFile(note)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存所有笔记失败", e)
        }
    }

    fun exportToDirectory(directory: File) {
        try {
            if (!directory.exists()) {
                directory.mkdirs()
            }

            notes.forEach { note ->
                val exportFile = File(directory, "${note.title.replace("/", "_")}.md")
                exportFile.writeText(note.toMarkdown())
            }

            Log.d(TAG, "导出 ${notes.size} 个笔记到 ${directory.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "导出笔记失败", e)
            throw e
        }
    }
    // 在 NoteManager 类中添加
    fun replaceAllNotes(newNotes: List<NoteItem>) {
        try {
            Log.d(TAG, "替换所有笔记，新笔记数量: ${newNotes.size}")

            // 清空当前笔记列表
            notes.clear()

            // 清空笔记目录
            if (notesDir.exists() && notesDir.isDirectory) {
                notesDir.deleteRecursively()
                notesDir.mkdirs()
            }

            // 重新添加所有笔记
            newNotes.forEach { note ->
                notes.add(note)
                saveNoteToFile(note)
            }

            // 更新最大ID
            if (newNotes.isNotEmpty()) {
                nextId = newNotes.maxOf { it.id } + 1
            } else {
                nextId = 1
            }

            saveMaxId()

            // 通知监听器
            noteChangeListener?.onNotesChanged(notes)
            Log.d(TAG, "已替换所有笔记: ${notes.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "替换所有笔记失败", e)
            noteChangeListener?.onNoteError("替换所有笔记失败: ${e.message}")
        }
    }

    fun getNotesStatistics(): NoteStatistics {
        val total = notes.size
        val totalChars = notes.sumOf { it.content.length }
        val avgLength = if (total > 0) totalChars / total else 0
        val recentNotes = notes.sortedByDescending { it.updatedAt }.take(5)

        return NoteStatistics(total, totalChars, avgLength, recentNotes)
    }

    data class NoteStatistics(
        val totalNotes: Int,
        val totalCharacters: Int,
        val averageLength: Int,
        val recentNotes: List<NoteItem>
    )
}