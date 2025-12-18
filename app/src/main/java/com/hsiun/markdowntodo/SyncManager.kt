package com.hsiun.markdowntodo

import NoteItem
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
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
    private val todoListManager: TodoListManager,

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

    private suspend fun pullAndMergeChanges() {
        try {
            val success = suspendCoroutine<Boolean> { continuation ->
                gitManager.pullChanges(
                    onSuccess = { pullResult ->
                        syncScope.launch {
                            try {
                                syncListener?.onSyncProgress("拉取成功，正在合并数据...")

                                val repoDir = File(context.filesDir, GIT_REPO_DIR)
                                val todoListsDir = File(repoDir, "todo_lists")

                                // 1. 首先确保 todo_lists 目录存在
                                if (!todoListsDir.exists()) {
                                    todoListsDir.mkdirs()
                                    Log.d(TAG, "创建 todo_lists 目录")
                                }

                                // 2. 从 todo_lists 目录读取列表元数据或创建列表
                                val remoteTodoLists = readTodoListsMetadata(repoDir)
                                Log.d(TAG, "从云端读取到 ${remoteTodoLists.size} 个待办列表")

                                // 获取本地列表
                                val localLists = todoListManager.getAllLists()
                                Log.d(TAG, "本地有 ${localLists.size} 个待办列表")

                                // 3. 同步列表结构（合并新增的列表）
                                if (remoteTodoLists.isNotEmpty()) {
                                    syncTodoListsStructure(localLists, remoteTodoLists)
                                } else {
                                    Log.d(TAG, "云端没有待办列表数据，使用本地列表")
                                    // 如果云端没有列表，使用本地列表创建元数据并上传
                                    saveTodoListsMetadata(localLists, repoDir)
                                }

                                // 4. 现在获取更新后的本地列表（包含新增的列表）
                                val updatedLocalLists = todoListManager.getAllLists()
                                Log.d(TAG, "同步后本地有 ${updatedLocalLists.size} 个待办列表")

                                // 5. 同步每个列表的待办事项（只处理 todo_lists 目录下的文件）
                                updatedLocalLists.forEach { list ->
                                    try {
                                        // 读取远程列表文件（位于 todo_lists 目录）
                                        val remoteListFile = File(todoListsDir, list.fileName)
                                        if (remoteListFile.exists()) {
                                            // 读取远程待办事项
                                            val remoteTodos = readTodosFromFile(remoteListFile)

                                            // 读取本地待办事项
                                            val localFile = todoListManager.getTodoFileForList(list.id)
                                            val localTodos = if (localFile.exists()) {
                                                readTodosFromFile(localFile)
                                            } else {
                                                emptyList()
                                            }

                                            // 智能合并
                                            val mergedTodos = mergeTodosIntelligently(localTodos, remoteTodos)

                                            // 保存合并后的待办事项到本地文件
                                            saveTodosToFile(mergedTodos, localFile)

                                            // 如果这是当前选中的列表，更新内存中的待办事项
                                            if (list.isSelected) {
                                                todoManager.replaceAllTodos(mergedTodos)
                                            }

                                            // 更新列表统计
                                            val total = mergedTodos.size
                                            val active = mergedTodos.count { !it.isCompleted }
                                            todoListManager.updateListCount(list.id, total, active)

                                            Log.d(TAG, "列表同步完成: ${list.name}, 本地 ${localTodos.size} 条, 远程 ${remoteTodos.size} 条, 合并后 ${mergedTodos.size} 条")
                                        } else {
                                            Log.w(TAG, "远程列表文件不存在: ${list.fileName}")

                                            // 如果远程文件不存在，可能是新创建的列表还没有同步
                                            // 使用本地数据
                                            val localFile = todoListManager.getTodoFileForList(list.id)
                                            if (localFile.exists()) {
                                                val localTodos = readTodosFromFile(localFile)
                                                val total = localTodos.size
                                                val active = localTodos.count { !it.isCompleted }
                                                todoListManager.updateListCount(list.id, total, active)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "同步列表失败: ${list.name}", e)
                                    }
                                }

                                // 6. 合并笔记
                                val mergedNotes = mergeNotesIntelligently()
                                noteManager.replaceAllNotes(mergedNotes)

                                // 7. 重新调度所有提醒
                                withContext(Dispatchers.Main) {
                                    val mainActivity = context as? MainActivity
                                    mainActivity?.todoManager?.rescheduleAllReminders()
                                }

                                // 8. 准备要提交的文件（包含更新后的列表结构和待办事项）
                                prepareFilesForCommit(emptyList(), mergedNotes)

                                syncListener?.onSyncProgress("合并完成")

                                // 9. 提交合并后的更改
                                val commitMessage = "同步合并 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"

                                // 只提交 todo_lists 和 notes 目录
                                gitManager.commitAndPush(
                                    commitMessage = commitMessage,
                                    filePatterns = listOf("todo_lists/", "notes/"),
                                    onSuccess = {
                                        syncListener?.onSyncSuccess("同步成功")
                                        continuation.resume(true)
                                    },
                                    onError = { error ->
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
                        Log.e(TAG, "拉取失败: $error")
                        syncListener?.onSyncError("拉取失败: $error")
                        continuation.resume(false)
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


    // 添加保存待办事项到文件的方法
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
    private fun extractListNameFromFile(fileName: String): String {
        return when {
            fileName == "todos.md" -> "默认待办"
            fileName.endsWith("_todos.md") -> fileName.removeSuffix("_todos.md")
            else -> fileName.removeSuffix(".md")
        }
    }

    private suspend fun prepareFilesForCommit(todos: List<TodoItem>, notes: List<NoteItem>) {
        withContext(Dispatchers.IO) {
            val repoDir = File(context.filesDir, GIT_REPO_DIR)

            // 确保 todo_lists 目录存在
            val todoListsDir = File(repoDir, "todo_lists")
            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            // 获取所有待办列表
            val todoLists = todoListManager.getAllLists()
            Log.d(TAG, "准备保存 ${todoLists.size} 个待办列表到Git仓库")

            // 1. 首先清空 todo_lists 目录（除了 metadata.json）
            if (todoListsDir.exists() && todoListsDir.isDirectory) {
                todoListsDir.listFiles()?.forEach { file ->
                    if (file.name != "metadata.json") {
                        file.delete()
                    }
                }
            }

            // 2. 保存待办列表元数据
            saveTodoListsMetadata(todoLists, repoDir)

            // 3. 保存每个列表的待办事项到 todo_lists 目录
            todoLists.forEach { list ->
                try {
                    // 读取该列表的待办事项
                    val listFile = todoListManager.getTodoFileForList(list.id)
                    if (listFile.exists()) {
                        // 读取列表中的待办事项
                        val listTodos = readTodosFromFile(listFile)

                        // 保存到Git仓库的 todo_lists 目录
                        val remoteListFile = File(todoListsDir, list.fileName)
                        saveTodosToGitRepo(listTodos, remoteListFile)

                        Log.d(TAG, "已保存待办列表 '${list.name}' 到: ${remoteListFile.name}, 待办数: ${listTodos.size}")
                    } else {
                        Log.w(TAG, "待办列表文件不存在: ${list.name}, 文件: ${list.fileName}")
                        // 创建空文件
                        val remoteListFile = File(todoListsDir, list.fileName)
                        saveTodosToGitRepo(emptyList(), remoteListFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "保存待办列表失败: ${list.name}", e)
                }
            }

            // 4. 保存笔记
            val notesDir = File(repoDir, "notes")
            if (!notesDir.exists()) {
                notesDir.mkdirs()
            }

            // 先清空notes目录
            if (notesDir.exists() && notesDir.isDirectory) {
                notesDir.listFiles()?.forEach { it.delete() }
            }

            // 保存笔记文件
            notes.forEach { note ->
                try {
                    val noteFile = File(notesDir, "note_${note.id}_${note.uuid}.md")
                    val markdown = note.toMarkdown()
                    noteFile.writeText(markdown)
                    Log.d(TAG, "已保存笔记到: ${noteFile.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "保存笔记失败: ${note.title}", e)
                }
            }

            Log.d(TAG, "文件准备完成: ${todoLists.size} 个待办列表, ${notes.size} 个笔记")
        }
    }
    // 新增方法：保存待办列表元数据
    private fun saveTodoListsMetadata(todoLists: List<TodoList>, repoDir: File) {
        try {
            val todoListsDir = File(repoDir, "todo_lists")
            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            val metadataFile = File(todoListsDir, "metadata.json")
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

            metadataFile.writeText(jsonArray.toString())
            Log.d(TAG, "已保存待办列表元数据到: ${metadataFile.absolutePath}, 包含 ${todoLists.size} 个列表")
        } catch (e: Exception) {
            Log.e(TAG, "保存待办列表元数据失败", e)
        }
    }

    // 新增方法：从文件名提取列表名称
    private fun extractListNameFromFileName(fileName: String): String {
        return when {
            fileName == "todos.md" -> "默认待办"
            fileName.endsWith("_todos.md") -> {
                val baseName = fileName.removeSuffix("_todos.md")
                if (baseName.isNotEmpty()) {
                    baseName
                } else {
                    "未命名列表"
                }
            }
            fileName.endsWith(".md") -> {
                val baseName = fileName.removeSuffix(".md")
                if (baseName.isNotEmpty()) {
                    baseName
                } else {
                    "未命名列表"
                }
            }
            else -> "未命名列表"
        }
    }

    // 新增方法：读取待办列表元数据
    private fun readTodoListsMetadata(repoDir: File): List<TodoList> {
        return try {
            val todoListsDir = File(repoDir, "todo_lists")

            // 如果 todo_lists 目录不存在，返回空列表
            if (!todoListsDir.exists() || !todoListsDir.isDirectory) {
                Log.w(TAG, "todo_lists 目录不存在")
                return emptyList()
            }

            val metadataFile = File(todoListsDir, "metadata.json")

            if (metadataFile.exists()) {
                // 读取现有的元数据文件
                val json = metadataFile.readText()
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

                Log.d(TAG, "从 metadata.json 读取到 ${result.size} 个待办列表元数据")
                return result
            } else {
                // 如果没有元数据文件，扫描目录下的 .md 文件并创建列表
                Log.w(TAG, "待办列表元数据文件不存在，扫描目录创建列表")
                return createListsFromFiles(todoListsDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取待办列表元数据失败", e)
            emptyList()
        }
    }

    // 新增方法：根据文件创建列表
// 修改 createListsFromFiles 方法，确保不会重复创建默认待办列表
    private fun createListsFromFiles(todoListsDir: File): List<TodoList> {
        val result = mutableListOf<TodoList>()

        try {
            // 扫描 todo_lists 目录下的所有 .md 文件
            val files = todoListsDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md") && file.name != "metadata.json"
            } ?: emptyArray()

            Log.d(TAG, "扫描到 ${files.size} 个待办文件")

            // 首先检查是否有默认待办文件 (todos.md)
            var hasDefaultTodoFile = false

            files.forEach { file ->
                if (file.name == "todos.md") {
                    hasDefaultTodoFile = true
                }
            }

            // 如果没有默认待办文件，创建一个空的默认待办文件
            if (!hasDefaultTodoFile) {
                Log.d(TAG, "没有找到默认待办文件，创建空的 todos.md")
                val defaultFile = File(todoListsDir, "todos.md")
                saveTodosToGitRepo(emptyList(), defaultFile)
            }

            // 重新扫描文件，现在应该包含 todos.md
            val allFiles = todoListsDir.listFiles { file ->
                file.isFile && file.name.endsWith(".md") && file.name != "metadata.json"
            } ?: emptyArray()

            // 创建列表
            allFiles.forEachIndexed { index, file ->
                try {
                    val fileName = file.name

                    // 跳过重复文件
                    if (result.any { it.fileName == fileName }) {
                        Log.d(TAG, "跳过重复文件: $fileName")
                        return@forEachIndexed
                    }

                    // 从文件名提取列表名称
                    val listName = extractListNameFromFileName(fileName)

                    // 检查是否是默认列表
                    val isDefault = fileName == "todos.md"

                    // 读取待办数量
                    val todos = readTodosFromFile(file)
                    val todoCount = todos.size
                    val activeCount = todos.count { !it.isCompleted }

                    // 创建列表
                    val list = TodoList(
                        name = listName,
                        fileName = fileName,
                        todoCount = todoCount,
                        activeCount = activeCount,
                        isDefault = isDefault,
                        isSelected = isDefault // 默认列表默认选中
                    )

                    result.add(list)
                    Log.d(TAG, "从文件创建列表: ${list.name}, 文件: $fileName, 待办数: $todoCount")
                } catch (e: Exception) {
                    Log.e(TAG, "从文件创建列表失败: ${file.name}", e)
                }
            }

            // 保存元数据文件，以便下次使用
            if (result.isNotEmpty()) {
                saveTodoListsMetadata(result, todoListsDir.parentFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "从文件创建列表失败", e)
        }

        return result
    }
    // 新增方法：同步待办列表结构
    private fun syncTodoListsStructure(localLists: List<TodoList>, remoteLists: List<TodoList>) {
        Log.d(TAG, "开始同步待办列表结构，远程 ${remoteLists.size} 个，本地 ${localLists.size} 个")

        // 创建本地列表ID的映射
        val localListMap = localLists.associateBy { it.id }

        // 处理每个远程列表
        remoteLists.forEach { remoteList ->
            val localList = localListMap[remoteList.id]

            if (localList == null) {
                // 本地没有这个列表，创建它
                Log.d(TAG, "创建新的待办列表: ${remoteList.name}")

                // 使用TodoListManager的addExistingList方法
                val success = todoListManager.addExistingList(remoteList)
                if (success) {
                    Log.d(TAG, "已添加待办列表: ${remoteList.name}")
                } else {
                    Log.w(TAG, "添加待办列表失败: ${remoteList.name}")
                }
            } else {
                // 本地已有这个列表，更新信息
                Log.d(TAG, "更新现有列表: ${remoteList.name}")

                // 使用TodoListManager的updateListInfo方法
                todoListManager.updateListInfo(
                    localList.id,
                    remoteList.name,
                    remoteList.fileName,
                    remoteList.todoCount,
                    remoteList.activeCount
                )
            }
        }

        Log.d(TAG, "待办列表结构同步完成")
    }

    // 修改 autoPushTodo 方法，提交 todo_lists 目录
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
                // 准备要提交的文件
                prepareFilesForCommit(emptyList(), noteManager.getAllNotes())

                gitManager.commitAndPush(
                    commitMessage = "$operation 待办事项 - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}",
                    filePatterns = listOf("todo_lists/"),
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

    private fun mergeTodosIntelligently(localTodos: List<TodoItem>, remoteTodos: List<TodoItem>): List<TodoItem> {
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
                // 优先保留提醒设置
                val mergedTodo = if (existingTodo.remindTime > 0 || existingTodo.nextRemindTime > 0) {
                    // 远程有提醒设置，使用远程的
                    existingTodo.copy(
                        title = localTodo.title, // 保留最新的标题
                        isCompleted = if (existingTodo.isCompleted) true else localTodo.isCompleted // 完成状态优先已完成
                    )
                } else if (localTodo.remindTime > 0 || localTodo.nextRemindTime > 0) {
                    // 本地有提醒设置，使用本地的
                    localTodo.copy(
                        title = existingTodo.title, // 保留标题
                        isCompleted = if (localTodo.isCompleted) true else existingTodo.isCompleted
                    )
                } else {
                    // 都没有提醒设置，合并其他字段
                    localTodo.copy(
                        title = existingTodo.title, // 保留标题
                        isCompleted = if (existingTodo.isCompleted) true else localTodo.isCompleted
                    )
                }
                mergedMap[localTodo.uuid] = mergedTodo
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
                if (operation.contains("删除") && note != null) {
                    Log.d(TAG, "处理删除笔记操作: ${note.title}")
                    deleteNoteFromGit(note)  // 直接调用专门的删除方法
                    return@launch
                }
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


                // 检查是否有笔记文件需要推送
                val hasNotesFiles = remoteNotesDir.exists() &&
                        remoteNotesDir.listFiles()?.any { it.isFile && it.name.endsWith(".md") } == true

                // 准备要添加的文件模式
                val filePatterns = mutableListOf<String>()


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
                    // 从文件名解析ID和UUID
                    val (id, uuid) = parseIdAndUuidFromFilename(file.name)

                    if (id == -1 || uuid.isEmpty()) {
                        Log.w(TAG, "文件名解析失败: ${file.name}")
                        return@forEach
                    }


                } catch (e: Exception) {
                    Log.e(TAG, "读取远程笔记文件失败: ${file.name}", e)
                }
            }
        }



        val mergedMap = mutableMapOf<String, NoteItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 先添加本地所有笔记（本地优先）
        localNotes.forEach { localNote ->
            mergedMap[localNote.uuid] = localNote
        }

        // 然后处理远程的笔记
        remoteNotes.forEach { remoteNote ->
            val existingNote = mergedMap[remoteNote.uuid]

            if (existingNote == null) {
                // 远程有而本地没有的笔记，添加
                mergedMap[remoteNote.uuid] = remoteNote
                Log.d(TAG, "添加远程笔记: ID=${remoteNote.id}, 标题='${remoteNote.title}'")
            } else {
                // 两者都存在，根据更新时间判断
                try {
                    val localTime = dateFormat.parse(existingNote.updatedAt) ?: Date(0)
                    val remoteTime = dateFormat.parse(remoteNote.updatedAt) ?: Date(0)


                    // 优先使用更新时间更晚的
                    if (remoteTime.after(localTime)) {
                        mergedMap[remoteNote.uuid] = remoteNote
                    } else if (localTime.after(remoteTime)) {
                        // 已经使用本地版本
                    } else {
                        // 时间相等，使用内容更长的（通常是更新后的）
                        if (remoteNote.content.length > existingNote.content.length) {
                            Log.d(TAG, "  远程内容更长，使用远程版本")
                            mergedMap[remoteNote.uuid] = remoteNote
                        } else {
                            Log.d(TAG, "  本地内容更长或相等，使用本地版本")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "解析笔记时间失败，使用本地版本: ${e.message}")
                    // 解析失败时，优先保留本地
                }
            }
        }

        Log.d(TAG, "合并后笔记数量: ${mergedMap.size}")
        return mergedMap.values.sortedBy { it.id }
    }

    // 使用文件名解析ID和UUID的辅助方法
    fun parseIdAndUuidFromFilename(filename: String): Pair<Int, String> {
        return try {
            val pattern = Regex("note_(\\d+)_([a-f0-9-]+)\\.md")
            val match = pattern.find(filename)

            if (match != null && match.groupValues.size >= 3) {
                val id = match.groupValues[1].toInt()
                val uuid = match.groupValues[2]
                Pair(id, uuid)
            } else {
                Pair(-1, "")
            }
        } catch (e: Exception) {
            Pair(-1, "")
        }
    }

    fun deleteNoteFromGit(note: NoteItem) {
        Log.d(TAG, "deleteNoteFromGit: 开始删除笔记, ID=${note.id}, UUID=${note.uuid}")

        if (!::gitManager.isInitialized) {
            Log.w(TAG, "GitManager未初始化")
            return
        }

        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val gitConfig = File(repoDir, ".git/config")

        if (!gitConfig.exists()) {
            Log.w(TAG, "Git仓库不存在")
            return
        }

        syncScope.launch {
            try {
                // 使用专门的Git删除方法
                gitManager.removeFile(
                    filePattern = "notes/note_${note.id}_${note.uuid}.md",
                    commitMessage = "删除笔记: ${note.title}",
                    onSuccess = {
                        Log.d(TAG, "删除笔记同步成功: ${note.title}")
                        syncScope.launch {
                            withContext(Dispatchers.Main) {
                                syncListener?.onSyncProgress("删除笔记同步成功")
                                syncListener?.onSyncStatusChanged("✅")
                                Toast.makeText(context, "笔记已从云端删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "删除笔记同步失败: $error")
                        syncScope.launch {
                            withContext(Dispatchers.Main) {
                                syncListener?.onSyncError("删除同步失败: $error")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "删除笔记同步异常", e)
            }
        }
    }
}