package com.hsiun.markdowntodo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(
    private val context: Context,
    private val repoUrl: String,
    private val token: String,
    private val branch: String = "main"
) {
    companion object {
        private const val TAG = "GitManager"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repoDir = File(context.filesDir, "git_repo")

    // 使用 synchronized 替代 ReentrantLock，避免锁状态异常
    private val lock = Any()

    private fun getCredentialsProvider(): CredentialsProvider {
        return UsernamePasswordCredentialsProvider("token", token)
    }

    // 1. 克隆仓库
    fun initAndCloneRepo(onSuccess: () -> Unit, onError: (String) -> Unit) {
        coroutineScope.launch {
            try {
                val result = synchronized(lock) {
                    try {
                        Log.d(TAG, "开始克隆仓库到: ${repoDir.absolutePath}")

                        // 如果目录已存在，先删除
                        if (repoDir.exists()) {
                            repoDir.deleteRecursively()
                        }

                        // 使用JGit进行克隆
                        Git.cloneRepository()
                            .setURI(repoUrl)
                            .setDirectory(repoDir)
                            .setBranch(branch)
                            .setCredentialsProvider(getCredentialsProvider())
                            .call()
                            .close()

                        Log.d(TAG, "克隆成功")
                        Pair(true, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "克隆失败", e)
                        Pair(false, "克隆失败: ${e.localizedMessage ?: "未知错误"}")
                    }
                }

                if (result.first) {
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError(result.second ?: "未知错误")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "initAndCloneRepo 异常", e)
                withContext(Dispatchers.Main) {
                    onError("initAndCloneRepo 异常: ${e.localizedMessage ?: "未知错误"}")
                }
            }
        }
    }

    // 2. 拉取更新（同步）
// GitManager.kt
// 在 pullChanges 方法的 onSuccess 回调中添加日志
    fun pullChanges(onSuccess: (PullResult) -> Unit, onError: (String) -> Unit) {
        coroutineScope.launch {
            try {
                val result = synchronized(lock) {
                    try {
                        Log.d(TAG, "开始拉取更改...")
                        val repository = FileRepositoryBuilder()
                            .setGitDir(File(repoDir, ".git"))
                            .build()

                        var pullResult: PullResult? = null

                        repository.use { repo ->
                            Git(repo).use { git ->
                                pullResult = git.pull()
                                    .setRemote("origin")
                                    .setRemoteBranchName(branch)
                                    .setCredentialsProvider(getCredentialsProvider())
                                    .call()

                                // 拉取后检查文件状态
                                Log.d(TAG, "拉取操作完成")

                                // 列出notes目录中的文件
                                val notesDir = File(repoDir, "notes")
                                if (notesDir.exists()) {
                                    val files = notesDir.listFiles()
                                    Log.d(TAG, "拉取后笔记文件数: ${files?.size ?: 0}")
                                    files?.forEach { file ->
                                        Log.d(TAG, "  - ${file.name}, 大小: ${file.length()}")
                                    }
                                }
                            }
                        }

                        if (pullResult?.isSuccessful == true) {
                            Log.d(TAG, "拉取成功")
                            Triple(true, pullResult, null)
                        } else {
                            Log.w(TAG, "拉取失败或存在冲突")
                            Triple(false, null, "拉取失败")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "拉取失败", e)
                        Triple(false, null, "拉取失败: ${e.localizedMessage ?: e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    if (result.first && result.second != null) {
                        onSuccess(result.second!!)
                    } else {
                        onError(result.third ?: "拉取失败")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pullChanges 异常", e)
                withContext(Dispatchers.Main) {
                    onError("pullChanges 异常: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    // 3. 提交并推送
    fun commitAndPush(
        commitMessage: String = "Update todos",
        filePatterns: List<String> = listOf("todos.md"),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            try {
                // 在临界区内执行所有同步操作，但不包含任何挂起函数
                val result = synchronized(lock) {
                    try {
                        val repository = FileRepositoryBuilder()
                            .setGitDir(File(repoDir, ".git"))
                            .build()

                        repository.use { repo ->
                            Git(repo).use { git ->
                                // 清理可能存在的锁文件
                                cleanupLockFiles()

                                // 确保文件存在
                                val hasValidFiles = filePatterns.any { pattern ->
                                    val fileToCheck = if (pattern.contains("*")) {
                                        File(repoDir, pattern.substringBeforeLast("*"))
                                    } else {
                                        File(repoDir, pattern)
                                    }
                                    fileToCheck.exists() || (fileToCheck.isDirectory && fileToCheck.listFiles()?.isNotEmpty() == true)
                                }

                                if (!hasValidFiles) {
                                    Log.w(TAG, "没有需要提交的文件")
                                    return@synchronized Pair(true, "没有需要提交的文件")
                                }

                                // 添加所有指定的文件模式
                                filePatterns.forEach { pattern ->
                                    try {
                                        git.add()
                                            .addFilepattern(pattern)
                                            .call()
                                        Log.d(TAG, "已添加文件模式: $pattern")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "添加文件模式 $pattern 失败: ${e.message}")
                                    }
                                }

                                // 检查是否有需要提交的更改
                                val status = git.status().call()
                                if (status.isClean) {
                                    Log.d(TAG, "没有需要提交的更改")
                                    return@synchronized Pair(true, "没有需要提交的更改")
                                }

                                // 提交
                                val commit = git.commit()
                                    .setMessage(commitMessage)
                                    .call()

                                if (commit == null) {
                                    throw Exception("提交失败")
                                }

                                Log.d(TAG, "提交成功: ${commit.id.name} - $commitMessage")

                                // 推送
                                git.push()
                                    .setRemote("origin")
                                    .setCredentialsProvider(getCredentialsProvider())
                                    .call()

                                Log.d(TAG, "推送成功")
                                Pair(true, null)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "推送失败", e)
                        Pair(false, "推送失败: ${e.localizedMessage ?: e.message}")
                    }
                }

                // 在临界区外部执行回调
                if (result.first) {
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError(result.second ?: "未知错误")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "commitAndPush 异常", e)
                withContext(Dispatchers.Main) {
                    onError("commitAndPush 异常: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    // 清理可能存在的锁文件
    private fun cleanupLockFiles() {
        val lockFile = File(repoDir, ".git/index.lock")
        if (lockFile.exists()) {
            try {
                lockFile.delete()
                Log.d(TAG, "已清理锁文件")
            } catch (e: Exception) {
                Log.e(TAG, "清理锁文件失败", e)
            }
        }
    }
    // GitManager.kt - 添加删除单个文件的方法
    fun removeFile(
        filePattern: String,
        commitMessage: String = "删除文件",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            try {
                val result = synchronized(lock) {
                    try {
                        val repository = FileRepositoryBuilder()
                            .setGitDir(File(repoDir, ".git"))
                            .build()

                        repository.use { repo ->
                            Git(repo).use { git ->
                                // 清理可能存在的锁文件
                                cleanupLockFiles()

                                Log.d(TAG, "开始删除文件: $filePattern")

                                // 检查文件是否存在
                                val fileToDelete = File(repoDir, filePattern)
                                if (!fileToDelete.exists()) {
                                    Log.w(TAG, "文件不存在，跳过删除: $filePattern")
                                    return@synchronized Pair(true, "文件不存在")
                                }

                                // 使用 git.rm() 删除文件
                                git.rm()
                                    .addFilepattern(filePattern)
                                    .call()

                                Log.d(TAG, "已标记删除文件: $filePattern")

                                // 检查是否有需要提交的更改
                                val status = git.status().call()
                                if (status.isClean) {
                                    Log.d(TAG, "没有需要提交的更改")
                                    return@synchronized Pair(true, "没有需要提交的更改")
                                }

                                // 提交删除操作
                                val commit = git.commit()
                                    .setMessage(commitMessage)
                                    .call()

                                if (commit == null) {
                                    throw Exception("提交失败")
                                }

                                Log.d(TAG, "删除提交成功: ${commit.id.name} - $commitMessage")

                                // 推送
                                git.push()
                                    .setRemote("origin")
                                    .setCredentialsProvider(getCredentialsProvider())
                                    .call()

                                Log.d(TAG, "推送删除成功")
                                Pair(true, null)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "删除文件失败", e)
                        Pair(false, "删除文件失败: ${e.localizedMessage ?: e.message}")
                    }
                }

                if (result.first) {
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError(result.second ?: "未知错误")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeFile 异常", e)
                withContext(Dispatchers.Main) {
                    onError("removeFile 异常: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }
    fun cleanup() {
        coroutineScope.cancel()
    }
}