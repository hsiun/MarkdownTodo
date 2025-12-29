package com.hsiun.markdowntodo

import NoteItem
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteManager(private val context: Context) {

    companion object {
        private const val TAG = "NoteManager"
        private const val GIT_REPO_DIR = "git_repo"
        private const val DIR_NOTES = "notes"
        private const val MAX_ID_FILE = "note_max_id.json"
    }

    private val repoDir = File(context.filesDir, GIT_REPO_DIR)
    private val notesDir = File(repoDir, DIR_NOTES)
    private var notes = mutableListOf<NoteItem>()
    private var nextId = 1

    // 记录笔记ID和文件名的映射关系
    private val noteFileMap = mutableMapOf<Int, String>()

    interface NoteChangeListener {
        fun onNotesChanged(notes: List<NoteItem>)
        fun onNoteAdded(note: NoteItem)
        fun onNoteUpdated(note: NoteItem)
        fun onNoteDeleted(note: NoteItem)
        fun onNoteError(message: String)
    }

    private var noteChangeListener: NoteChangeListener? = null

    init {
        // 确保目录存在
        if (!repoDir.exists()) {
            repoDir.mkdirs()
        }
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
            noteFileMap.clear()

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
            val processedFiles = mutableMapOf<Int, Pair<NoteItem, String>>() // 存储ID -> (笔记, 原文件名)

            // 第一步：收集所有笔记，识别旧格式
            noteFiles.forEach { originalFile ->
                try {
                    val content = originalFile.readText()

                    // 从文件内容中解析笔记
                    val note = NoteItem.fromMarkdown(content)

                    note?.let { loadedNote ->
                        // 检查是否是旧格式（内容没有UUID注释或文件名包含UUID）
                        val isOldFormat = !content.startsWith("<!-- UUID: ") ||
                                originalFile.name.contains("_${loadedNote.uuid}.md") ||
                                originalFile.name.matches(Regex("note_\\d+_[a-f0-9-]+\\.md"))

                        processedFiles[loadedNote.id] = Pair(loadedNote, originalFile.name)

                        if (isOldFormat) {
                            Log.d(TAG, "检测到旧格式笔记: ${originalFile.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取笔记文件失败: ${originalFile.name}", e)
                }
            }

            // 第二步：为每个笔记生成新文件名
            val usedFileNames = mutableSetOf<String>()
            val renameMap = mutableMapOf<Int, Pair<String, String>>() // ID -> (原文件名, 新文件名)

            processedFiles.forEach { (id, pair) ->
                val (note, originalFileName) = pair

                // 生成新文件名
                val newFileName = generateUniqueFileName(note, usedFileNames)
                usedFileNames.add(newFileName)

                // 如果文件名需要更改，记录重命名信息
                if (originalFileName != newFileName) {
                    renameMap[id] = Pair(originalFileName, newFileName)
                    Log.d(TAG, "计划重命名: $originalFileName -> $newFileName")
                }

                loadedNotes.add(note)
            }

            // 第三步：执行文件操作（先重命名，再删除旧格式文件）
            renameMap.forEach { (id, fileNames) ->
                val (oldFileName, newFileName) = fileNames

                try {
                    val oldFile = File(notesDir, oldFileName)
                    val newFile = File(notesDir, newFileName)

                    // 如果旧文件是旧格式，先保存为新格式内容
                    val note = processedFiles[id]?.first
                    if (note != null && oldFile.name.contains("_${note.uuid}.md")) {
                        // 保存为新格式
                        newFile.writeText(note.toMarkdown())

                        // 删除旧文件
                        if (oldFile.exists()) {
                            oldFile.delete()
                            Log.d(TAG, "删除旧格式文件: $oldFileName")
                        }
                    } else if (oldFile.exists()) {
                        // 只是重命名，移动文件
                        oldFile.renameTo(newFile)
                        Log.d(TAG, "重命名文件: $oldFileName -> $newFileName")
                    }

                    // 更新映射
                    noteFileMap[id] = newFileName
                } catch (e: Exception) {
                    Log.e(TAG, "处理文件失败: $oldFileName -> $newFileName", e)
                }
            }

            // 第四步：处理不需要重命名的文件，但需要检查格式
            processedFiles.forEach { (id, pair) ->
                val (note, fileName) = pair

                if (!renameMap.containsKey(id)) {
                    val file = File(notesDir, fileName)
                    if (file.exists()) {
                        val content = file.readText()
                        // 如果是旧格式，更新内容
                        if (!content.startsWith("<!-- UUID: ")) {
                            Log.d(TAG, "更新文件格式: $fileName")
                            file.writeText(note.toMarkdown())
                        }
                    }
                    noteFileMap[id] = fileName
                }
            }

            // 按更新时间降序排序
            notes.addAll(loadedNotes.sortedByDescending {
                try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(it.updatedAt) ?: Date()
                } catch (e: Exception) {
                    Date()
                }
            })

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

    /**
     * 生成唯一的文件名
     */
    private fun generateUniqueFileName(note: NoteItem, usedFileNames: Set<String>): String {
        var baseName = note.getSafeFileName()
        var fileName = "$baseName.md"
        var counter = 1

        // 确保文件名唯一
        while (usedFileNames.contains(fileName) || File(notesDir, fileName).exists()) {
            fileName = if (counter == 1) {
                "${baseName}_${note.id}.md"
            } else {
                "${baseName}_${note.id}_${counter}.md"
            }
            counter++
        }

        return fileName
    }

    // 根据笔记生成文件名（使用标题，可能包含ID以防止重复）
    private fun getFileNameForNote(note: NoteItem): String {
        val safeName = note.getSafeFileName()
        var fileName = "$safeName.md"

        // 检查是否已存在相同文件名的文件（且不是当前笔记）
        var counter = 1
        while (true) {
            val existingFile = File(notesDir, fileName)
            val fileExists = existingFile.exists()

            // 如果文件存在，检查是否是当前笔记的文件
            if (fileExists) {
                val isCurrentNoteFile = try {
                    val content = existingFile.readText()
                    val existingNote = NoteItem.fromMarkdown(content)
                    existingNote?.id == note.id
                } catch (e: Exception) {
                    false
                }

                if (isCurrentNoteFile) {
                    // 是当前笔记的文件，使用这个文件名
                    break
                } else {
                    // 不是当前笔记的文件，尝试其他名称
                    fileName = if (counter == 1) {
                        "${safeName}_${note.id}.md"
                    } else {
                        "${safeName}_${note.id}_${counter}.md"
                    }
                    counter++
                }
            } else {
                // 文件不存在，可以使用这个名称
                break
            }
        }

        return fileName
    }

    fun getAllNotes(): List<NoteItem> {
        return notes.toList()
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
                updatedAt = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
            )

            // 更新列表
            notes[noteIndex] = updatedNote

            // 保存到文件（会自动处理旧文件清理）
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
            val fileName = noteFileMap.remove(id)
            if (fileName != null) {
                val noteFile = File(notesDir, fileName)
                if (noteFile.exists()) {
                    noteFile.delete()
                    Log.d(TAG, "删除笔记文件: $fileName")
                }
            }

            // 额外清理：删除可能存在的旧格式文件
            val oldFormatFiles = notesDir.listFiles { file ->
                file.isFile &&
                        (file.name.startsWith("note_${id}_") ||
                                file.name.contains("_${noteToDelete.uuid}.md"))
            } ?: emptyArray()

            oldFormatFiles.forEach { file ->
                if (file.exists() && file.name != fileName) {
                    file.delete()
                    Log.d(TAG, "删除旧格式文件: ${file.name}")
                }
            }

            noteChangeListener?.onNoteDeleted(noteToDelete)
            noteToDelete
        } catch (e: Exception) {
            Log.e(TAG, "删除笔记失败", e)
            noteChangeListener?.onNoteError("删除笔记失败: ${e.message}")
            throw e
        }
    }
    private fun saveNoteToFile(note: NoteItem) {
        try {
            val newFileName = getFileNameForNote(note)
            val oldFileName = noteFileMap[note.id]

            // 如果存在旧文件且文件名不同，删除旧文件
            if (oldFileName != null && oldFileName != newFileName) {
                val oldFile = File(notesDir, oldFileName)
                if (oldFile.exists()) {
                    oldFile.delete()
                    Log.d(TAG, "删除旧文件: $oldFileName")
                }
            }

            // 保存新文件
            val noteFile = File(notesDir, newFileName)
            noteFile.writeText(note.toMarkdown())
            noteFileMap[note.id] = newFileName
            // 清理可能的重复文件
            cleanupDuplicateNoteFiles(note, newFileName)

            Log.d(TAG, "保存笔记到文件: $newFileName")
        } catch (e: Exception) {
            Log.e(TAG, "保存笔记文件失败", e)
            throw e
        }
    }
    private fun cleanupDuplicateNoteFiles(note: NoteItem, currentFileName: String) {
        try {
            // 查找所有可能重复的文件
            val duplicateFiles = notesDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md") &&
                        (file.name.contains("_${note.uuid}.md") ||
                                file.name.startsWith("note_${note.id}_"))
            } ?: emptyArray()

            // 删除除当前文件外的所有重复文件
            duplicateFiles.forEach { file ->
                if (file.name != currentFileName && file.exists()) {
                    file.delete()
                    Log.d(TAG, "删除重复笔记文件: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理重复文件失败", e)
        }
    }

    fun searchNotes(query: String): List<NoteItem> {
        val lowerQuery = query.lowercase(java.util.Locale.getDefault())
        return notes.filter {
            it.title.lowercase(java.util.Locale.getDefault()).contains(lowerQuery) ||
                    it.content.lowercase(java.util.Locale.getDefault()).contains(lowerQuery)
        }
    }

    fun replaceAllNotes(newNotes: List<NoteItem>) {
        try {
            Log.d(TAG, "替换所有笔记，新笔记数量: ${newNotes.size}")

            // 清空当前笔记列表和文件映射
            notes.clear()
            noteFileMap.clear()

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

    fun verifyNoteDeleted(id: Int): Boolean {
        val noteExistsInList = notes.any { it.id == id }

        // 检查文件是否存在
        val fileName = noteFileMap[id]
        val fileExists = if (fileName != null) {
            File(notesDir, fileName).exists()
        } else {
            false
        }

        Log.d(TAG, "验证笔记删除 - ID=$id: 列表中存在=$noteExistsInList, 文件存在=$fileExists")

        return !noteExistsInList && !fileExists
    }

}