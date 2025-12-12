package com.hsiun.markdowntodo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SyncManager(
    private val context: Context,
    private val todoManager: TodoManager,
    private val noteManager: NoteManager, // 添加笔记管理器

    private val sharedPreferences: SharedPreferences
) {

    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_COOLDOWN = 5000L
        private const val GIT_REPO_DIR = "git_repo"
    }

    private var isSyncing = false
    private var lastSyncTime: Long = 0
    private lateinit var gitManager: GitManager

    // 协程作用域
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface SyncListener {
        fun onSyncStarted()
        fun onSyncSuccess(message: String)
        fun onSyncError(error: String)
        fun onSyncStatusChanged(status: String)
        fun onSyncProgress(string: String)
    }

    private var syncListener: SyncListener? = null

    fun setSyncListener(listener: SyncListener) {
        this.syncListener = listener
    }

    fun initGitManager(githubRepoUrl: String, githubToken: String) {
        if (githubRepoUrl.isEmpty() || githubToken.isEmpty()) {
            Log.d(TAG, "Git配置不完整，不初始化GitManager")
            return
        }

        if (::gitManager.isInitialized) {
            gitManager.cleanup()
        }

        gitManager = GitManager(context, githubRepoUrl, githubToken)
        Log.d(TAG, "GitManager已初始化")
    }

    fun performSync(isManualRefresh: Boolean = false): Boolean {
        if (isSyncing) {
            syncListener?.onSyncError("正在同步中")
            return false
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) {
            syncListener?.onSyncError("同步间隔太短")
            return false
        }

        if (!::gitManager.isInitialized) {
            syncListener?.onSyncError("Git未配置")
            return false
        }

        isSyncing = true
        lastSyncTime = currentTime

        CoroutineScope(Dispatchers.Main).launch {
            syncListener?.onSyncStarted()
            syncListener?.onSyncStatusChanged("正在同步...")
        }

        syncScope.launch {
            try {
                val repoDir = File(context.filesDir, GIT_REPO_DIR)
                val gitConfig = File(repoDir, ".git/config")

                if (!gitConfig.exists()) {
                    initializeGitRepo()
                    return@launch
                }

                pullAndMergeChanges()
            } catch (e: Exception) {
                isSyncing = false
                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncError("同步异常: ${e.message}")
                    syncListener?.onSyncStatusChanged("同步失败")
                }
            }
        }

        return true
    }

    private fun initializeGitRepo() {
        CoroutineScope(Dispatchers.Main).launch {
            syncListener?.onSyncStatusChanged("正在初始化...")
        }

        gitManager.initAndCloneRepo(
            onSuccess = {
                isSyncing = false
                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncSuccess("Git仓库初始化成功")
                    syncListener?.onSyncStatusChanged("初始化成功")
                    performSync()
                }
            },
            onError = { error ->
                isSyncing = false
                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncError("Git初始化失败: $error")
                    syncListener?.onSyncStatusChanged("初始化失败")
                }
            }
        )
    }

    // 修改同步方法以包含笔记
    private suspend fun pullAndMergeChanges() {
        gitManager.pullChanges(
            onSuccess = { pullResult ->
                syncScope.launch {
                    try {
                        syncListener?.onSyncProgress("拉取成功，正在合并数据...")

                        // 合并待办事项
                        val mergedTodos = mergeTodosIntelligently()

                        // 合并笔记
                        val mergedNotes = mergeNotesIntelligently()

                        // 更新本地数据
                        todoManager.replaceAllTodos(mergedTodos)
                        // 这里需要添加更新笔记的方法
                        // noteManager.replaceAllNotes(mergedNotes)

                        // 保存到Git仓库目录
                        val remoteTodoFile = File(context.filesDir, "$GIT_REPO_DIR/todos.md")
                        saveTodosToGitRepo(mergedTodos, remoteTodoFile)

                        // 保存笔记
                        val remoteNotesDir = File(context.filesDir, "$GIT_REPO_DIR/notes")
                        if (!remoteNotesDir.exists()) {
                            remoteNotesDir.mkdirs()
                        }

                        mergedNotes.forEach { note ->
                            val noteFile = File(remoteNotesDir, "note_${note.id}_${note.uuid}.md")
                            noteFile.writeText(note.toMarkdown())
                        }

                        syncListener?.onSyncProgress("合并后共有 ${mergedTodos.size} 条待办，${mergedNotes.size} 条笔记")

                        // ... 其他代码 ...
                    } catch (e: Exception) {
                        // 错误处理
                    }
                }
            },
            onError = { error ->
                // 错误处理
            }
        )
    }

    private fun handleSyncConflict() {
        syncScope.launch {
            try {
                val repoDir = File(context.filesDir, GIT_REPO_DIR)
                if (repoDir.exists()) {
                    repoDir.deleteRecursively()
                }

                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncStatusChanged("重新初始化...")
                }

                initializeGitRepo()
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncError("处理冲突失败: ${e.message}")
                }
            }
        }
    }

    fun autoPushTodo(operation: String, todo: TodoItem? = null) {
        if (!::gitManager.isInitialized) {
            return
        }

        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val gitConfig = File(repoDir, ".git/config")

        if (!gitConfig.exists()) {
            return
        }

        syncScope.launch {
            try {
                // 将本地待办事项文件复制到Git仓库目录
                val remoteTodoFile = File(repoDir, "todos.md")
                saveTodosToGitRepo(todoManager.getAllTodos(), remoteTodoFile)

                gitManager.commitAndPush(
                    commitMessage = "$operation 待办事项 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}",
                    onSuccess = {
                        CoroutineScope(Dispatchers.Main).launch {
                            syncListener?.onSyncStatusChanged("推送成功")
                        }
                    },
                    onError = { error ->
                        CoroutineScope(Dispatchers.Main).launch {
                            syncListener?.onSyncError("自动推送失败: $error")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "自动推送异常", e)
                CoroutineScope(Dispatchers.Main).launch {
                    syncListener?.onSyncError("自动推送异常: ${e.message}")
                }
            }
        }
    }

    private fun mergeTodosIntelligently(): List<TodoItem> {
        val localTodos = todoManager.getAllTodos()
        val remoteFile = File(context.filesDir, "$GIT_REPO_DIR/todos.md")

        val remoteTodos = if (remoteFile.exists()) {
            readTodosFromFile(remoteFile)
        } else {
            emptyList()
        }

        val mergedMap = mutableMapOf<String, TodoItem>()

        // 先添加远程的所有项目，远程优先
        remoteTodos.forEach { remoteTodo ->
            mergedMap[remoteTodo.uuid] = remoteTodo
        }

        // 然后处理本地的项目
        localTodos.forEach { localTodo ->
            val existingTodo = mergedMap[localTodo.uuid]

            if (existingTodo == null) {
                mergedMap[localTodo.uuid] = localTodo
            } else {
                if (existingTodo.isCompleted) {
                    val mergedTodo = localTodo.copy(isCompleted = true)
                    mergedMap[localTodo.uuid] = mergedTodo
                } else if (localTodo.isCompleted && !existingTodo.isCompleted) {
                    mergedMap[localTodo.uuid] = localTodo
                } else {
                    mergedMap[localTodo.uuid] = localTodo
                }
            }
        }

        return mergedMap.values.sortedBy { it.id }
    }

    private fun readTodosFromFile(file: File): List<TodoItem> {
        return if (file.exists() && file.length() > 0) {
            try {
                val lines = file.readLines()
                if (lines.size > 2) {
                    lines.drop(2)
                        .filter { it.isNotBlank() }
                        .mapNotNull { TodoItem.fromMarkdownLine(it) }
                        .toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取待办文件失败", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun saveTodosToGitRepo(todos: List<TodoItem>, file: File) {
        try {
            file.bufferedWriter().use { writer ->
                writer.write("# 待办事项\n\n")
                todos.forEach { todo ->
                    writer.write("${todo.toMarkdownLine()}\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存文件到Git仓库失败", e)
            throw e
        }
    }

    fun cleanup() {
        syncScope.cancel()
        if (::gitManager.isInitialized) {
            gitManager.cleanup()
        }
    }

    fun autoPushNote(operation: String, note: NoteItem? = null) {
        if (!::gitManager.isInitialized) {
            return
        }

        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val gitConfig = File(repoDir, ".git/config")

        if (!gitConfig.exists()) {
            return
        }

        syncScope.launch {
            try {
                syncListener?.onSyncProgress("自动推送笔记: $operation")

                // 将笔记目录复制到Git仓库目录
                val localNotesDir = File(context.filesDir, "notes")
                val remoteNotesDir = File(repoDir, "notes")

                if (localNotesDir.exists() && localNotesDir.isDirectory) {
                    // 确保远程目录存在
                    if (!remoteNotesDir.exists()) {
                        remoteNotesDir.mkdirs()
                    }

                    // 复制所有笔记文件
                    val noteFiles = localNotesDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".md")
                    }

                    noteFiles?.forEach { localFile ->
                        val remoteFile = File(remoteNotesDir, localFile.name)
                        localFile.copyTo(remoteFile, overwrite = true)
                    }

                    syncListener?.onSyncProgress("已复制 ${noteFiles?.size ?: 0} 个笔记文件到Git目录")
                }

                // 将待办事项文件也复制到Git仓库目录（保持同步）
                val localTodoFile = File(context.filesDir, "todos.md")
                val remoteTodoFile = File(repoDir, "todos.md")
                if (localTodoFile.exists()) {
                    localTodoFile.copyTo(remoteTodoFile, overwrite = true)
                }

                // 检查是否有笔记文件需要推送
                val hasNotesFiles = remoteNotesDir.exists() &&
                        remoteNotesDir.listFiles()?.any { it.isFile && it.name.endsWith(".md") } == true

                // 准备要添加的文件模式
                val filePatterns = mutableListOf<String>()

                if (remoteTodoFile.exists()) {
                    filePatterns.add("todos.md")
                }

                if (hasNotesFiles) {
                    filePatterns.add("notes/")  // 添加整个 notes 目录
                }

                if (filePatterns.isEmpty()) {
                    Log.d(TAG, "没有需要推送的文件")
                    syncListener?.onSyncProgress("没有需要推送的文件")
                    return@launch
                }

                gitManager.commitAndPush(
                    commitMessage = "$operation 笔记 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}",
                    filePatterns = filePatterns,
                    onSuccess = {
                        syncScope.launch {
                            withContext(Dispatchers.Main) {
                                syncListener?.onSyncProgress("自动推送成功: $operation")
                                syncListener?.onSyncStatusChanged("推送成功")
                            }
                        }
                    },
                    onError = { error ->
                        syncScope.launch {
                            withContext(Dispatchers.Main) {
                                syncListener?.onSyncError("自动推送失败: $operation - $error")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "自动推送笔记异常", e)
                syncListener?.onSyncError("自动推送笔记异常: ${e.message}")
            }
        }
    }

    private fun mergeNotesIntelligently(): List<NoteItem> {
        val localNotes = noteManager.getAllNotes()
        val remoteNotesDir = File(context.filesDir, "$GIT_REPO_DIR/notes")

        val remoteNotes = mutableListOf<NoteItem>()

        if (remoteNotesDir.exists() && remoteNotesDir.isDirectory) {
            remoteNotesDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md")
            }?.forEach { file ->
                try {
                    val content = file.readText()
                    val note = NoteItem.fromMarkdown(content)
                    note?.let { remoteNotes.add(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "读取远程笔记文件失败: ${file.name}", e)
                }
            }
        }

        val mergedMap = mutableMapOf<String, NoteItem>()

        // 先添加远程的所有笔记，远程优先
        remoteNotes.forEach { remoteNote ->
            mergedMap[remoteNote.uuid] = remoteNote
        }

        // 然后处理本地的笔记
        localNotes.forEach { localNote ->
            val existingNote = mergedMap[localNote.uuid]

            if (existingNote == null) {
                // 远程没有这个笔记，添加本地笔记
                mergedMap[localNote.uuid] = localNote
            } else {
                // 远程有这个笔记，比较更新时间，使用最新的
                val localUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(localNote.updatedAt)
                val remoteUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(existingNote.updatedAt)

                if (localUpdated != null && remoteUpdated != null && localUpdated.after(remoteUpdated)) {
                    // 本地更新，使用本地版本
                    mergedMap[localNote.uuid] = localNote
                }
                // 否则保持远程版本
            }
        }

        return mergedMap.values.sortedBy { it.id }
    }


}