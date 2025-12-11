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
import java.util.concurrent.locks.ReentrantLock

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

    // 添加锁机制，确保同一时间只有一个Git操作
    private val gitLock = ReentrantLock()

    private fun getCredentialsProvider(): CredentialsProvider {
        return UsernamePasswordCredentialsProvider("token", token)
    }

    // 1. 克隆仓库
    fun initAndCloneRepo(onSuccess: () -> Unit, onError: (String) -> Unit) {
        coroutineScope.launch {
            try {
                gitLock.lock()
                try {
                    val normalUrl = repoUrl

                    Log.d(TAG, "开始克隆仓库到: ${repoDir.absolutePath}")

                    // 使用JGit进行克隆
                    Git.cloneRepository()
                        .setURI(normalUrl)
                        .setDirectory(repoDir)
                        .setBranch(branch)
                        .setCredentialsProvider(getCredentialsProvider())
                        .call()
                        .close()

                    Log.d(TAG, "克隆成功")
                    withContext(Dispatchers.Main) { onSuccess() }
                } finally {
                    gitLock.unlock()
                }
            } catch (e: Exception) {
                Log.e(TAG, "克隆失败", e)
                withContext(Dispatchers.Main) {
                    onError("克隆失败: ${e.localizedMessage ?: "未知错误"}")
                }
            }
        }
    }

    // 2. 拉取更新（同步）
    fun pullChanges(onSuccess: (PullResult) -> Unit, onError: (String) -> Unit) {
        coroutineScope.launch {
            try {
                gitLock.lock()
                try {
                    val repository = FileRepositoryBuilder()
                        .setGitDir(File(repoDir, ".git"))
                        .build()

                    repository.use { repo ->
                        Git(repo).use { git ->
                            val pullResult = git.pull()
                                .setRemote("origin")
                                .setRemoteBranchName(branch)
                                .setCredentialsProvider(getCredentialsProvider())
                                .call()

                            if (pullResult.isSuccessful) {
                                withContext(Dispatchers.Main) { onSuccess(pullResult) }
                            } else {
                                throw Exception("拉取失败或有冲突")
                            }
                        }
                    }
                } finally {
                    gitLock.unlock()
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取失败", e)
                withContext(Dispatchers.Main) {
                    onError("拉取失败: ${e.localizedMessage}")
                }
            }
        }
    }

    // 3. 提交并推送
    fun commitAndPush(
        commitMessage: String = "Update todos",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            try {
                gitLock.lock()
                try {
                    val repository = FileRepositoryBuilder()
                        .setGitDir(File(repoDir, ".git"))
                        .build()

                    repository.use { repo ->
                        Git(repo).use { git ->
                            // 确保文件存在
                            val fileToCheck = File(repoDir, "todos.md")
                            if (!fileToCheck.exists()) {
                                throw Exception("待办事项文件不存在")
                            }

                            // 清理可能存在的锁文件
                            cleanupLockFiles()

                            // 添加文件
                            git.add()
                                .addFilepattern("todos.md")
                                .call()

                            // 提交
                            val commit = git.commit()
                                .setMessage(commitMessage)
                                .call()

                            if (commit == null) {
                                throw Exception("没有需要提交的更改")
                            }

                            Log.d(TAG, "提交成功: ${commit.id.name} - $commitMessage")

                            // 推送
                            git.push()
                                .setRemote("origin")
                                .setCredentialsProvider(getCredentialsProvider())
                                .call()

                            Log.d(TAG, "推送成功")
                            withContext(Dispatchers.Main) { onSuccess() }
                        }
                    }
                } finally {
                    gitLock.unlock()
                }
            } catch (e: Exception) {
                Log.e(TAG, "推送失败", e)
                withContext(Dispatchers.Main) {
                    onError("推送失败: ${e.localizedMessage ?: e.message}")
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

    fun cleanup() {
        coroutineScope.cancel()
    }
}