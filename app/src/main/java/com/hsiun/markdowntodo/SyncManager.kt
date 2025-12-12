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

    private fun pullAndMergeChanges() {
        gitManager.pullChanges(
            onSuccess = { pullResult ->
                syncScope.launch {
                    try {
                        // 智能合并待办事项
                        val mergedTodos = mergeTodosIntelligently()

                        // 更新本地数据
                        todoManager.replaceAllTodos(mergedTodos)

                        // 保存到Git仓库目录
                        val remoteFile = File(context.filesDir, "$GIT_REPO_DIR/todos.md")
                        saveTodosToGitRepo(mergedTodos, remoteFile)

                        isSyncing = false

                        CoroutineScope(Dispatchers.Main).launch {
                            syncListener?.onSyncSuccess("同步完成！共 ${mergedTodos.size} 条待办")
                            syncListener?.onSyncStatusChanged("同步成功")
                        }
                    } catch (e: Exception) {
                        isSyncing = false
                        CoroutineScope(Dispatchers.Main).launch {
                            syncListener?.onSyncError("合并数据失败: ${e.message}")
                        }
                    }
                }
            },
            onError = { error ->
                isSyncing = false

                CoroutineScope(Dispatchers.Main).launch {
                    when {
                        error.contains("Checkout conflict with files") -> {
                            syncListener?.onSyncStatusChanged("检测到冲突...")
                            handleSyncConflict()
                        }
                        error.contains("网络不可用") -> {
                            syncListener?.onSyncError("网络不可用")
                            syncListener?.onSyncStatusChanged("网络不可用")
                        }
                        error.contains("Git仓库未初始化") -> {
                            syncListener?.onSyncError("Git仓库未初始化")
                            syncListener?.onSyncStatusChanged("未初始化")
                        }
                        else -> {
                            syncListener?.onSyncError("同步失败: $error")
                            syncListener?.onSyncStatusChanged("同步失败")
                        }
                    }
                }
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
}