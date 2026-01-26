package com.hsiun.markdowntodo

import com.hsiun.markdowntodo.NoteItem
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
    }

    private val repoDir = File(context.filesDir, GIT_REPO_DIR)
    private val notesDir = File(repoDir, DIR_NOTES)
    private var notes = mutableListOf<NoteItem>()

    // 记录笔记UUID和文件名的映射关系
    private val noteFileMap = mutableMapOf<String, String>()

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
        loadAllNotes()
    }

    fun setNoteChangeListener(listener: NoteChangeListener) {
        this.noteChangeListener = listener
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

            Log.d(TAG, "笔记目录路径: ${notesDir.absolutePath}")
            
            // 列出目录中的所有文件（用于调试）
            val allFiles = notesDir.listFiles() ?: emptyArray()
            Log.d(TAG, "目录中的所有文件数量: ${allFiles.size}")
            allFiles.forEach { file ->
                Log.d(TAG, "目录中的文件: ${file.name}, 是文件: ${file.isFile}, 是目录: ${file.isDirectory}")
            }

            // 手动过滤.md文件，确保读取所有文件
            val noteFiles = allFiles.filter { file ->
                file.isFile && file.name.endsWith(".md", ignoreCase = true)
            }

            Log.d(TAG, "找到 ${noteFiles.size} 个笔记文件")
            noteFiles.forEach { file ->
                Log.d(TAG, "笔记文件: ${file.name}, 大小: ${file.length()} 字节, 路径: ${file.absolutePath}")
            }

            val loadedNotes = mutableListOf<NoteItem>()
            val processedFiles = mutableMapOf<String, Pair<NoteItem, String>>() // 存储UUID -> (笔记, 原文件名)

            // 第一步：收集所有笔记，识别旧格式
            var successCount = 0
            var failCount = 0
            noteFiles.forEach { originalFile ->
                try {
                    val content = originalFile.readText()
                    Log.d(TAG, "处理文件: ${originalFile.name}, 大小: ${content.length} 字符")

                    // 从文件内容中解析笔记
                    val note = NoteItem.fromMarkdown(content)

                    if (note == null) {
                        Log.w(TAG, "文件解析返回null: ${originalFile.name}")
                        failCount++
                    } else {
                        Log.d(TAG, "成功解析笔记: ${originalFile.name}, UUID: ${note.uuid}, 标题: ${note.title}")
                        successCount++

                        // 检查是否是旧格式（内容没有UUID注释或文件名包含UUID）
                        val isOldFormat = !content.startsWith("<!-- UUID: ") ||
                                originalFile.name.contains("_${note.uuid}.md") ||
                                originalFile.name.matches(Regex("note_\\d+_[a-f0-9-]+\\.md"))

                        // 检查UUID是否已存在（处理重复UUID的情况）
                        if (processedFiles.containsKey(note.uuid)) {
                            Log.w(TAG, "发现重复UUID: ${note.uuid}, 文件1: ${processedFiles[note.uuid]?.second}, 文件2: ${originalFile.name}")
                            // 保留更新时间更晚的笔记
                            val existingNote = processedFiles[note.uuid]?.first
                            if (existingNote != null) {
                                try {
                                    val existingDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(existingNote.updatedAt) ?: Date(0)
                                    val newDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(note.updatedAt) ?: Date(0)
                                    if (newDate.after(existingDate)) {
                                        Log.d(TAG, "保留更新时间的笔记: ${originalFile.name}")
                                        processedFiles[note.uuid] = Pair(note, originalFile.name)
                                    } else {
                                        Log.d(TAG, "保留已存在的笔记: ${processedFiles[note.uuid]?.second}")
                                    }
                                } catch (e: Exception) {
                                    // 如果日期解析失败，保留已存在的
                                    Log.w(TAG, "日期解析失败，保留已存在的笔记")
                                }
                            }
                        } else {
                            processedFiles[note.uuid] = Pair(note, originalFile.name)
                            Log.d(TAG, "添加笔记到处理列表: UUID=${note.uuid}, 文件=${originalFile.name}")
                        }

                        if (isOldFormat) {
                            Log.d(TAG, "检测到旧格式笔记: ${originalFile.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取笔记文件失败: ${originalFile.name}", e)
                    failCount++
                }
            }
            
            Log.d(TAG, "文件处理完成: 成功=$successCount, 失败=$failCount, processedFiles大小=${processedFiles.size}")

            // 第二步：为每个笔记生成新文件名
            val usedFileNames = mutableSetOf<String>()
            val renameMap = mutableMapOf<String, Pair<String, String>>() // UUID -> (原文件名, 新文件名)

            processedFiles.forEach { (uuid, pair) ->
                val (note, originalFileName) = pair

                // 检查原文件名是否已经存在且属于当前笔记
                val originalFile = File(notesDir, originalFileName)
                val shouldKeepOriginalName = if (originalFile.exists()) {
                    try {
                        val content = originalFile.readText()
                        val existingNote = NoteItem.fromMarkdown(content)
                        existingNote?.uuid == note.uuid
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }

                // 如果原文件名可用且属于当前笔记，优先使用原文件名
                val newFileName = if (shouldKeepOriginalName && !usedFileNames.contains(originalFileName)) {
                    originalFileName
                } else {
                    // 否则生成新的唯一文件名
                    generateUniqueFileName(note, usedFileNames)
                }
                usedFileNames.add(newFileName)

                // 如果文件名需要更改，记录重命名信息
                if (originalFileName != newFileName) {
                    renameMap[uuid] = Pair(originalFileName, newFileName)
                    Log.d(TAG, "计划重命名: $originalFileName -> $newFileName")
                } else {
                    Log.d(TAG, "保持原文件名: $originalFileName")
                }

                loadedNotes.add(note)
            }

            // 第三步：执行文件操作（先重命名，再删除旧格式文件）
            renameMap.forEach { (uuid, fileNames) ->
                val (oldFileName, newFileName) = fileNames

                try {
                    val oldFile = File(notesDir, oldFileName)
                    val newFile = File(notesDir, newFileName)

                    // 如果新文件已经存在且属于当前笔记，不需要重命名
                    if (newFile.exists()) {
                        try {
                            val newFileContent = newFile.readText()
                            val newFileNote = NoteItem.fromMarkdown(newFileContent)
                            if (newFileNote?.uuid == uuid) {
                                Log.d(TAG, "目标文件已存在且属于当前笔记，跳过重命名: $newFileName")
                                // 删除旧文件（如果存在且不同）
                                if (oldFile.exists() && oldFile.name != newFile.name) {
                                    oldFile.delete()
                                    Log.d(TAG, "删除旧文件: $oldFileName")
                                }
                            } else {
                                // 目标文件属于其他笔记，需要重命名
                                if (oldFile.exists()) {
                                    oldFile.renameTo(newFile)
                                    Log.d(TAG, "重命名文件: $oldFileName -> $newFileName")
                                }
                            }
                        } catch (e: Exception) {
                            // 如果读取失败，尝试重命名
                            if (oldFile.exists()) {
                                oldFile.renameTo(newFile)
                                Log.d(TAG, "重命名文件: $oldFileName -> $newFileName")
                            }
                        }
                    } else if (oldFile.exists()) {
                        // 新文件不存在，直接重命名
                        oldFile.renameTo(newFile)
                        Log.d(TAG, "重命名文件: $oldFileName -> $newFileName")
                    }

                    // 更新映射
                    noteFileMap[uuid] = newFileName
                } catch (e: Exception) {
                    Log.e(TAG, "处理文件失败: $oldFileName -> $newFileName", e)
                }
            }

            // 第四步：处理不需要重命名的文件，但需要检查格式
            processedFiles.forEach { (uuid, pair) ->
                val (note, fileName) = pair

                if (!renameMap.containsKey(uuid)) {
                    val file = File(notesDir, fileName)
                    if (file.exists()) {
                        val content = file.readText()
                        // 如果是旧格式，更新内容
                        if (!content.startsWith("<!-- UUID: ")) {
                            Log.d(TAG, "更新文件格式: $fileName")
                            file.writeText(note.toMarkdown())
                        }
                    }
                    noteFileMap[uuid] = fileName
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

            Log.d(TAG, "最终加载的笔记数量: ${notes.size} 条")
            notes.forEachIndexed { index, note ->
                Log.d(TAG, "笔记 $index: UUID=${note.uuid}, 标题='${note.title}', 更新时间=${note.updatedAt}")
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

        // 使用UUID的短形式（前8个字符）确保文件名唯一
        val uuidShort = if (note.uuid.length >= 8) {
            note.uuid.substring(0, 8)
        } else {
            note.uuid // 如果UUID长度不足8，使用完整UUID
        }

        // 确保文件名唯一
        while (usedFileNames.contains(fileName) || File(notesDir, fileName).exists()) {
            fileName = if (counter == 1) {
                "${baseName}_${uuidShort}.md"
            } else {
                "${baseName}_${uuidShort}_${counter}.md"
            }
            counter++
        }

        return fileName
    }

    // 根据笔记生成文件名（使用标题，可能包含UUID短形式以防止重复）
    // 优先使用 noteFileMap 中已存在的文件名，避免不必要的重命名
    private fun getFileNameForNote(note: NoteItem): String {
        // 首先检查是否已经有映射的文件名
        val existingFileName = noteFileMap[note.uuid]
        if (existingFileName != null) {
            val existingFile = File(notesDir, existingFileName)
            if (existingFile.exists()) {
                // 验证文件内容是否匹配
                try {
                    val content = existingFile.readText()
                    val existingNote = NoteItem.fromMarkdown(content)
                    if (existingNote?.uuid == note.uuid) {
                        Log.d(TAG, "使用已存在的文件名: $existingFileName")
                        return existingFileName
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "验证已存在文件失败，将生成新文件名", e)
                }
            }
        }

        val safeName = note.getSafeFileName()
        var fileName = "$safeName.md"
        val uuidShort = if (note.uuid.length >= 8) {
            note.uuid.substring(0, 8)
        } else {
            note.uuid
        }

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
                    existingNote?.uuid == note.uuid
                } catch (e: Exception) {
                    false
                }

                if (isCurrentNoteFile) {
                    // 是当前笔记的文件，使用这个文件名
                    break
                } else {
                    // 不是当前笔记的文件，尝试其他名称
                    fileName = if (counter == 1) {
                        "${safeName}_${uuidShort}.md"
                    } else {
                        "${safeName}_${uuidShort}_${counter}.md"
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

            val note = NoteItem(id = -1, title = title, content = content)

            Log.d(TAG, "添加笔记: UUID=${note.uuid}, 标题='$title'")

            notes.add(note)
            saveNoteToFile(note)

            noteChangeListener?.onNoteAdded(note)
            note
        } catch (e: Exception) {
            Log.e(TAG, "添加笔记失败", e)
            noteChangeListener?.onNoteError("添加笔记失败: ${e.message}")
            throw e
        }
    }

    fun updateNote(uuid: String, title: String, content: String): NoteItem {
        return try {
            if (title.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val noteIndex = notes.indexOfFirst { it.uuid == uuid }
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

    fun deleteNote(uuid: String): NoteItem {
        return try {
            val noteIndex = notes.indexOfFirst { it.uuid == uuid }
            if (noteIndex == -1) {
                throw IllegalArgumentException("未找到笔记")
            }

            val noteToDelete = notes[noteIndex]
            notes.removeAt(noteIndex)

            // 删除对应的文件
            val fileName = noteFileMap.remove(uuid)
            if (fileName != null) {
                val noteFile = File(notesDir, fileName)
                if (noteFile.exists()) {
                    val deleted = noteFile.delete()
                    if (deleted) {
                        Log.d(TAG, "删除笔记文件成功: $fileName")
                    } else {
                        Log.w(TAG, "删除笔记文件失败: $fileName")
                    }
                } else {
                    Log.w(TAG, "笔记文件不存在: $fileName")
                }
            } else {
                Log.w(TAG, "noteFileMap中没有找到UUID映射: $uuid，尝试通过扫描文件查找")
                // 如果noteFileMap中没有映射，尝试通过扫描文件查找
                val allFiles = notesDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".md", ignoreCase = true)
                } ?: emptyArray()
                
                for (file in allFiles) {
                    try {
                        val content = file.readText()
                        val fileNote = NoteItem.fromMarkdown(content)
                        if (fileNote?.uuid == uuid) {
                            val deleted = file.delete()
                            if (deleted) {
                                Log.d(TAG, "通过扫描找到并删除笔记文件: ${file.name}")
                            } else {
                                Log.w(TAG, "通过扫描找到但删除失败: ${file.name}")
                            }
                            break
                        }
                    } catch (e: Exception) {
                        // 忽略解析失败的文件
                    }
                }
            }

            // 额外清理：删除可能存在的旧格式文件（基于UUID）
            val oldFormatFiles = notesDir.listFiles { file ->
                file.isFile && (file.name.contains("_${noteToDelete.uuid}.md") || 
                               file.name.matches(Regex(".*_${noteToDelete.uuid.substring(0, 8)}.*\\.md")))
            } ?: emptyArray()

            oldFormatFiles.forEach { file ->
                if (file.exists() && file.name != fileName) {
                    try {
                        // 验证文件内容是否属于要删除的笔记
                        val content = file.readText()
                        val fileNote = NoteItem.fromMarkdown(content)
                        if (fileNote?.uuid == uuid) {
                            val deleted = file.delete()
                            if (deleted) {
                                Log.d(TAG, "删除旧格式文件: ${file.name}")
                            } else {
                                Log.w(TAG, "删除旧格式文件失败: ${file.name}")
                            }
                        }
                    } catch (e: Exception) {
                        // 如果解析失败，也尝试删除（可能是损坏的文件）
                        val deleted = file.delete()
                        if (deleted) {
                            Log.d(TAG, "删除可能损坏的旧格式文件: ${file.name}")
                        }
                    }
                }
            }

            Log.d(TAG, "删除笔记完成: UUID=$uuid, 标题='${noteToDelete.title}', 剩余笔记数=${notes.size}")
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
            val oldFileName = noteFileMap[note.uuid]

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
            noteFileMap[note.uuid] = newFileName
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
            // 查找所有可能重复的文件（基于UUID）
            val uuidShort = if (note.uuid.length >= 8) {
                note.uuid.substring(0, 8)
            } else {
                note.uuid
            }
            val duplicateFiles = notesDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md") &&
                        file.name.contains("_$uuidShort")
            } ?: emptyArray()

            // 删除除当前文件外的所有重复文件
            duplicateFiles.forEach { file ->
                if (file.name != currentFileName && file.exists()) {
                    // 验证文件内容是否属于同一笔记
                    try {
                        val content = file.readText()
                        val fileNote = NoteItem.fromMarkdown(content)
                        if (fileNote?.uuid == note.uuid) {
                            file.delete()
                            Log.d(TAG, "删除重复笔记文件: ${file.name}")
                        }
                    } catch (e: Exception) {
                        // 忽略解析失败的文件
                    }
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
            Log.d(TAG, "replaceAllNotes: 开始替换，新笔记数量: ${newNotes.size}")
            
            // 检查重复UUID
            val uuidSet = mutableSetOf<String>()
            val duplicateUuids = mutableListOf<String>()
            newNotes.forEach { note ->
                if (uuidSet.contains(note.uuid)) {
                    duplicateUuids.add(note.uuid)
                    Log.w(TAG, "replaceAllNotes: 发现重复UUID: ${note.uuid}, 标题: ${note.title}")
                } else {
                    uuidSet.add(note.uuid)
                }
            }
            
            if (duplicateUuids.isNotEmpty()) {
                Log.w(TAG, "replaceAllNotes: 发现 ${duplicateUuids.size} 个重复UUID")
            }

            // 清空当前笔记列表和文件映射
            notes.clear()
            noteFileMap.clear()

            // 清空笔记目录
            if (notesDir.exists() && notesDir.isDirectory) {
                notesDir.deleteRecursively()
                notesDir.mkdirs()
            }

            // 重新添加所有笔记（去重，保留第一个）
            val uniqueNotes = mutableListOf<NoteItem>()
            val seenUuids = mutableSetOf<String>()
            newNotes.forEach { note ->
                if (!seenUuids.contains(note.uuid)) {
                    seenUuids.add(note.uuid)
                    uniqueNotes.add(note)
                    notes.add(note)
                    saveNoteToFile(note)
                    Log.d(TAG, "replaceAllNotes: 保存笔记 UUID=${note.uuid}, 标题=${note.title}")
                } else {
                    Log.w(TAG, "replaceAllNotes: 跳过重复UUID的笔记: ${note.uuid}, 标题=${note.title}")
                }
            }

            // 通知监听器
            Log.d(TAG, "replaceAllNotes: 完成，最终笔记数: ${notes.size} 条")
            
            // 验证文件是否已保存（使用手动过滤确保读取所有文件）
            val allSavedFiles = notesDir.listFiles() ?: emptyArray()
            val savedFiles = allSavedFiles.filter { file ->
                file.isFile && file.name.endsWith(".md", ignoreCase = true)
            }
            Log.d(TAG, "replaceAllNotes: 验证保存后的文件数量: ${savedFiles.size}")
            savedFiles.forEach { file ->
                Log.d(TAG, "replaceAllNotes: 保存后的文件: ${file.name}, 大小: ${file.length()} 字节")
            }
            
            // 如果保存的文件数量与笔记数量不一致，记录警告
            if (savedFiles.size != notes.size) {
                Log.w(TAG, "replaceAllNotes: 警告！保存的文件数量(${savedFiles.size})与笔记数量(${notes.size})不一致")
            }
            
            noteChangeListener?.onNotesChanged(notes)
        } catch (e: Exception) {
            Log.e(TAG, "替换所有笔记失败", e)
            noteChangeListener?.onNoteError("替换所有笔记失败: ${e.message}")
        }
    }

    fun verifyNoteDeleted(uuid: String): Boolean {
        val noteExistsInList = notes.any { it.uuid == uuid }

        // 扫描所有文件，检查是否有文件包含该UUID
        var fileExists = false
        if (notesDir.exists() && notesDir.isDirectory) {
            val allFiles = notesDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md", ignoreCase = true)
            } ?: emptyArray()
            
            for (file in allFiles) {
                try {
                    val content = file.readText()
                    val fileNote = NoteItem.fromMarkdown(content)
                    if (fileNote?.uuid == uuid) {
                        fileExists = true
                        Log.w(TAG, "验证删除失败：找到包含UUID的文件: ${file.name}")
                        break
                    }
                } catch (e: Exception) {
                    // 忽略解析失败的文件
                }
            }
        }

        val isDeleted = !noteExistsInList && !fileExists
        Log.d(TAG, "验证笔记删除 - UUID=$uuid: 列表中存在=$noteExistsInList, 文件存在=$fileExists, 删除成功=$isDeleted")

        return isDeleted
    }
    
    /**
     * 强制删除笔记（如果普通删除失败，使用此方法）
     */
    fun forceDeleteNote(uuid: String): Boolean {
        return try {
            Log.d(TAG, "强制删除笔记: UUID=$uuid")
            
            // 1. 从列表中移除
            val noteIndex = notes.indexOfFirst { it.uuid == uuid }
            if (noteIndex != -1) {
                notes.removeAt(noteIndex)
                Log.d(TAG, "从列表中移除笔记")
            }
            
            // 2. 从映射中移除
            noteFileMap.remove(uuid)
            
            // 3. 强制删除所有相关文件
            if (notesDir.exists() && notesDir.isDirectory) {
                val allFiles = notesDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".md", ignoreCase = true)
                } ?: emptyArray()
                
                var deletedCount = 0
                for (file in allFiles) {
                    try {
                        val content = file.readText()
                        val fileNote = NoteItem.fromMarkdown(content)
                        if (fileNote?.uuid == uuid) {
                            val deleted = file.delete()
                            if (deleted) {
                                deletedCount++
                                Log.d(TAG, "强制删除文件: ${file.name}")
                            } else {
                                Log.w(TAG, "强制删除文件失败: ${file.name}")
                            }
                        }
                    } catch (e: Exception) {
                        // 如果解析失败，也尝试删除（可能是损坏的文件）
                        if (file.name.contains(uuid.substring(0, minOf(8, uuid.length)))) {
                            val deleted = file.delete()
                            if (deleted) {
                                deletedCount++
                                Log.d(TAG, "强制删除可能相关的文件: ${file.name}")
                            }
                        }
                    }
                }
                Log.d(TAG, "强制删除完成，删除了 $deletedCount 个文件")
            }
            
            // 4. 验证删除结果
            val isDeleted = verifyNoteDeleted(uuid)
            if (isDeleted) {
                noteChangeListener?.onNoteDeleted(notes.find { it.uuid == uuid } ?: return false)
            }
            
            isDeleted
        } catch (e: Exception) {
            Log.e(TAG, "强制删除笔记失败", e)
            false
        }
    }

}