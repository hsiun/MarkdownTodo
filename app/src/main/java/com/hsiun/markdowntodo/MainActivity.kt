package com.hsiun.markdowntodo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import android.graphics.Color
import android.content.SharedPreferences
import android.preference.PreferenceManager
import kotlinx.coroutines.*
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TodoAdapter
    private var todos = mutableListOf<TodoItem>()
    private var nextId = 1
    private lateinit var sharedPreferences: SharedPreferences

    // 同步状态变量
    private var isSyncing = false
    private var isAutoPushEnabled = true
    private var lastSyncTime: Long = 0
    private val SYNC_COOLDOWN = 5000L

    // Git 配置变量 - 从 SharedPreferences 读取
    private var GITHUB_REPO_URL: String = ""
    private var GITHUB_TOKEN: String = ""

    private lateinit var gitManager: GitManager

    // 在类变量中添加
    private var showCompletedTodos = false
    private val todoFile by lazy {
        File(filesDir, "todos.md")
    }

    // 对话框中的临时待办事项列表
    private val dialogTodos = mutableListOf<String>()

    // ItemTouchHelper 用于实现左滑删除
    private lateinit var itemTouchHelper: ItemTouchHelper

    // 调试信息窗口
    private var debugDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置系统工具栏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // 从 SharedPreferences 加载 Git 配置
        loadGitConfig()

        // 初始化列表和适配器
        setupRecyclerView()

        // 根据设置更新待办显示模式
        updateTodoDisplayMode()

        // 加载本地待办事项
        loadLocalTodos()

        // 设置监听器
        setupListeners()
        setupTabs()

        // 初始化GitManager
        initializeGitManager()

        // 检查是否需要初始化Git仓库
        setupGitSync()

        // 设置下拉刷新
        setupSwipeRefresh()

        Log.d("MainActivity", "应用启动完成")
    }

    private fun setupSwipeRefresh() {
        // 设置下拉刷新颜色
        binding.swipeRefreshLayout.setColorSchemeColors(
            Color.parseColor("#865EDC"),  // 主色调
            Color.parseColor("#1A73E8"),  // 蓝色
            Color.parseColor("#4CAF50")   // 绿色
        )

        // 设置下拉刷新监听器
        binding.swipeRefreshLayout.setOnRefreshListener {
            performSyncWithDebug(true)
        }

        // 设置下拉刷新进度背景
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#FFFFFF"))
    }

    private fun performSyncWithDebug(isManualRefresh: Boolean = false) {
        if (isSyncing) {
            // 如果正在同步，停止刷新动画
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }

        // 显示调试信息窗口
        showDebugInfo("开始同步...", isManualRefresh)

        // 更新同步状态指示器
        updateSyncIndicator("正在同步...", Color.parseColor("#FF9800"))

        performSync()
    }

    private fun showDebugInfo(message: String, isManualRefresh: Boolean = false) {
        // 如果不是手动刷新且不是第一次同步，不显示调试信息
        if (!isManualRefresh && !isFirstSync) {
            return
        }

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val debugMessage = "[$timestamp] $message"

        Log.d("DebugInfo", debugMessage)

    }



    private fun updateSyncIndicator(status: String = "", color: Int? = null) {
        runOnUiThread {
            if (status.isNotEmpty()) {
                binding.syncStatusText.text = when {
                    status.contains("正在同步") -> "🔄"
                    status.contains("成功") -> "✅"
                    status.contains("失败") -> "❌"
                    status.contains("未连接") -> "⚪"
                    else -> "⚪"
                }
            }

            if (color != null) {
                binding.syncStatusText.setTextColor(color)
            }

            // 自动清除状态
            if (status.isNotEmpty() && !status.contains("正在同步")) {
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.syncStatusText.text = "⚪"
                    binding.syncStatusText.setTextColor(Color.parseColor("#666666"))
                }, 3000)
            }
        }
    }

    private fun loadGitConfig() {
        GITHUB_REPO_URL = sharedPreferences.getString("github_repo_url", "") ?: ""
        GITHUB_TOKEN = sharedPreferences.getString("github_token", "") ?: ""
        showCompletedTodos = sharedPreferences.getBoolean("show_completed", false)

        Log.d("MainActivity", "加载Git配置: URL=${if (GITHUB_REPO_URL.isNotEmpty()) "已设置" else "未设置"}, " +
                "Token=${if (GITHUB_TOKEN.isNotEmpty()) "已设置" else "未设置"}, " +
                "显示已完成=$showCompletedTodos")
    }

    private fun saveGitConfig(repoUrl: String, token: String) {
        with(sharedPreferences.edit()) {
            putString("github_repo_url", repoUrl)
            putString("github_token", token)
            apply()
        }

        // 更新内存中的配置
        GITHUB_REPO_URL = repoUrl
        GITHUB_TOKEN = token

        // 重新初始化GitManager
        initializeGitManager()

        Toast.makeText(this, "Git配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun initializeGitManager() {
        // 清理旧的GitManager
        if (::gitManager.isInitialized) {
            gitManager.cleanup()
        }

        // 创建新的GitManager
        if (GITHUB_REPO_URL.isNotEmpty() && GITHUB_TOKEN.isNotEmpty()) {
            gitManager = GitManager(this, GITHUB_REPO_URL, GITHUB_TOKEN)
            Log.d("MainActivity", "GitManager已初始化")
        } else {
            Toast.makeText(this, "请先配置Git仓库地址和Token", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupGitSync() {
        // 如果没有配置Git，不进行同步
        if (GITHUB_REPO_URL.isEmpty() || GITHUB_TOKEN.isEmpty()) {
            Toast.makeText(this, "请先配置Git仓库", Toast.LENGTH_LONG).show()
            return
        }

        val isFirstLaunch = sharedPreferences.getBoolean("is_first_launch", true)

        if (isFirstLaunch) {
            initGitRepo()
            sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
        } else {
            checkAndSync()
        }
    }

    private fun initGitRepo() {
        isSyncing = true
        updateSyncIndicator("正在初始化...", Color.parseColor("#FF9800"))

        gitManager.initAndCloneRepo(
            onSuccess = {
                runOnUiThread {
                    isSyncing = false
                    binding.swipeRefreshLayout.isRefreshing = false
                    updateSyncIndicator("初始化成功", Color.parseColor("#4CAF50"))
                    Toast.makeText(this, "Git仓库初始化成功", Toast.LENGTH_SHORT).show()
                    performSync()
                }
            },
            onError = { error ->
                runOnUiThread {
                    isSyncing = false
                    binding.swipeRefreshLayout.isRefreshing = false
                    updateSyncIndicator("初始化失败", Color.parseColor("#F44336"))
                    if (error.contains("网络不可用")) {
                        Toast.makeText(this, "网络不可用，将使用本地模式", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Git初始化失败: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun checkAndSync() {
        val repoDir = File(filesDir, "git_repo")
        val gitConfig = File(repoDir, ".git/config")

        if (gitConfig.exists()) {
            performSync()
        } else {
            initGitRepo()
        }
    }

    private fun setupRecyclerView() {
        adapter = TodoAdapter(
            todos,
            onTodoChanged = { updateTodo(it) },
            onTodoDeleted = { deleteTodo(it) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 设置左滑删除功能
        setupSwipeToDelete()

        updateTodoCount()
        updateEmptyView()
    }

    private fun setupSwipeToDelete() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // 不支持拖动
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val todo = adapter.getItemAtPosition(position)
                    if (todo != null) {
                        // 显示确认删除对话框
                        showDeleteConfirmationDialog(todo, position, viewHolder)
                    }
                }
            }

            override fun onChildDraw(
                canvas: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // 自定义滑动效果
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val deleteButton = itemView.findViewById<ImageButton>(R.id.deleteButton)

                    if (dX < 0) {
                        // 向左滑动，显示删除按钮
                        deleteButton.visibility = View.VISIBLE
                        deleteButton.translationX = dX + itemView.width - deleteButton.width
                    } else {
                        // 向右滑动或其他情况，隐藏删除按钮
                        deleteButton.visibility = View.GONE
                    }

                    // 调用父类方法绘制背景
                    super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(swipeCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun showDeleteConfirmationDialog(todo: TodoItem, position: Int, viewHolder: RecyclerView.ViewHolder) {
        AlertDialog.Builder(this)
            .setTitle("删除待办")
            .setMessage("确定要删除 '${todo.title}' 吗？")
            .setPositiveButton("删除") { dialog, which ->
                // 执行删除操作
                deleteTodo(todo)
                // 通知适配器删除该项
                adapter.notifyItemRemoved(position)
            }
            .setNegativeButton("取消") { dialog, which ->
                // 取消删除，恢复原状
                adapter.notifyItemChanged(position)
            }
            .setOnCancelListener {
                // 对话框被取消，恢复原状
                adapter.notifyItemChanged(position)
            }
            .show()
    }

    private fun setupListeners() {
        // 设置按钮点击
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        // 悬浮按钮点击 - 弹出添加待办对话框
        binding.fab.setOnClickListener {
            showAddTodoDialog()
        }

    }

    private fun showAddTodoDialog() {
        // 使用传统方式加载布局
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_todo_list, null)

        // 获取视图组件
        val inputEditText = dialogView.findViewById<EditText>(R.id.inputEditText)
        val completeButton = dialogView.findViewById<Button>(R.id.completeButton)
        val reminderCheckBox = dialogView.findViewById<CheckBox>(R.id.reminderCheckBox)

        // 设置输入框监听
        inputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 启用/禁用完成按钮
                val hasContent = s?.toString()?.trim()?.isNotEmpty() == true
                completeButton.isEnabled = hasContent
                completeButton.alpha = if (hasContent) 1.0f else 0.5f
            }
        })

        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 输入框回车键监听
        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val title = inputEditText.text.toString().trim()
                if (title.isNotEmpty()) {
                    // 添加待办事项
                    addTodoWithReminder(title, reminderCheckBox.isChecked)
                    // 关闭对话框
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        // 完成按钮点击
        completeButton.setOnClickListener {
            val title = inputEditText.text.toString().trim()
            if (title.isNotEmpty()) {
                // 添加待办事项
                addTodoWithReminder(title, reminderCheckBox.isChecked)
                // 关闭对话框
                dialog.dismiss()
            }
        }

        // 初始化完成按钮状态
        completeButton.isEnabled = false
        completeButton.alpha = 0.5f

        // 显示对话框后自动弹出键盘
        dialog.show()
        inputEditText.requestFocus()

        // 显示软键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    // 新增一个方法来处理带提醒的待办事项
    private fun addTodoWithReminder(title: String, setReminder: Boolean) {
        try {
            if (title.trim().isEmpty()) {
                Toast.makeText(this, "标题不能为空", Toast.LENGTH_SHORT).show()
                return
            }

            // 生成唯一ID
            val newId = nextId++
            val todo = TodoItem(id = newId, title = title)

            Log.d("MainActivity", "添加待办事项: ID=$newId, 标题='$title'")

            // 使用适配器的 addTodo 方法
            adapter.addTodo(todo)

            // 保存到本地
            saveLocalTodos()

            // 如果设置了提醒，可以在这里添加提醒逻辑
            if (setReminder) {
                Log.d("MainActivity", "设置了提醒")
            }

            // 自动推送
            autoPushTodo("添加", todo)

            updateEmptyView()
            updateTodoCount()
            Toast.makeText(this, "已添加: $title", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "添加待办失败", e)
            Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 修改 showSettingsDialog 方法，添加调试按钮
    private fun showSettingsDialog() {
        // 加载设置对话框布局
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        // 获取输入框
        val repoUrlInput = dialogView.findViewById<EditText>(R.id.githubRepoUrlInput)
        val tokenInput = dialogView.findViewById<EditText>(R.id.githubTokenInput)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val showCompletedCheckbox = dialogView.findViewById<CheckBox>(R.id.showCompletedCheckbox)

        // 填充现有配置
        repoUrlInput.setText(GITHUB_REPO_URL)
        tokenInput.setText(GITHUB_TOKEN)
        // 加载是否显示已完成的设置
        showCompletedCheckbox.isChecked = sharedPreferences.getBoolean("show_completed", false)
        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 保存按钮点击 - 修复这里：调用 saveAllSettings 而不是 saveGitConfig
        saveButton.setOnClickListener {
            val repoUrl = repoUrlInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()
            val showCompleted = showCompletedCheckbox.isChecked

            if (repoUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "请填写完整的Git配置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 修复：调用 saveAllSettings 保存所有设置，包括单选框状态
            saveAllSettings(repoUrl, token, showCompleted)

            // 立即更新待办显示模式
            updateTodoDisplayMode()

            dialog.dismiss()
        }

        // 取消按钮点击
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }


        dialog.show()
    }

    // 修改 saveAllSettings 方法
    private fun saveAllSettings(repoUrl: String, token: String, showCompleted: Boolean) {
        with(sharedPreferences.edit()) {
            putString("github_repo_url", repoUrl)
            putString("github_token", token)
            putBoolean("show_completed", showCompleted)
            apply()
        }

        // 更新内存中的配置
        GITHUB_REPO_URL = repoUrl
        GITHUB_TOKEN = token
        showCompletedTodos = showCompleted

        // 重新初始化GitManager（如果需要）
        if (GITHUB_REPO_URL.isNotEmpty() && GITHUB_TOKEN.isNotEmpty()) {
            initializeGitManager()
        }

        // 注意：这里不调用 updateTodoDisplayMode()，因为调用者在保存后会调用
        // 这样可以避免重复更新

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    // 修改 updateTodoDisplayMode 方法，确保它能正确更新列表
    private fun updateTodoDisplayMode() {
        Log.d("MainActivity", "更新显示模式，showCompletedTodos=$showCompletedTodos")

        if (showCompletedTodos) {
            Log.d("MainActivity", "设置显示模式为: ALL")
            adapter.setDisplayMode(TodoAdapter.DisplayMode.ALL)
        } else {
            Log.d("MainActivity", "设置显示模式为: ACTIVE")
            adapter.setDisplayMode(TodoAdapter.DisplayMode.ACTIVE)
        }

        // 确保列表立即更新
        updateTodoCount()
        updateEmptyView()

        // 添加一个轻微的延迟，确保UI更新完成
        binding.recyclerView.post {
            // 刷新列表
            adapter.notifyDataSetChanged()
            Log.d("MainActivity", "列表已刷新，当前显示数量: ${adapter.itemCount}")
        }
    }


    private fun setupTabs() {
        binding.todoTab.setTextColor(Color.parseColor("#1A73E8"))
        binding.notesTab.setTextColor(Color.parseColor("#999999"))

        binding.todoTab.setOnClickListener {
            binding.todoTab.setTextColor(Color.parseColor("#1A73E8"))
            binding.notesTab.setTextColor(Color.parseColor("#999999"))
            Toast.makeText(this, "待办选项卡", Toast.LENGTH_SHORT).show()
        }

        binding.notesTab.setOnClickListener {
            binding.notesTab.setTextColor(Color.parseColor("#1A73E8"))
            binding.todoTab.setTextColor(Color.parseColor("#999999"))
            Toast.makeText(this, "笔记选项卡（功能待实现）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSync() {
        // 如果没有配置Git，不进行同步
        if (GITHUB_REPO_URL.isEmpty() || GITHUB_TOKEN.isEmpty()) {
            runOnUiThread {
                binding.swipeRefreshLayout.isRefreshing = false
                updateSyncIndicator("未配置", Color.parseColor("#F44336"))
                showDebugInfo("同步失败：未配置Git仓库")
            }
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) {
            runOnUiThread {
                binding.swipeRefreshLayout.isRefreshing = false
                updateSyncIndicator("同步间隔太短", Color.parseColor("#FF9800"))
                showDebugInfo("同步失败：同步间隔太短")
            }
            return
        }

        if (isSyncing) {
            runOnUiThread {
                binding.swipeRefreshLayout.isRefreshing = false
                updateSyncIndicator("正在同步中", Color.parseColor("#FF9800"))
                showDebugInfo("同步失败：正在同步中")
            }
            return
        }

        isSyncing = true
        lastSyncTime = currentTime
        showDebugInfo("开始同步流程...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                showDebugInfo("检查仓库目录...")
                val repoDir = File(filesDir, "git_repo")
                val gitConfig = File(repoDir, ".git/config")

                if (!gitConfig.exists()) {
                    showDebugInfo("Git仓库未初始化，重新初始化...")
                    runOnUiThread {
                        initGitRepo()
                    }
                    return@launch
                }

                gitManager.pullChanges(
                    onSuccess = { pullResult ->
                        // 合并前先保存当前本地状态
                        val currentLocalTodos = readTodosFromFile(todoFile)
                        showDebugInfo("拉取成功，当前本地有 ${currentLocalTodos.size} 条待办")

                        // 智能合并
                        val mergedTodos = mergeTodosIntelligently()
                        showDebugInfo("合并后共有 ${mergedTodos.size} 条待办")

                        // 保存合并后的结果到本地文件
                        saveTodosToFile(mergedTodos, todoFile)
                        // 同时也保存到Git仓库目录
                        val remoteFile = File(filesDir, "git_repo/todos.md")
                        saveTodosToFile(mergedTodos, remoteFile)

                        showDebugInfo("文件保存完成")

                        runOnUiThread {
                            // 使用适配器的 updateTodos 方法更新列表
                            adapter.updateTodos(mergedTodos)

                            // 同时更新本地的 todos 列表
                            todos.clear()
                            todos.addAll(mergedTodos)

                            // 更新下一个可用的ID
                            if (mergedTodos.isNotEmpty()) {
                                nextId = mergedTodos.maxOf { it.id } + 1
                            } else {
                                nextId = 1
                            }

                            updateTodoCount()
                            updateEmptyView()

                            isSyncing = false
                            binding.swipeRefreshLayout.isRefreshing = false
                            updateSyncIndicator("同步成功", Color.parseColor("#4CAF50"))
                            showDebugInfo("同步完成！共 ${mergedTodos.size} 条待办")

                            if (BuildConfig.DEBUG) {
                                Toast.makeText(this@MainActivity, "同步成功，共 ${mergedTodos.size} 条待办", Toast.LENGTH_SHORT).show()
                            }

                            Log.d("MainActivity", "同步完成: 本地=${currentLocalTodos.size} 条, 合并后=${mergedTodos.size} 条, nextId=$nextId")
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            isSyncing = false
                            binding.swipeRefreshLayout.isRefreshing = false

                            if (error.contains("Checkout conflict with files")) {
                                // 这是冲突处理后的重新同步
                                showDebugInfo("检测到冲突，正在重新同步...")
                                updateSyncIndicator("重新同步中...", Color.parseColor("#FF9800"))
                                // 在后台线程处理冲突
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        // 删除本地git仓库目录
                                        if (repoDir.exists()) {
                                            repoDir.deleteRecursively()
                                            showDebugInfo("已删除本地冲突仓库")
                                        }


                                    } catch (e: Exception) {
                                        showDebugInfo("处理冲突失败: ${e.message}")
                                        runOnUiThread {
                                            updateSyncIndicator("冲突处理失败", Color.parseColor("#F44336"))
                                        }
                                    }
                                }
                            } else if (error.contains("网络不可用")) {
                                updateSyncIndicator("网络不可用", Color.parseColor("#F44336"))
                                showDebugInfo("同步失败：网络不可用")
                            } else if (error.contains("Git仓库未初始化")) {
                                updateSyncIndicator("未初始化", Color.parseColor("#F44336"))
                                showDebugInfo("同步失败：Git仓库未初始化，重新初始化...")
                                initGitRepo()
                            } else {
                                updateSyncIndicator("同步失败", Color.parseColor("#F44336"))
                                showDebugInfo("同步失败：$error")
                                if (BuildConfig.DEBUG) {
                                    Toast.makeText(this@MainActivity, "同步失败: $error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    isSyncing = false
                    binding.swipeRefreshLayout.isRefreshing = false
                    updateSyncIndicator("同步异常", Color.parseColor("#F44336"))
                    showDebugInfo("同步异常: ${e.message}")
                    Toast.makeText(this@MainActivity, "同步异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun mergeTodosIntelligently(): List<TodoItem> {
        val localTodos = readTodosFromFile(todoFile)
        val remoteFile = File(filesDir, "git_repo/todos.md")

        // 读取并显示远程文件内容
        val remoteContent = if (remoteFile.exists()) {
            remoteFile.readText()
        } else {
            ""
        }

        Log.d("MainActivity", "远程文件内容:\n$remoteContent")

        val remoteTodos = if (remoteFile.exists()) {
            readTodosFromFile(remoteFile)
        } else {
            emptyList()
        }

        showDebugInfo("本地待办: ${localTodos.size} 条，远程待办: ${remoteTodos.size} 条")

        // 详细检查每个待办的状态
        remoteTodos.forEach { todo ->
            Log.d("MainActivity", "远程待办 ${todo.id}: '${todo.title}' - 完成状态: ${todo.isCompleted}")
        }

        val mergedMap = mutableMapOf<String, TodoItem>()

        // 先添加远程的所有项目，远程优先
        remoteTodos.forEach { remoteTodo ->
            mergedMap[remoteTodo.uuid] = remoteTodo
        }

        showDebugInfo("添加远程待办后: ${mergedMap.size} 条")

        // 然后处理本地的项目
        localTodos.forEach { localTodo ->
            val existingTodo = mergedMap[localTodo.uuid]

            if (existingTodo == null) {
                // 远程没有这个待办，添加本地待办
                mergedMap[localTodo.uuid] = localTodo
                showDebugInfo("添加新本地待办: ID=${localTodo.id}, 标题='${localTodo.title}'")
            } else {
                // 远程有这个待办，需要合并
                // 关键修改：如果远程已完成，则优先使用远程状态
                if (existingTodo.isCompleted) {
                    // 如果远程已完成，则本地也标记为已完成
                    val mergedTodo = localTodo.copy(isCompleted = true)
                    mergedMap[localTodo.uuid] = mergedTodo
                    showDebugInfo("待办 ${localTodo.id} - '${localTodo.title}' 已同步为已完成状态")
                } else if (localTodo.isCompleted && !existingTodo.isCompleted) {
                    // 如果本地已完成而远程未完成，保留本地完成状态
                    mergedMap[localTodo.uuid] = localTodo
                    showDebugInfo("待办 ${localTodo.id} - '${localTodo.title}' 保持本地已完成状态")
                } else {
                    // 其他情况（都未完成），保留本地待办
                    mergedMap[localTodo.uuid] = localTodo
                    showDebugInfo("待办 ${localTodo.id} - '${localTodo.title}' 保持未完成状态")
                }
            }
        }

        showDebugInfo("合并本地待办后: ${mergedMap.size} 条")

        // 转换回列表并按ID排序
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
                Log.e("MainActivity", "读取待办文件失败", e)
                showDebugInfo("读取文件失败: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
    }


    // 修改 saveTodosToFile 方法
    private fun saveTodosToFile(todos: List<TodoItem>, file: File) {
        try {
            file.bufferedWriter().use { writer ->
                writer.write("# 待办事项\n\n")
                todos.forEach { todo ->
                    writer.write("${todo.toMarkdownLine()}\n")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "保存文件失败", e)
            showDebugInfo("保存文件失败: ${e.message}")
        }
    }

    private fun autoPushTodo(operation: String, todo: TodoItem? = null) {
        if (!isAutoPushEnabled) {
            return
        }

        // 如果没有配置Git，不进行推送
        if (GITHUB_REPO_URL.isEmpty() || GITHUB_TOKEN.isEmpty()) {
            return
        }

        // 确保本地文件已保存
        saveLocalTodos()

        val repoDir = File(filesDir, "git_repo")
        val gitConfig = File(repoDir, ".git/config")

        if (!gitConfig.exists()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                showDebugInfo("自动推送: $operation")

                // 将本地待办事项文件复制到Git仓库目录
                val localTodoFile = todoFile
                val remoteTodoFile = File(repoDir, "todos.md")

                if (localTodoFile.exists()) {
                    localTodoFile.copyTo(remoteTodoFile, overwrite = true)
                    showDebugInfo("已复制待办事项文件到Git目录")
                }

                // 直接提交和推送，不再调用 performSync
                gitManager.commitAndPush(
                    commitMessage = "$operation 待办事项 - ${System.currentTimeMillis()}",
                    onSuccess = {
                        runOnUiThread {
                            showDebugInfo("自动推送成功: $operation")
                            updateSyncIndicator("推送成功", Color.parseColor("#4CAF50"))
                            Toast.makeText(this@MainActivity, "已同步到云端", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { error ->
                        showDebugInfo("自动推送失败: $error")
                        if (BuildConfig.DEBUG) {
                            runOnUiThread {
                                updateSyncIndicator("推送失败", Color.parseColor("#F44336"))
                                Toast.makeText(this@MainActivity, "自动推送失败: $error", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "自动推送异常", e)
                showDebugInfo("自动推送异常: ${e.message}")
            }
        }
    }

    private fun updateTodo(todo: TodoItem) {
        // 更新列表中的待办事项
        val index = todos.indexOfFirst { it.id == todo.id }
        if (index != -1) {
            todos[index] = todo
        }

        saveLocalTodos()
        autoPushTodo("更新", todo)
        updateTodoCount()

        // 通知适配器更新特定项
        //adapter.notifyItemChanged(index)
    }

    private fun deleteTodo(todo: TodoItem) {
        // 使用适配器的 removeTodo 方法
        adapter.removeTodo(todo)
        saveLocalTodos()
        autoPushTodo("删除", todo)
        updateEmptyView()
        updateTodoCount()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    private fun saveLocalTodos() {
        try {
            FileWriter(todoFile).use { writer ->
                writer.write("# 待办事项\n\n")
                todos.forEach { todo ->
                    writer.write("${todo.toMarkdownLine()}\n")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLocalTodos() {
        try {
            if (!todoFile.exists()) {
                Log.d("MainActivity", "待办文件不存在，创建空文件")
                // 创建空文件
                todoFile.createNewFile()
                saveTodosToFile(emptyList(), todoFile)
                return
            }

            val loadedTodos = readTodosFromFile(todoFile)
            // 按ID升序排序
            val sortedTodos = loadedTodos.sortedBy { it.id }

            Log.d("MainActivity", "加载到 ${sortedTodos.size} 条待办事项")

            // 直接更新适配器
            adapter.updateTodos(sortedTodos)

            // 同时更新本地的 todos 列表
            todos.clear()
            todos.addAll(sortedTodos)

            // 更新下一个可用的ID
            if (sortedTodos.isNotEmpty()) {
                nextId = sortedTodos.maxOf { it.id } + 1
            } else {
                nextId = 1
            }

            updateEmptyView()
            updateTodoCount()

            Log.d("MainActivity", "加载本地待办事项完成: ${sortedTodos.size} 条, nextId: $nextId")
        } catch (e: Exception) {
            Log.e("MainActivity", "加载失败", e)
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateEmptyView() {
        val displayMode = adapter.getDisplayMode()

        when (displayMode) {
            TodoAdapter.DisplayMode.ALL -> {
                binding.emptyView.text = "暂无待办事项\n点击右下角+号添加待办\n下拉刷新可同步云端数据"
            }
            TodoAdapter.DisplayMode.ACTIVE -> {
                binding.emptyView.text = "暂无未完成待办\n所有任务已完成！\n下拉刷新可同步云端数据"
            }
            TodoAdapter.DisplayMode.COMPLETED -> {
                binding.emptyView.text = "暂无已完成待办\n下拉刷新可同步云端数据"
            }
        }

        if (adapter.itemCount == 0) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    // 修改 updateTodoCount 方法，简化显示
    private fun updateTodoCount() {
        val displayMode = adapter.getDisplayMode()
        val displayCount = adapter.itemCount
        val totalTodos = adapter.getAllTodosCount()
        val activeTodos = adapter.getActiveTodosCount()
        val completedTodos = adapter.getCompletedTodosCount()

        val modeText = when (displayMode) {
            TodoAdapter.DisplayMode.ALL -> "全部 ($displayCount)"
            TodoAdapter.DisplayMode.ACTIVE -> "未完成 ($activeTodos)"
            TodoAdapter.DisplayMode.COMPLETED -> "已完成 ($completedTodos)"
        }

        // 简化显示：只显示当前模式和数量
        binding.todoCountText.text = "$modeText 条待办"
    }

    override fun onResume() {
        super.onResume()
        val currentTime = System.currentTimeMillis()
        val lastSyncTime = sharedPreferences.getLong("last_sync_time", 0)

        // 只有当距离上次同步超过5分钟时才执行同步
        if (currentTime - lastSyncTime > 5 * 60 * 1000) {
            performSync()
        }
    }

    override fun onPause() {
        super.onPause()
        saveLocalTodos()
        sharedPreferences.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::gitManager.isInitialized) {
            gitManager.cleanup()
        }
        debugDialog?.dismiss()
    }

    companion object {
        private var isFirstSync = true
    }
}