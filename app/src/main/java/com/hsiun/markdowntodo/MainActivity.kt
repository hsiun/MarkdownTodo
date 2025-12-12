package com.hsiun.markdowntodo

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.databinding.ActivityMainBinding
import android.content.SharedPreferences
import android.util.Log

class MainActivity : AppCompatActivity(),
    TodoManager.TodoChangeListener,
    SyncManager.SyncListener,
    TodoDialogManager.TodoDialogListener,
    SettingsManager.SettingsChangeListener,
    SettingsDialogManager.SettingsDialogListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TodoAdapter
    private lateinit var sharedPreferences: SharedPreferences

    // 管理器实例
    private lateinit var todoManager: TodoManager
    private lateinit var todoDialogManager: TodoDialogManager
    private lateinit var syncManager: SyncManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var settingsDialogManager: SettingsDialogManager
    // Git 配置变量
    private var githubRepoUrl: String = ""
    private var githubToken: String = ""
    private var showCompletedTodos = false

    // ItemTouchHelper 用于左滑删除
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置系统工具栏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // 初始化SharedPreferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // 初始化设置管理器
        settingsManager = SettingsManager(this)
        settingsManager.addSettingsChangeListener(this)

        // 初始化设置对话框管理器
        settingsDialogManager = SettingsDialogManager(this)
        settingsDialogManager.setSettingsDialogListener(this)

        // 加载Git配置
        githubRepoUrl = settingsManager.githubRepoUrl
        githubToken = settingsManager.githubToken
        showCompletedTodos = settingsManager.showCompletedTodos

        // 初始化管理器
        initManagers()

        // 初始化列表和适配器
        setupRecyclerView()

        // 设置监听器
        setupListeners()

        // 设置下拉刷新
        setupSwipeRefresh()

        // 设置选项卡
        setupTabs()

        Log.d("MainActivity", "应用启动完成")
    }

    private fun initManagers() {
        // 初始化TodoManager
        todoManager = TodoManager(this)
        todoManager.setTodoChangeListener(this)
        todoManager.init()

        // 初始化TodoDialogManager
        todoDialogManager = TodoDialogManager(this)

        // 初始化SyncManager
        syncManager = SyncManager(this, todoManager, sharedPreferences)
        syncManager.setSyncListener(this)

        // 配置GitManager（如果已配置）
        if (githubRepoUrl.isNotEmpty() && githubToken.isNotEmpty()) {
            syncManager.initGitManager(githubRepoUrl, githubToken)
        }
    }


    private fun setupRecyclerView() {
        adapter = TodoAdapter(
            mutableListOf(),
            onTodoChanged = { todo ->
                // 当复选框状态改变时调用
                todoManager.toggleTodoStatus(todo.id)
            },
            onTodoDeleted = { todo ->
                // 当点击删除按钮时调用
                showDeleteConfirmationDialog(todo)
            },
            onTodoClicked = { todo ->
                // 当点击待办项时调用
                todoDialogManager.showEditTodoDialog(todo, this)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 设置左滑删除功能
        setupSwipeToDelete()

        // 设置初始显示模式
        adapter.setDisplayMode(if (showCompletedTodos) TodoAdapter.DisplayMode.ALL else TodoAdapter.DisplayMode.ACTIVE)

        // 更新初始状态
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
                        showDeleteConfirmationDialog(todo, position)
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
                    val deleteButton = itemView.findViewById<android.widget.ImageButton>(R.id.deleteButton)

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

    private fun showDeleteConfirmationDialog(todo: TodoItem, position: Int = -1) {
        AlertDialog.Builder(this)
            .setTitle("删除待办")
            .setMessage("确定要删除 '${todo.title}' 吗？")
            .setPositiveButton("删除") { dialog, which ->
                // 执行删除操作
                todoManager.deleteTodo(todo.id)
                // 如果是从左滑删除触发的，需要通知适配器恢复视图
                if (position != -1) {
                    adapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("取消") { dialog, which ->
                // 取消删除，恢复原状
                if (position != -1) {
                    adapter.notifyItemChanged(position)
                }
            }
            .setOnCancelListener {
                // 对话框被取消，恢复原状
                if (position != -1) {
                    adapter.notifyItemChanged(position)
                }
            }
            .show()
    }

    private fun setupListeners() {
        // 设置按钮点击 - 使用新的设置对话框
        binding.settingsButton.setOnClickListener {
            // 显示简单版或高级版设置对话框
            // settingsDialogManager.showSimpleSettingsDialog(settingsManager)
            settingsDialogManager.showSettingsDialog(settingsManager)
        }


        // 悬浮按钮点击 - 弹出添加待办对话框
        binding.fab.setOnClickListener {
            todoDialogManager.showAddTodoDialog(this)
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

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(
            Color.parseColor("#865EDC"),
            Color.parseColor("#1A73E8"),
            Color.parseColor("#4CAF50")
        )

        binding.swipeRefreshLayout.setOnRefreshListener {
            performSync(true)
        }

        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#FFFFFF"))
    }

    // TodoDialogManager.TodoDialogListener 实现
    override fun onAddTodo(title: String, setReminder: Boolean) {
        try {
            val todo = todoManager.addTodo(title, setReminder)
            syncManager.autoPushTodo("添加", todo)
            Toast.makeText(this, "已添加: $title", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUpdateTodo(id: Int, newTitle: String, setReminder: Boolean) {
        try {
            val todo = todoManager.updateTodo(id, newTitle, setReminder)
            syncManager.autoPushTodo("更新", todo)
            Toast.makeText(this, "已更新: $newTitle", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCancel() {
        // 对话框取消，不需要特殊处理
    }

    // TodoManager.TodoChangeListener 实现
    override fun onTodosChanged(todos: List<TodoItem>) {
        runOnUiThread {
            updateTodoDisplay()
            updateTodoCount()
            updateEmptyView()
        }
    }

    override fun onTodoAdded(todo: TodoItem) {
        runOnUiThread {
            // 添加到适配器
            adapter.updateTodos(todoManager.getAllTodos())
            updateTodoCount()
            updateEmptyView()
        }
    }

    override fun onTodoUpdated(todo: TodoItem) {
        runOnUiThread {
            // 更新适配器中的特定待办项
            adapter.updateTodos(todoManager.getAllTodos())
            updateTodoCount()
            updateEmptyView()
        }
    }

    override fun onTodoDeleted(todo: TodoItem) {
        runOnUiThread {
            adapter.removeTodo(todo)
            updateTodoCount()
            updateEmptyView()
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTodoError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // SyncManager.SyncListener 实现
    override fun onSyncStarted() {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = true
            updateSyncIndicator("正在同步...", Color.parseColor("#FF9800"))
        }
    }

    override fun onSyncSuccess(message: String) {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = false
            updateSyncIndicator("同步成功", Color.parseColor("#4CAF50"))
            Log.d("MainActivity", message)
        }
    }

    override fun onSyncError(error: String) {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = false
            updateSyncIndicator("同步失败", Color.parseColor("#F44336"))
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSyncStatusChanged(status: String) {
        runOnUiThread {
            binding.syncStatusText.text = when {
                status.contains("正在同步") -> "🔄"
                status.contains("成功") -> "✅"
                status.contains("失败") -> "❌"
                status.contains("未连接") -> "⚪"
                else -> "⚪"
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

    private fun updateTodoDisplay() {
        // 更新适配器中的所有待办
        adapter.updateTodos(todoManager.getAllTodos())

        // 根据显示模式更新适配器
        adapter.setDisplayMode(if (showCompletedTodos) TodoAdapter.DisplayMode.ALL else TodoAdapter.DisplayMode.ACTIVE)
    }

    private fun performSync(isManualRefresh: Boolean = false) {
        if (!syncManager.performSync(isManualRefresh)) {
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun updateSyncIndicator(status: String = "", color: Int? = null) {
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
    }

    private fun updateTodoCount() {
        val total = todoManager.getAllTodos().size
        val active = todoManager.getActiveTodosCount()

        val modeText = if (showCompletedTodos) {
            "全部 ($total)"
        } else {
            "未完成 ($active)"
        }

        binding.todoCountText.text = "$modeText 条待办"
    }

    private fun updateEmptyView() {
        val hasTodos = adapter.itemCount > 0

        if (showCompletedTodos) {
            binding.emptyView.text = "暂无待办事项\n点击右下角+号添加待办\n下拉刷新可同步云端数据"
        } else {
            binding.emptyView.text = "暂无未完成待办\n所有任务已完成！\n下拉刷新可同步云端数据"
        }

        binding.emptyView.visibility = if (hasTodos) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasTodos) View.VISIBLE else View.GONE
    }


    override fun onResume() {
        super.onResume()
        val currentTime = System.currentTimeMillis()
        val lastSyncTime = sharedPreferences.getLong("last_sync_time", 0)

        if (currentTime - lastSyncTime > 5 * 60 * 1000) {
            performSync()
        }
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
    }

    override fun onGitSettingsChanged(repoUrl: String, token: String) {
        Log.d("MainActivity", "git设置变更: $repoUrl")
    }

    override fun onDisplaySettingsChanged(showCompleted: Boolean) {
        Log.d("MainActivity", "显示变更: $showCompleted")
    }


    override fun onSyncSettingsChanged(autoSync: Boolean, interval: Int) {
        // 可以在这里处理自动同步设置变更
        Log.d("MainActivity", "同步设置变更: autoSync=$autoSync, interval=$interval")
    }

    override fun onAppearanceSettingsChanged(themeColor: String) {
        // 可以在这里更新应用主题颜色
        Log.d("MainActivity", "主题颜色变更: $themeColor")
    }

    override fun onNotificationSettingsChanged(enabled: Boolean, vibration: Boolean) {
        // 可以在这里更新通知设置
        Log.d("MainActivity", "通知设置变更: enabled=$enabled, vibration=$vibration")
    }

    override fun onSortSettingsChanged(sortBy: String) {
        // 可以在这里处理排序方式变更
        Log.d("MainActivity", "排序方式变更: $sortBy")
    }

    // SettingsDialogManager.SettingsDialogListener 实现
    override fun onSaveSettings(
        repoUrl: String,
        token: String,
        showCompleted: Boolean,
        autoSync: Boolean,
        syncInterval: Int,
        themeColor: String,
        notificationEnabled: Boolean,
        vibrationEnabled: Boolean,
        sortBy: String
    ) {
        // 保存所有设置
        settingsManager.saveAllSettings(
            repoUrl,
            token,
            showCompleted,
            autoSync,
            syncInterval,
            themeColor,
            notificationEnabled,
            vibrationEnabled,
            sortBy
        )

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }


    override fun onResetSettings() {
        settingsManager.resetToDefaults()
        Toast.makeText(this, "设置已重置为默认值", Toast.LENGTH_SHORT).show()
    }

    // 删除旧的 saveAllSettings 和 showSettingsDialog 方法

    override fun onDestroy() {
        super.onDestroy()
        syncManager.cleanup()
        settingsManager.cleanup()
    }
}