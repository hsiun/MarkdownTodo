package com.hsiun.markdowntodo.data.manager

import android.content.Context
import android.util.Log
import com.hsiun.markdowntodo.data.model.NoteItem
import com.hsiun.markdowntodo.data.model.TodoItem
import com.hsiun.markdowntodo.data.model.TodoList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        private const val DIR_IMAGES = "images"
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
     * 列出远程仓库中的笔记文件（通过Git ls-tree命令）
     */
    private fun listRemoteNoteFiles(): List<String> {
        return try {
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val gitDir = File(repoDir, ".git")
            if (!gitDir.exists()) {
                Log.w(TAG, "Git目录不存在，无法列出远程文件")
                return emptyList()
            }

            val repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .build()

            repository.use { repo ->
                // 尝试多个可能的分支名称
                val possibleBranches = listOf("main", "master", "origin/main", "origin/master")
                var ref: Ref? = null
                var branchName = ""

                for (branch in possibleBranches) {
                    ref = repo.findRef("refs/remotes/origin/$branch")
                        ?: repo.findRef("refs/heads/$branch")
                        ?: repo.findRef("refs/remotes/$branch")
                    if (ref != null) {
                        branchName = branch
                        Log.d(TAG, "找到分支引用: $branch")
                        break
                    }
                }

                // 如果还是找不到，尝试使用HEAD
                if (ref == null) {
                    ref = repo.findRef("HEAD")
                    if (ref != null) {
                        Log.d(TAG, "使用HEAD引用")
                    }
                }

                if (ref == null) {
                    Log.w(TAG, "无法找到任何分支引用")
                    return emptyList()
                }

                // 获取ref指向的对象
                val objectId = ref.objectId
                if (objectId == null) {
                    Log.w(TAG, "引用没有对象ID")
                    return emptyList()
                }

                // 解析对象，如果是commit，需要获取其tree
                val objectReader = repo.newObjectReader()
                val objectLoader = objectReader.open(objectId)
                val treeId = if (objectLoader.type == Constants.OBJ_COMMIT) {
                    // 如果是commit对象，需要解析出tree对象
                    val commit = RevWalk(repo).parseCommit(objectId)
                    commit.tree.id
                } else if (objectLoader.type == Constants.OBJ_TREE) {
                    // 如果已经是tree对象，直接使用
                    objectId
                } else {
                    Log.w(TAG, "引用指向的对象类型不支持: ${objectLoader.type}")
                    return emptyList()
                }

                // 使用ls-tree列出远程分支的文件
                val remoteFiles = mutableListOf<String>()
                TreeWalk(repo).use { treeWalk ->
                    treeWalk.reset(treeId)
                    treeWalk.isRecursive = true

                    while (treeWalk.next()) {
                        val path = treeWalk.pathString
                        if (path.startsWith("$DIR_NOTES/") && path.endsWith(".md")) {
                            val fileName = path.substringAfter("$DIR_NOTES/")
                            remoteFiles.add(fileName)
                            Log.d(TAG, "远程文件: $fileName (完整路径: $path)")
                        }
                    }
                }


                Log.d(TAG, "远程仓库中的笔记文件数量: ${remoteFiles.size}")
                remoteFiles
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出远程文件失败", e)
            emptyList()
        }
    }

    /**
     * 强制checkout所有文件到工作目录
     */
    private fun forceCheckoutFiles() {
        try {
            if (!::gitManager.isInitialized) {
                Log.w(TAG, "GitManager未初始化，无法执行checkout")
                return
            }

            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .build()

            repository.use { repo ->
                Git(repo).use { git ->
                    Log.d(TAG, "执行强制checkout...")
                    // 使用reset --hard来确保工作目录与HEAD一致
                    git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("HEAD")
                        .call()
                    Log.d(TAG, "强制checkout完成")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "强制checkout失败", e)
            throw e
        }
    }

    /**
     * 列出本地目录中的所有文件
     */
    private fun listLocalFiles(directory: File, description: String): List<String> {
        return try {
            if (!directory.exists() || !directory.isDirectory) {
                Log.w(TAG, "$description: 目录不存在或不是目录: ${directory.absolutePath}")
                return emptyList()
            }

            val allFiles = directory.listFiles() ?: emptyArray()
            val mdFiles = allFiles.filter { file ->
                file.isFile && file.name.endsWith(".md", ignoreCase = true)
            }.map { it.name }

            Log.d(TAG, "$description: 本地文件总数=${allFiles.size}, .md文件数=${mdFiles.size}")
            mdFiles.forEach { fileName ->
                Log.d(TAG, "$description: 本地文件: $fileName")
            }
            mdFiles
        } catch (e: Exception) {
            Log.e(TAG, "$description: 列出本地文件失败", e)
            emptyList()
        }
    }

    /**
     * 完整的同步流程
     */
    private suspend fun fullSyncFlow() {
        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val gitConfig = File(repoDir, ".git/config")
        val notesDir = File(repoDir, DIR_NOTES)

        // 0. 拉取前，列出本地文件
        Log.d(TAG, "========== 同步开始：文件对比分析 ==========")
        val localFilesBeforePull = listLocalFiles(notesDir, "拉取前本地")

        // 1. 检查Git仓库是否存在，不存在则初始化并拉取
        if (!gitConfig.exists()) {
            Log.d(TAG, "Git仓库不存在，初始化仓库")
            initializeAndPull()
            return
        }

        // 1.5. 获取远程状态（分析用）
        val remoteFiles = listRemoteNoteFiles()
        Log.d(TAG, "远程仓库中的笔记文件: ${remoteFiles.size} 个")

        // 1.6. 先拉取远程更新（确保以服务器数据为准进行合并）
        syncListener?.onSyncProgress("正在获取远程更新...")
        val pullResult = pullWithGitMerge()

        // 处理拉取结果（合并冲突）
        if (pullResult is SyncPullResult.Conflict) {
            handleMergeConflict(pullResult)
        } else if (pullResult is SyncPullResult.Error) {
            syncListener?.onSyncError("拉取失败: ${pullResult.errorMessage}")
            isSyncing = false
            return
        }

        // 1.7. 将合并后的最新数据加载到内存，然后再保存（确保内存与磁盘同步）
        loadDataFromGitToMemory()

        Log.d(TAG, "正在保存合并后的数据...")
        saveCurrentDataToGit()

        // 1.8. 推送本地合并后的更改到远程
        Log.d(TAG, "推送更改到远程...")
        syncListener?.onSyncProgress("正在同步到云端...")
        pushToRemote()
        // 2.5. 拉取后立即检查Git目录中的文件
        var localFilesAfterPull = listLocalFiles(notesDir, "拉取后本地")

        // 对比分析
        Log.d(TAG, "========== 文件对比分析 ==========")
        Log.d(TAG, "远程文件数量: ${remoteFiles.size}")
        Log.d(TAG, "拉取前本地文件数量: ${localFilesBeforePull.size}")
        Log.d(TAG, "拉取后本地文件数量: ${localFilesAfterPull.size}")

        // 找出差异（remoteFiles现在只包含文件名，不包含路径）
        val missingInLocal = remoteFiles.filter { fileName ->
            !localFilesAfterPull.contains(fileName)
        }
        val extraInLocal = localFilesAfterPull.filter { fileName ->
            !remoteFiles.contains(fileName)
        }

        if (missingInLocal.isNotEmpty()) {
            Log.w(TAG, "本地缺失的文件 (${missingInLocal.size} 个): ${missingInLocal.joinToString(", ")}")

            // 如果拉取后文件数量不对，尝试强制checkout
            if (pullResult is SyncPullResult.Success && missingInLocal.isNotEmpty()) {
                Log.w(TAG, "检测到文件缺失，尝试强制checkout...")
                try {
                    forceCheckoutFiles()
                    // 重新检查文件
                    localFilesAfterPull = listLocalFiles(notesDir, "强制checkout后本地")
                    Log.d(TAG, "强制checkout后本地文件数量: ${localFilesAfterPull.size}")

                    // 再次检查缺失的文件
                    val stillMissing = remoteFiles.filter { fileName ->
                        !localFilesAfterPull.contains(fileName)
                    }
                    if (stillMissing.isNotEmpty()) {
                        Log.w(TAG, "强制checkout后仍缺失的文件: ${stillMissing.joinToString(", ")}")
                    } else {
                        Log.d(TAG, "✓ 强制checkout后文件已完整")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "强制checkout失败", e)
                }
            }
        }
        if (extraInLocal.isNotEmpty()) {
            Log.w(TAG, "本地多余的文件 (${extraInLocal.size} 个): ${extraInLocal.joinToString(", ")}")
        }
        if (missingInLocal.isEmpty() && extraInLocal.isEmpty() && remoteFiles.size == localFilesAfterPull.size) {
            Log.d(TAG, "✓ 文件数量一致")
        }
        Log.d(TAG, "=====================================")

        // 整次同步流程全部结束后再通知成功，保证图标与真实状态一致
        syncListener?.onSyncSuccess("同步成功")
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

                                    continuation.resume(
                                        SyncPullResult.Conflict(
                                            conflictFiles,
                                            mergeResult
                                        )
                                    )
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
                            resolveConflictWithRemoteVersion(file)
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
                if (conflictParts.size >= 3) {
                    val ourContent = conflictParts[0]  // 我们的版本
                    val theirContent = conflictParts[2] // 他们的版本

                    // 按更新时间合并
                    val mergedContent = if (relativePath.endsWith(FILE_METADATA)) {
                        // metadata.json：按 id 合并，并保证输出是“单一合法数组”
                        mergeMetadataJson(
                            normalizeMetadataJson(ourContent),
                            normalizeMetadataJson(theirContent)
                        )
                    } else {
                        // 普通待办列表文件：按更新时间合并
                        mergeTodoListsByUpdateTime(ourContent, theirContent)
                    }

                    // 写回文件
                    file.writeText(mergedContent)

                    Log.d(TAG, "已解决待办列表冲突: $relativePath")
                } else {
                    // 无法解析冲突，使用本地版本
                    resolveConflictWithRemoteVersion(file)
                }
            } else {
                Log.d(TAG, "文件没有冲突标记，跳过: $relativePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理待办列表冲突失败", e)
            // 失败时使用本地版本
            resolveConflictWithRemoteVersion(file)
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

                    if (ourTime > theirTime) {
                        mergedMap[ourTodo.uuid] = ourTodo
                    }
                }
            }

            // 生成合并后的Markdown
            return generateTodoListMarkdown(mergedMap.values.toList())
        } catch (e: Exception) {
            Log.e(TAG, "合并待办列表失败", e)
            // 合并失败，使用我们的版本
            return theirContent
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
                    .mapNotNull { TodoItem.Companion.fromMarkdownLine(it) }
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
    private fun resolveConflictWithRemoteVersion(file: File) {
        try {
            val content = file.readText()

            // 如果有冲突标记，提取远程版本（他们的版本）
            if (content.contains("<<<<<<<") &&
                content.contains("=======") &&
                content.contains(">>>>>>>")) {

                val conflictParts = parseGitConflict(content)
                if (conflictParts.size >= 3) {
                    val remoteContent = conflictParts[2]  // 远程版本在第三个
                    file.writeText(remoteContent)
                    Log.d(TAG, "已使用远程版本解决冲突: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "使用远程版本解决冲突失败", e)
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
                val commitMessage = "$operation 列表 - ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                        Date()
                    )}"

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
            val ourArray = JSONArray(ourJson)
            val theirArray = JSONArray(theirJson)

            // 使用ID作为唯一标识，合并两个数组
            val mergedMap = mutableMapOf<String, JSONObject>()

            // 添加他们的所有项目
            for (i in 0 until theirArray.length()) {
                val obj = theirArray.getJSONObject(i)
                val id = obj.getString("id")
                mergedMap[id] = obj
            }

            // 添加我们的项目（仅当服务器上不存在时新增，存在时以服务器为准）
            for (i in 0 until ourArray.length()) {
                val obj = ourArray.getJSONObject(i)
                val id = obj.getString("id")
                if (!mergedMap.containsKey(id)) {
                    mergedMap[id] = obj
                }
            }

            // 创建合并后的JSON数组
            val mergedArray = JSONArray()
            mergedMap.values.forEach { mergedArray.put(it) }

            mergedArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "合并元数据JSON失败", e)
            theirJson // 失败时返回远程版本
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
                    val listToSelect = selectedList
                        ?: todoLists.firstOrNull { it.isDefault }
                        ?: todoLists.firstOrNull()

                    listToSelect?.let {
                        // 兜底：metadata.json 里如果没有 isSelected=true 的列表，也要选中一个，
                        // 否则 currentListId 可能指向不存在的列表，导致“当前列表不存在”
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
                        val currentTime = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                        todo.copy(updatedAt = currentTime)
                    } else {
                        todo
                    }
                }

                todoManager.replaceAllTodos(updatedTodos)
            }

            // 3. 加载笔记数据
            // 直接让 NoteManager 从硬盘（也就是 Git 镜像目录）重新加载当前分类的笔记即可
            noteManager.loadAllNotes()

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
                filePatterns = listOf("$DIR_TODO_LISTS/", "$DIR_NOTES/", "$DIR_IMAGES/"),
                onSuccess = {
                    syncScope.launch {
                        // 仅作为进度提示，不在此处调用 onSyncSuccess，由 fullSyncFlow 在整次同步结束后统一回调
                        syncListener?.onSyncProgress("推送完成")
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
    private suspend fun retryPushWithPull(continuation: Continuation<Unit>) {
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
                    commitMessage = "同步更新（重试） - ${
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                            Date()
                        )}",
                    filePatterns = listOf("$DIR_TODO_LISTS/", "$DIR_NOTES/", "$DIR_IMAGES/"),
                    onSuccess = {
                        syncScope.launch {
                            // 重试推送仅恢复 continuation，最终成功由 fullSyncFlow 统一回调 onSyncSuccess
                            syncListener?.onSyncProgress("重试推送完成")
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
                val commitMessage = "$operation 待办 - ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                        Date()
                    )}"

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
                    filePatterns = listOf("$DIR_NOTES/", "$DIR_IMAGES/"),
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

        // 1.5. 获取远程状态（分析用）
        val remoteFiles = listRemoteNoteFiles()
        Log.d(TAG, "远程仓库中的笔记文件: ${remoteFiles.size} 个")

        // 1.6. 先拉取远程更新（确保以服务器数据为准进行合并）
        syncListener?.onSyncProgress("正在获取远程更新...")
        val pullResult = pullWithGitMerge()

        // 处理拉取结果（合并冲突）
        if (pullResult is SyncPullResult.Conflict) {
            handleMergeConflict(pullResult)
        } else if (pullResult is SyncPullResult.Error) {
            syncListener?.onSyncError("拉取失败: ${pullResult.errorMessage}")
            isSyncing = false
            return
        }

        // 1.7. 将合并后的最新数据加载到内存，然后再保存（确保内存与磁盘同步）
        loadDataFromGitToMemory()

        Log.d(TAG, "正在保存合并后的数据...")
        saveCurrentDataToGit()

        // 1.8. 推送本地合并后的更改到远程
        Log.d(TAG, "推送更改到远程...")
        syncListener?.onSyncProgress("正在同步到云端...")
        pushToRemote()
