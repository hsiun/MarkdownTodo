package com.hsiun.markdowntodo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        private const val SYNC_TIMEOUT = 30000L // 30秒超时
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
        Log.d(TAG, "performSync 开始, isManualRefresh=$isManualRefresh")

        if (isSyncing) {
            Log.w(TAG, "同步正在进行，跳过")
            syncListener?.onSyncError("正在同步中")
            return false
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) {
            Log.w(TAG, "同步间隔太短，跳过")
            syncListener?.onSyncError("同步间隔太短")
            return false
        }

        if (!::gitManager.isInitialized) {
            Log.w(TAG, "GitManager 未初始化")
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
                    Log.d(TAG, "Git仓库不存在，初始化仓库")
                    initializeGitRepo()
                    return@launch
                }

                Log.d(TAG, "开始同步流程")
                pullAndMergeChanges()
            } catch (e: Exception) {
                Log.e(TAG, "同步过程中发生异常", e)
                syncListener?.onSyncError("同步异常: ${e.message}")
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

    // 修改 pullAndMergeChanges 方法
    private suspend fun pullAndMergeChanges() {
        try {
            val success = suspendCoroutine<Boolean> { continuation ->
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
                                // 添加：更新本地笔记数据
                                noteManager.replaceAllNotes(mergedNotes)
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

                                syncListener?.onSyncProgress("合并完成")

                                // 提交合并后的更改
                                val commitMessage = "同步合并 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"

                                gitManager.commitAndPush(
                                    commitMessage = commitMessage,
                                    filePatterns = listOf("todos.md", "notes/"),
                                    onSuccess = {
                                        syncListener?.onSyncSuccess("同步成功")
                                        continuation.resume(true)
                                    },
                                    onError = { error ->
                                        // 提交失败，但数据已经合并到本地，所以继续
                                        Log.w(TAG, "提交失败，但本地数据已更新: $error")
                                        syncListener?.onSyncSuccess("同步完成（但推送失败）")
                                        continuation.resume(true)
                                    }
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "合并数据时发生异常", e)
                                syncListener?.onSyncError("合并数据失败: ${e.message}")
                                continuation.resume(false)
                            }
                        }
                    },
                    onError = { error ->
                        // 拉取失败（可能是冲突或网络问题），直接删除本地仓库
                        Log.d(TAG, "拉取失败，删除本地仓库: $error")

                        syncScope.launch {
                            try {
                                // 删除本地仓库
                                val repoDir = File(context.filesDir, GIT_REPO_DIR)
                                if (repoDir.exists()) {
                                    repoDir.deleteRecursively()
                                    Log.d(TAG, "已删除本地仓库")
                                }

                                // 通知用户需要手动刷新
                                syncListener?.onSyncError("同步失败，已清理本地仓库，请手动刷新")
                                continuation.resume(false)
                            } catch (e: Exception) {
                                syncListener?.onSyncError("处理失败: ${e.message}")
                                continuation.resume(false)
                            }
                        }
                    }
                )
            }

            if (!success) {
                Log.d(TAG, "同步失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullAndMergeChanges 异常", e)
            syncListener?.onSyncError("同步异常: ${e.message}")
        } finally {
            isSyncing = false
            Log.d(TAG, "同步流程完成，isSyncing = false")
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 收集所有笔记（本地+远程）
        val allNotes = mutableListOf<NoteItem>().apply {
            addAll(localNotes)
            addAll(remoteNotes)
        }

        // 按照UUID分组，比较更新时间，保留最新的
        allNotes.forEach { note ->
            val existing = mergedMap[note.uuid]

            if (existing == null) {
                mergedMap[note.uuid] = note
            } else {
                try {
                    val existingTime = dateFormat.parse(existing.updatedAt) ?: Date(0)
                    val noteTime = dateFormat.parse(note.updatedAt) ?: Date(0)

                    if (noteTime.after(existingTime)) {
                        mergedMap[note.uuid] = note
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析笔记时间失败: ${e.message}")
                    // 解析失败时，使用ID更大的（假设更新更晚）
                    if (note.id > existing.id) {
                        mergedMap[note.uuid] = note
                    }
                }
            }
        }

        return mergedMap.values.sortedBy { it.id }
    }


}