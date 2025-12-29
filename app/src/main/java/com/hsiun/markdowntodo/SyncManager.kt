package com.hsiun.markdowntodo

import NoteItem
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.MergeResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.eclipse.jgit.api.PullResult as JPullResult

class SyncManager(
    private val context: Context,
    private val todoManager: TodoManager,
    private val noteManager: NoteManager,
    private val todoListManager: TodoListManager,
) {

    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_COOLDOWN = 5000L
        private const val GIT_REPO_DIR = "git_repo"

        // Git 仓库中的目录结构
        private const val DIR_TODO_LISTS = "todo_lists"
        private const val DIR_NOTES = "notes"
        private const val FILE_METADATA = "metadata.json"
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
                // 执行完整的同步流程
                fullSyncFlow()
            } catch (e: Exception) {
                Log.e(TAG, "同步过程中发生异常", e)
                syncListener?.onSyncError("同步异常: ${e.message}")
                isSyncing = false
            }
        }

        return true
    }

    /**
     * 完整的同步流程
     */
    private suspend fun fullSyncFlow() {
        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val gitConfig = File(repoDir, ".git/config")

        // 1. 检查Git仓库是否存在，不存在则初始化并拉取
        if (!gitConfig.exists()) {
            Log.d(TAG, "Git仓库不存在，初始化仓库")
            initializeAndPull()
            return
        }

        // 2. 先拉取远程更新（让Git自动合并）
        val pullResult = pullWithGitMerge()

        // 3. 处理拉取结果
        when (pullResult) {
            is SyncPullResult.Success -> {
                syncListener?.onSyncProgress("拉取成功，Git自动合并完成")
                loadDataFromGitToMemory()
            }
            is SyncPullResult.Conflict -> {
                syncListener?.onSyncProgress("检测到冲突，正在处理...")
                handleMergeConflict(pullResult)
                loadDataFromGitToMemory()
            }
            is SyncPullResult.Error -> {
                syncListener?.onSyncError("拉取失败: ${pullResult.errorMessage}")
                isSyncing = false
                return
            }
        }

        // 4. 推送本地更改到远程
        pushToRemote()

        isSyncing = false
    }

    /**
     * 初始化Git仓库并拉取数据
     */
    private suspend fun initializeAndPull() {
        return suspendCoroutine { continuation ->
            gitManager.initAndCloneRepo(
                onSuccess = {
                    syncScope.launch {
                        // 克隆成功后，加载数据到内存
                        loadDataFromGitToMemory()
                        syncListener?.onSyncSuccess("Git仓库初始化成功")
                        isSyncing = false
                        continuation.resume(Unit)
                    }
                },
                onError = { error ->
                    syncScope.launch {
                        syncListener?.onSyncError("Git初始化失败: $error")
                        isSyncing = false
                        continuation.resume(Unit)
                    }
                }
            )
        }
    }

    /**
     * 拉取远程更新（使用Git自动合并）
     */
    private suspend fun pullWithGitMerge(): SyncPullResult {
        return suspendCoroutine { continuation ->
            syncListener?.onSyncProgress("正在拉取远程更新...")

            gitManager.pullChanges(
                onSuccess = { pullResult ->
                    syncScope.launch {
                        // 检查合并状态
                        val mergeResult = pullResult.mergeResult
                        if (mergeResult != null) {
                            when (mergeResult.mergeStatus) {
                                MergeResult.MergeStatus.CONFLICTING -> {
                                    syncListener?.onSyncProgress("检测到冲突")

                                    // 获取冲突文件列表
                                    val conflictFiles = mutableListOf<String>()
                                    if (mergeResult.conflicts != null) {
                                        conflictFiles.addAll(mergeResult.conflicts.keys)
                                    }

                                    continuation.resume(SyncPullResult.Conflict(conflictFiles, mergeResult))
                                }
                                MergeResult.MergeStatus.FAILED -> {
                                    syncListener?.onSyncProgress("合并失败")
                                    continuation.resume(SyncPullResult.Error("合并失败"))
                                }
                                else -> {
                                    syncListener?.onSyncProgress("拉取成功")
                                    continuation.resume(SyncPullResult.Success(pullResult))
                                }
                            }
                        } else {
                            syncListener?.onSyncProgress("拉取成功")
                            continuation.resume(SyncPullResult.Success(pullResult))
                        }
                    }
                },
                onError = { error ->
                    syncScope.launch {
                        // 检查错误信息是否包含冲突
                        if (error.contains("冲突") || error.contains("Checkout conflict")) {
                            syncListener?.onSyncProgress("检测到冲突")

                            // 从错误信息中提取冲突文件
                            val conflictFiles = extractConflictFilesFromError(error)
                            continuation.resume(SyncPullResult.Conflict(conflictFiles, null))
                        } else {
                            syncListener?.onSyncProgress("拉取失败: $error")
                            continuation.resume(SyncPullResult.Error(error))
                        }
                    }
                }
            )
        }
    }

    /**
     * 从错误信息中提取冲突文件
     */
    private fun extractConflictFilesFromError(error: String): List<String> {
        val conflictFiles = mutableListOf<String>()

        try {
            // 尝试从错误信息中提取文件列表
            // 错误格式可能是："Checkout conflict with files: \ntodo_lists/todos.md"
            val lines = error.lines()
            for (line in lines) {
                if (line.trim().endsWith(".md") || line.contains("todo_lists/") || line.contains("notes/")) {
                    // 提取文件名
                    val fileName = line.trim()
                    if (fileName.isNotEmpty()) {
                        conflictFiles.add(fileName)
                    }
                }
            }

            // 如果没有提取到，尝试其他方式
            if (conflictFiles.isEmpty()) {
                // 使用正则表达式匹配文件名
                val pattern = Regex("todo_lists/[^\\s]+|notes/[^\\s]+")
                val matches = pattern.findAll(error)
                matches.forEach { match ->
                    conflictFiles.add(match.value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取冲突文件失败", e)
        }

        // 如果没有找到任何文件，添加默认的冲突文件
        if (conflictFiles.isEmpty()) {
            conflictFiles.add("todo_lists/todos.md")
        }

        return conflictFiles
    }

    /**
     * 处理合并冲突（按更新时间采用最新的）
     */
    /**
     * 处理合并冲突
     */
    private fun handleMergeConflict(conflictResult: SyncPullResult.Conflict) {
        try {
            syncListener?.onSyncProgress("正在处理冲突...")

            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val conflictFiles = conflictResult.conflictFiles

            if (conflictFiles.isEmpty()) {
                Log.w(TAG, "没有冲突文件信息")
                return
            }

            Log.d(TAG, "处理 ${conflictFiles.size} 个冲突文件")

            conflictFiles.forEach { relativePath ->
                try {
                    val file = File(repoDir, relativePath)
                    if (file.exists()) {
                        Log.d(TAG, "处理冲突文件: $relativePath")

                        // 检查文件类型并处理
                        if (relativePath.startsWith("todo_lists/")) {
                            handleTodoListConflict(file, relativePath)
                        } else if (relativePath.startsWith("notes/")) {
                            handleNoteConflict(file, relativePath)
                        } else {
                            // 默认处理方式：使用本地版本
                            resolveConflictWithLocalVersion(file)
                        }
                    } else {
                        Log.w(TAG, "冲突文件不存在: $relativePath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理冲突文件失败: $relativePath", e)
                }
            }

            syncListener?.onSyncProgress("冲突处理完成")
        } catch (e: Exception) {
            Log.e(TAG, "处理冲突失败", e)
            syncListener?.onSyncError("冲突处理失败: ${e.message}")
        }
    }

    /**
     * 处理待办列表冲突
     */
    private fun handleTodoListConflict(file: File, relativePath: String) {
        try {
            val content = file.readText()

            // 检查是否有Git冲突标记
            if (content.contains("<<<<<<<") &&
                content.contains("=======") &&
                content.contains(">>>>>>>")) {

                Log.d(TAG, "检测到待办列表冲突，正在解析...")

                // 解析冲突内容
                val conflictParts = parseGitConflict(content)
                if (conflictParts.size >= 2) {
                    val ourContent = conflictParts[0]  // 我们的版本
                    val theirContent = conflictParts[2] // 他们的版本

                    // 按更新时间合并
                    val mergedContent = mergeTodoListsByUpdateTime(ourContent, theirContent)

                    // 写回文件
                    file.writeText(mergedContent)

                    Log.d(TAG, "已解决待办列表冲突: $relativePath")
                } else {
                    // 无法解析冲突，使用本地版本
                    resolveConflictWithLocalVersion(file)
                }
            } else {
                Log.d(TAG, "文件没有冲突标记，跳过: $relativePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理待办列表冲突失败", e)
            // 失败时使用本地版本
            resolveConflictWithLocalVersion(file)
        }
    }

    /**
     * 合并待办列表（按更新时间）
     */
    private fun mergeTodoListsByUpdateTime(ourContent: String, theirContent: String): String {
        try {
            // 解析我们的待办事项
            val ourTodos = parseTodosFromMarkdown(ourContent)
            // 解析他们的待办事项
            val theirTodos = parseTodosFromMarkdown(theirContent)

            // 创建合并映射（基于UUID）
            val mergedMap = mutableMapOf<String, TodoItem>()

            // 添加他们的所有待办
            theirTodos.forEach { todo ->
                mergedMap[todo.uuid] = todo
            }

            // 合并我们的待办
            ourTodos.forEach { ourTodo ->
                val existingTodo = mergedMap[ourTodo.uuid]
                if (existingTodo == null) {
                    mergedMap[ourTodo.uuid] = ourTodo
                } else {
                    // 比较更新时间，保留最新的
                    val ourTime = parseTodoUpdateTime(ourTodo)
                    val theirTime = parseTodoUpdateTime(existingTodo)

                    if (ourTime >= theirTime) {
                        mergedMap[ourTodo.uuid] = ourTodo
                    }
                }
            }

            // 生成合并后的Markdown
            return generateTodoListMarkdown(mergedMap.values.toList())
        } catch (e: Exception) {
            Log.e(TAG, "合并待办列表失败", e)
            // 合并失败，使用我们的版本
            return ourContent
        }
    }

    /**
     * 从Markdown解析待办事项
     */
    private fun parseTodosFromMarkdown(content: String): List<TodoItem> {
        return try {
            val lines = content.lines()
            if (lines.size > 2) {
                lines.drop(2)
                    .filter { it.isNotBlank() }
                    .mapNotNull { TodoItem.fromMarkdownLine(it) }
                    .toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析待办Markdown失败", e)
            emptyList()
        }
    }

    /**
     * 解析待办更新时间
     */
    private fun parseTodoUpdateTime(todo: TodoItem): Long {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return dateFormat.parse(todo.updatedAt)?.time ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }

    /**
     * 生成待办列表Markdown
     */
    private fun generateTodoListMarkdown(todos: List<TodoItem>): String {
        val builder = StringBuilder()
        builder.append("# 待办事项\n\n")

        todos.forEach { todo ->
            builder.append("${todo.toMarkdownLine()}\n")
        }

        return builder.toString()
    }

    /**
     * 使用本地版本解决冲突（简单策略）
     */
    private fun resolveConflictWithLocalVersion(file: File) {
        try {
            val content = file.readText()

            // 如果有冲突标记，提取本地版本
            if (content.contains("<<<<<<<") &&
                content.contains("=======") &&
                content.contains(">>>>>>>")) {

                val conflictParts = parseGitConflict(content)
                if (conflictParts.size >= 1) {
                    val localContent = conflictParts[0]  // 本地版本在第一个
                    file.writeText(localContent)
                    Log.d(TAG, "已使用本地版本解决冲突: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "使用本地版本解决冲突失败", e)
        }
    }

    /**
     * 解析Git冲突内容
     */
    private fun parseGitConflict(content: String): List<String> {
        val result = mutableListOf<String>()

        try {
            // 使用正则表达式匹配完整的冲突块
            val pattern = Regex("<<<<<<<[^\\n]*\\n(.*?)=======\\n(.*?)>>>>>>>[^\\n]*", RegexOption.DOT_MATCHES_ALL)
            val matches = pattern.findAll(content)

            // 如果没有匹配到，返回整个内容作为第一个元素
            if (!matches.iterator().hasNext()) {
                result.add(content)
                return result
            }

            // 提取所有匹配的部分
            val matchList = matches.toList()
            val firstMatch = matchList.first()

            // 第一个冲突块之前的内容 + 本地版本
            val beforeFirst = content.substring(0, firstMatch.range.first)
            val ourPart = firstMatch.groupValues[1]
            val theirPart = firstMatch.groupValues[2]
            val afterLast = content.substring(firstMatch.range.last + 1)

            // 本地版本：冲突前的内容 + 本地部分 + 冲突后的内容
            result.add(beforeFirst + ourPart + afterLast)

            // 基础版本（如果有的话） - 这里简单处理
            result.add(beforeFirst + ourPart + afterLast) // 与本地版本相同

            // 远程版本：冲突前的内容 + 远程部分 + 冲突后的内容
            result.add(beforeFirst + theirPart + afterLast)

        } catch (e: Exception) {
            Log.e(TAG, "解析Git冲突失败", e)
            // 失败时返回原始内容
            result.add(content)
        }

        return result
    }

    /**
     * 解析笔记更新时间
     */
    private fun parseNoteUpdateTime(dateString: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            dateFormat.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "解析笔记更新时间失败", e)
            0L
        }
    }
    /**
     * 自动推送列表元数据变更
     */
    fun autoPushTodoLists(operation: String) {
        if (!::gitManager.isInitialized) {
            return
        }

        syncScope.launch {
            try {
                // 先将当前应用数据保存到Git目录
                saveCurrentDataToGit()

                // 推送
                val commitMessage = "$operation 列表 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"

                gitManager.commitAndPush(
                    commitMessage = commitMessage,
                    filePatterns = listOf("$DIR_TODO_LISTS/$FILE_METADATA"), // 只推送元数据文件
                    onSuccess = {
                        syncListener?.onSyncStatusChanged("列表元数据推送成功")
                    },
                    onError = { error ->
                        syncListener?.onSyncError("列表元数据推送失败: $error")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "自动推送列表元数据异常", e)
            }
        }
    }
    /**
     * 合并元数据JSON
     */
    private fun mergeMetadataJson(ourJson: String, theirJson: String): String {
        return try {
            val ourArray = org.json.JSONArray(ourJson)
            val theirArray = org.json.JSONArray(theirJson)

            // 使用ID作为唯一标识，合并两个数组
            val mergedMap = mutableMapOf<String, org.json.JSONObject>()

            // 添加他们的所有项目
            for (i in 0 until theirArray.length()) {
                val obj = theirArray.getJSONObject(i)
                val id = obj.getString("id")
                mergedMap[id] = obj
            }

            // 添加我们的项目（覆盖或新增）
            for (i in 0 until ourArray.length()) {
                val obj = ourArray.getJSONObject(i)
                val id = obj.getString("id")
                mergedMap[id] = obj
            }

            // 创建合并后的JSON数组
            val mergedArray = org.json.JSONArray()
            mergedMap.values.forEach { mergedArray.put(it) }

            mergedArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "合并元数据JSON失败", e)
            ourJson // 失败时返回我们的版本
        }
    }


    /**
     * 从Git目录加载数据到应用内存
     */
    private fun loadDataFromGitToMemory() {
        val repoDir = File(context.filesDir, GIT_REPO_DIR)

        try {
            // 1. 加载待办列表元数据
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)
            if (todoListsDir.exists()) {
                val metadataFile = File(todoListsDir, FILE_METADATA)
                if (metadataFile.exists()) {
                    val todoLists = readTodoListsMetadata(metadataFile)

                    // 更新待办列表管理器
                    todoListManager.cleanup()
                    todoLists.forEach { list ->
                        todoListManager.addExistingList(list)
                    }

                    // 设置当前选中的列表
                    val selectedList = todoLists.find { it.isSelected }
                    selectedList?.let {
                        todoListManager.setCurrentList(it.id)
                    }
                }
            }

            // 2. 加载当前选中的列表的待办事项
            val currentListId = todoListManager.getCurrentListId()
            val currentListFile = File(todoListsDir, todoListManager.getCurrentListFileName())
            if (currentListFile.exists()) {
                val todos = readTodosFromFile(currentListFile)

                // 确保所有待办的更新时间正确
                val updatedTodos = todos.map { todo ->
                    // 如果updatedAt是空的，设置为当前时间
                    if (todo.updatedAt.isEmpty() || todo.updatedAt == todo.createdAt) {
                        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        todo.copy(updatedAt = currentTime)
                    } else {
                        todo
                    }
                }

                todoManager.replaceAllTodos(updatedTodos)
            }

            // 3. 加载笔记数据
            val notesDir = File(repoDir, DIR_NOTES)
            if (notesDir.exists()) {
                val notes = readNotesFromDirectory(notesDir)
                noteManager.replaceAllNotes(notes)
            }

            // 4. 重新调度提醒
            todoManager.rescheduleAllReminders()

            Log.d(TAG, "数据已从Git目录加载到内存")
        } catch (e: Exception) {
            Log.e(TAG, "从Git目录加载数据失败", e)
        }
    }

    /**
     * 推送本地更改到远程
     */
    private suspend fun pushToRemote() {
        syncListener?.onSyncProgress("正在推送更新...")

        val commitMessage = "同步更新 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"

        return suspendCoroutine { continuation ->
            gitManager.commitAndPush(
                commitMessage = commitMessage,
                filePatterns = listOf("$DIR_TODO_LISTS/", "$DIR_NOTES/"),
                onSuccess = {
                    syncScope.launch {
                        syncListener?.onSyncSuccess("同步成功")
                        continuation.resume(Unit)
                    }
                },
                onError = { error ->
                    syncScope.launch {
                        // 推送失败，先拉取一次再尝试推送
                        retryPushWithPull(continuation)
                    }
                }
            )
        }
    }

    /**
     * 重试推送（先拉取再推送）
     */
    private suspend fun retryPushWithPull(continuation: kotlin.coroutines.Continuation<Unit>) {
        syncListener?.onSyncProgress("推送失败，正在重试...")

        // 先拉取
        val pullResult = pullWithGitMerge()

        when (pullResult) {
            is SyncPullResult.Success, is SyncPullResult.Conflict -> {
                // 拉取成功或解决冲突后，重新推送
                if (pullResult is SyncPullResult.Conflict) {
                    handleMergeConflict(pullResult)
                }

                loadDataFromGitToMemory()

                syncListener?.onSyncProgress("重新推送...")
                gitManager.commitAndPush(
                    commitMessage = "同步更新（重试） - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}",
                    filePatterns = listOf("$DIR_TODO_LISTS/", "$DIR_NOTES/"),
                    onSuccess = {
                        syncScope.launch {
                            syncListener?.onSyncSuccess("同步成功（重试后）")
                            continuation.resume(Unit)
                        }
                    },
                    onError = { error ->
                        syncScope.launch {
                            syncListener?.onSyncError("推送失败（重试后）: $error")
                            continuation.resume(Unit)
                        }
                    }
                )
            }
            is SyncPullResult.Error -> {
                syncScope.launch {
                    syncListener?.onSyncError("重试失败，无法拉取: ${pullResult.errorMessage}")
                    continuation.resume(Unit)
                }
            }

            else -> {}
        }
    }

    /**
     * 自动推送待办变更
     */
    fun autoPushTodo(operation: String, todo: TodoItem? = null) {
        if (!::gitManager.isInitialized) {
            return
        }

        syncScope.launch {
            try {
                // 先将当前应用数据保存到Git目录
                saveCurrentDataToGit()

                // 推送
                val commitMessage = "$operation 待办 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"

                gitManager.commitAndPush(
                    commitMessage = commitMessage,
                    filePatterns = listOf("$DIR_TODO_LISTS/"),
                    onSuccess = {
                        syncListener?.onSyncStatusChanged("自动推送成功")
                    },
                    onError = { error ->
                        syncListener?.onSyncError("自动推送失败: $error")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "自动推送待办异常", e)
            }
        }
    }

    /**
     * 自动推送笔记变更
     */
    fun autoPushNote(operation: String, note: NoteItem? = null) {
        if (!::gitManager.isInitialized) {
            return
        }

        syncScope.launch {
            try {
                // 先将当前应用数据保存到Git目录
                saveCurrentDataToGit()

                // 推送
                val commitMessage = if (operation.contains("删除") && note != null) {
                    "删除笔记: ${note.title}"
                } else {
                    "$operation 笔记 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"
                }

                gitManager.commitAndPush(
                    commitMessage = commitMessage,
                    filePatterns = listOf("$DIR_NOTES/"),
                    onSuccess = {
                        syncListener?.onSyncStatusChanged("自动推送成功")
                    },
                    onError = { error ->
                        syncListener?.onSyncError("自动推送失败: $error")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "自动推送笔记异常", e)
            }
        }
    }

    /**
     * 将当前应用数据保存到Git目录
     */
    private fun saveCurrentDataToGit() {
        val repoDir = File(context.filesDir, GIT_REPO_DIR)

        try {
            // 1. 保存待办列表数据
            val todoLists = todoListManager.getAllLists()
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)
            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }
            // 重要：确保保存最新的列表选中状态
            Log.d(TAG, "保存列表元数据，共 ${todoLists.size} 个列表")
            todoLists.forEach { list ->
                Log.d(TAG, "列表: ${list.name}, isSelected: ${list.isSelected}")
            }
            // 保存元数据
            val metadataFile = File(todoListsDir, FILE_METADATA)
            saveTodoListsMetadata(todoLists, metadataFile)

            // 保存每个列表的待办事项
            todoLists.forEach { list ->
                val listFile = File(todoListsDir, list.fileName)
                val todos = if (list.isSelected) {
                    todoManager.getAllTodos()
                } else {
                    // 对于非当前列表，从本地文件读取
                    val localFile = todoListManager.getTodoFileForList(list.id)
                    readTodosFromFile(localFile)
                }
                saveTodosToFile(todos, listFile)
            }

            // 2. 保存笔记数据
            val notes = noteManager.getAllNotes()
            val notesDir = File(repoDir, DIR_NOTES)
            if (!notesDir.exists()) {
                notesDir.mkdirs()
            }

            // 清空笔记目录
            notesDir.listFiles()?.forEach { it.delete() }

            // 保存笔记 - 使用 NoteManager 相同的逻辑获取文件名
            notes.forEach { note ->
                // 获取与 NoteManager 一致的文件名
                val fileName = note.getSafeFileName() + ".md"
                val noteFile = File(notesDir, fileName)
                noteFile.writeText(note.toMarkdown())
            }

            Log.d(TAG, "当前应用数据已保存到Git目录")
        } catch (e: Exception) {
            Log.e(TAG, "保存应用数据到Git目录失败", e)
        }
    }

    /**
     * 删除笔记（从Git目录）
     */
    fun deleteNoteFromGit(note: NoteItem) {
        if (!::gitManager.isInitialized) {
            return
        }

        syncScope.launch {
            try {
                // 由于文件名现在是基于标题的，我们需要找到对应的文件名
                // 注意：这里假设文件名是基于标题的，可能需要在NoteManager中提供一个获取文件名的方法
                // 或者我们可以直接删除所有以该笔记ID开头的文件

                // 先获取所有可能的文件名
                val noteDir = File(context.filesDir, "$GIT_REPO_DIR/$DIR_NOTES")
                val noteFiles = noteDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".md")
                } ?: emptyArray()

                var fileNameToDelete = ""
                for (file in noteFiles) {
                    try {
                        val content = file.readText()
                        val fileNote = NoteItem.fromMarkdown(content)
                        if (fileNote?.id == note.id) {
                            fileNameToDelete = file.name
                            break
                        }
                    } catch (e: Exception) {
                        // 忽略解析失败的文件
                    }
                }

                if (fileNameToDelete.isNotEmpty()) {
                    gitManager.removeFile(
                        filePattern = "$DIR_NOTES/$fileNameToDelete",
                        commitMessage = "删除笔记: ${note.title}",
                        onSuccess = {
                            syncListener?.onSyncStatusChanged("删除同步成功")
                        },
                        onError = { error ->
                            syncListener?.onSyncError("删除同步失败: $error")
                        }
                    )
                } else {
                    Log.w(TAG, "未找到要删除的笔记文件: ID=${note.id}, Title=${note.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除笔记同步异常", e)
            }
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        syncScope.cancel()
        if (::gitManager.isInitialized) {
            gitManager.cleanup()
        }
    }

    // ============= 辅助方法 =============

    /**
     * 读取待办列表元数据
     */
    private fun readTodoListsMetadata(file: File): List<TodoList> {
        return try {
            if (!file.exists()) {
                return emptyList()
            }

            val json = file.readText()
            val jsonArray = org.json.JSONArray(json)
            val result = mutableListOf<TodoList>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val list = TodoList(
                    id = jsonObject.getString("id"),
                    name = jsonObject.getString("name"),
                    fileName = jsonObject.getString("fileName"),
                    todoCount = jsonObject.getInt("todoCount"),
                    activeCount = jsonObject.getInt("activeCount"),
                    createdAt = jsonObject.getString("createdAt"),
                    isDefault = jsonObject.getBoolean("isDefault"),
                    isSelected = jsonObject.getBoolean("isSelected")
                )
                result.add(list)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "读取待办列表元数据失败", e)
            emptyList()
        }
    }

    /**
     * 保存待办列表元数据
     */
    private fun saveTodoListsMetadata(todoLists: List<TodoList>, file: File) {
        try {
            val jsonArray = org.json.JSONArray()

            todoLists.forEach { list ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", list.id)
                    put("name", list.name)
                    put("fileName", list.fileName)
                    put("todoCount", list.todoCount)
                    put("activeCount", list.activeCount)
                    put("createdAt", list.createdAt)
                    put("isDefault", list.isDefault)
                    put("isSelected", list.isSelected)
                }
                jsonArray.put(jsonObject)
            }

            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存待办列表元数据失败", e)
            throw e
        }
    }

    /**
     * 从文件读取待办事项
     */
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

    /**
     * 从目录读取笔记
     */
    private fun readNotesFromDirectory(directory: File): List<NoteItem> {
        return try {
            val noteFiles = directory.listFiles { file ->
                file.isFile && file.name.endsWith(".md")
            } ?: emptyArray()

            val notes = mutableListOf<NoteItem>()
            noteFiles.forEach { file ->
                try {
                    val content = file.readText()
                    val note = NoteItem.fromMarkdown(content)
                    note?.let { notes.add(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "读取笔记文件失败: ${file.name}", e)
                }
            }

            notes.sortedBy { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "读取笔记目录失败", e)
            emptyList()
        }
    }

    /**
     * 保存待办事项到文件
     */
    private fun saveTodosToFile(todos: List<TodoItem>, file: File) {
        try {
            file.bufferedWriter().use { writer ->
                writer.write("# 待办事项\n\n")
                todos.forEach { todo ->
                    writer.write("${todo.toMarkdownLine()}\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存待办文件失败", e)
            throw e
        }
    }
    /**
     * 处理笔记冲突
     */
    private fun handleNoteConflict(file: File, relativePath: String) {
        try {
            val content = file.readText()

            // 检查是否有Git冲突标记
            if (content.contains("<<<<<<<") &&
                content.contains("=======") &&
                content.contains(">>>>>>>")) {

                Log.d(TAG, "检测到笔记冲突，正在解析...")

                // 解析冲突内容
                val conflictParts = parseGitConflict(content)
                if (conflictParts.size >= 2) {
                    val ourContent = conflictParts[0]  // 我们的版本
                    val theirContent = conflictParts[2] // 他们的版本

                    // 按UUID和更新时间合并笔记
                    val mergedContent = mergeNotesByUuidAndTime(ourContent, theirContent)

                    // 写回文件
                    file.writeText(mergedContent)

                    Log.d(TAG, "已解决笔记冲突: $relativePath")
                } else {
                    // 无法解析冲突，使用本地版本
                    resolveConflictWithLocalVersion(file)
                }
            } else {
                Log.d(TAG, "文件没有冲突标记，跳过: $relativePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理笔记冲突失败", e)
            // 失败时使用本地版本
            resolveConflictWithLocalVersion(file)
        }
    }

    /**
     * 合并笔记（按UUID和更新时间）
     */
    private fun mergeNotesByUuidAndTime(ourContent: String, theirContent: String): String {
        try {
            // 解析我们的笔记
            val ourNote = NoteItem.fromMarkdown(ourContent)
            // 解析他们的笔记
            val theirNote = NoteItem.fromMarkdown(theirContent)

            if (ourNote == null && theirNote == null) {
                return ourContent
            } else if (ourNote == null) {
                return theirContent
            } else if (theirNote == null) {
                return ourContent
            }

            // 比较更新时间，保留最新的
            val ourTime = parseNoteUpdateTime(ourNote.updatedAt)
            val theirTime = parseNoteUpdateTime(theirNote.updatedAt)

            return if (ourTime >= theirTime) {
                // 使用我们的版本，但要确保UUID正确
                ourContent
            } else {
                // 使用他们的版本
                theirContent
            }
        } catch (e: Exception) {
            Log.e(TAG, "合并笔记失败", e)
            // 合并失败，使用我们的版本
            return ourContent
        }
    }
    /**
     * 合并笔记（按更新时间）
     */
    private fun mergeNotesByUpdateTime(ourContent: String, theirContent: String): String {
        try {
            // 解析我们的笔记
            val ourNote = NoteItem.fromMarkdown(ourContent)
            // 解析他们的笔记
            val theirNote = NoteItem.fromMarkdown(theirContent)

            if (ourNote == null && theirNote == null) {
                return ourContent
            } else if (ourNote == null) {
                return theirContent
            } else if (theirNote == null) {
                return ourContent
            }

            // 比较更新时间，保留最新的
            val ourTime = parseNoteUpdateTime(ourNote.updatedAt)
            val theirTime = parseNoteUpdateTime(theirNote.updatedAt)

            return if (ourTime >= theirTime) ourContent else theirContent
        } catch (e: Exception) {
            Log.e(TAG, "合并笔记失败", e)
            // 合并失败，使用我们的版本
            return ourContent
        }
    }

    /**
     * 解析笔记更新时间
     */



    // ============= 数据类 =============

    /**
     * 拉取结果封装
     */
    private sealed class SyncPullResult {
        data class Success(val result: JPullResult) : SyncPullResult()
        data class Conflict(val conflictFiles: List<String>, val mergeResult: MergeResult?) : SyncPullResult()
        data class Error(val errorMessage: String) : SyncPullResult()
    }
}