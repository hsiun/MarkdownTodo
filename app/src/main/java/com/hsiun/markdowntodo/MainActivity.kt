package com.hsiun.markdowntodo

import android.R
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.hsiun.markdowntodo.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(),
    TodoManager.TodoChangeListener,
    NoteManager.NoteChangeListener,
    SyncManager.SyncListener,
    TodoDialogManager.TodoDialogListener,
    NoteDialogManager.NoteDialogListener,
    SettingsManager.SettingsChangeListener,
    SettingsDialogManager.SettingsDialogListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    // 管理器实例
    lateinit var todoManager: TodoManager
    lateinit var noteManager: NoteManager
    lateinit var todoDialogManager: TodoDialogManager
    lateinit var noteDialogManager: NoteDialogManager
    lateinit var syncManager: SyncManager
    lateinit var settingsManager: SettingsManager
    lateinit var settingsDialogManager: SettingsDialogManager

    // 页面适配器
    private lateinit var mainPagerAdapter: MainPagerAdapter

    // 当前活动页面
    private var currentPage = 0 // 0=待办, 1=笔记

    // 同步状态
    private var isSyncing = false
    private var lastSyncTime: Long = 0

    companion object {
        // 使用伴生对象存储共享实例
        @Volatile
        private var instance: MainActivity? = null

        fun getInstance(): MainActivity? = instance
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保存实例引用
        instance = this

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayShowTitleEnabled(false)

        // 初始化SharedPreferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // 初始化所有管理器
        initManagers()

        // 设置ViewPager和页面
        setupViewPager()

        // 设置监听器
        setupListeners()

        // 设置下拉刷新
        setupSwipeRefresh()

        // 更新页面数量显示
        updatePageCounts()
        // 根据设置初始化TodoFragment的显示模式
        val showCompleted = settingsManager.showCompletedTodos
        updateTodoDisplayMode(showCompleted)

        Log.d("MainActivity", "应用启动完成")
    }

    private fun initManagers() {
        // 初始化SettingsManager
        settingsManager = SettingsManager(this)
        settingsManager.addSettingsChangeListener(this)

        // 初始化TodoManager
        todoManager = TodoManager(this)
        todoManager.setTodoChangeListener(this)
        todoManager.init()

        // 初始化NoteManager
        noteManager = NoteManager(this)
        noteManager.setNoteChangeListener(this)

        // 初始化对话框管理器
        todoDialogManager = TodoDialogManager(this)
        noteDialogManager = NoteDialogManager(this)

        // 初始化SettingsDialogManager
        settingsDialogManager = SettingsDialogManager(this)
        settingsDialogManager.setSettingsDialogListener(this)

        // 初始化SyncManager - 将 sharedPreferences 作为参数传递
        syncManager = SyncManager(this, todoManager, noteManager, sharedPreferences)
        syncManager.setSyncListener(this)

        // 配置GitManager（如果已配置）
        if (settingsManager.githubRepoUrl.isNotEmpty() && settingsManager.githubToken.isNotEmpty()) {
            syncManager.initGitManager(settingsManager.githubRepoUrl, settingsManager.githubToken)
        }
    }

    private fun setupViewPager() {
        mainPagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = mainPagerAdapter

        // 连接TabLayout和ViewPager
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "待办"
                1 -> "笔记"
                else -> "未知"
            }

            // 设置自定义的Tab视图，确保文字颜色正确
            val tabView = TextView(this).apply {
                text = tab.text
                gravity = Gravity.CENTER
                setTextColor(
                    ColorStateList(
                        arrayOf(
                            intArrayOf(R.attr.state_selected),
                            intArrayOf(-R.attr.state_selected)
                        ),
                        intArrayOf(
                            Color.parseColor("#FF9800"), // 选中时的颜色
                            Color.parseColor("#666666")  // 未选中时的颜色
                        )
                    )
                )
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD

            }

            tab.customView = tabView
        }.attach()

        // 监听页面切换
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateFabAction(position)
                updatePageTitle(position)

                // 手动更新Tab文字颜色
                for (i in 0 until binding.tabLayout.tabCount) {
                    val tab = binding.tabLayout.getTabAt(i)
                    val tabView = tab?.customView as? TextView
                    tabView?.isSelected = (i == position)
                }
            }
        })

        // 初始化第一个Tab为选中状态
        val firstTab = binding.tabLayout.getTabAt(0)
        val firstTabView = firstTab?.customView as? TextView
        firstTabView?.isSelected = true
    }

    private fun updatePageTitle(position: Int) {
        when (position) {
            0 -> {
                val activeCount = todoManager.getActiveTodosCount()
                val totalCount = todoManager.getAllTodos().size
                binding.todoCountText.text = "待办: $activeCount/$totalCount"
                binding.todoCountText.setTextColor(Color.parseColor("#333333"))
            }
            1 -> {
                val noteCount = noteManager.getAllNotes().size
                binding.todoCountText.text = "笔记: $noteCount"
                binding.todoCountText.setTextColor(Color.parseColor("#333333"))
            }
        }
    }

    private fun updateFabAction(position: Int) {
        binding.fab.setOnClickListener {
            when (position) {
                0 -> {
                    // 添加待办
                    todoDialogManager.showAddTodoDialog(this)
                }
                1 -> {
                    // 添加笔记
                    noteDialogManager.showAddNoteDialog(this)
                }
            }
        }
    }

    private fun setupListeners() {
        // 设置按钮点击
        binding.settingsButton.setOnClickListener {
            settingsDialogManager.showSimpleSettingsDialog(settingsManager)
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
            updatePageCounts()
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

    // NoteDialogManager.NoteDialogListener 实现
    override fun onAddNote(title: String, content: String) {
        try {
            val note = noteManager.addNote(title, content)
            syncManager.autoPushNote("添加笔记", note)
            Toast.makeText(this, "已添加笔记: $title", Toast.LENGTH_SHORT).show()
            updatePageCounts()
        } catch (e: Exception) {
            Toast.makeText(this, "添加笔记失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUpdateNote(id: Int, title: String, content: String) {
        try {
            val note = noteManager.updateNote(id, title, content)
            syncManager.autoPushNote("更新笔记", note)
            Toast.makeText(this, "已更新笔记: $title", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "更新笔记失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // TodoManager.TodoChangeListener 实现
    override fun onTodosChanged(todos: List<TodoItem>) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onTodoAdded(todo: TodoItem) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onTodoUpdated(todo: TodoItem) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onTodoDeleted(todo: TodoItem) {
        runOnUiThread {
            updatePageCounts()
            Toast.makeText(this, "已删除待办", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTodoError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // NoteManager.NoteChangeListener 实现
    override fun onNotesChanged(notes: List<NoteItem>) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onNoteAdded(note: NoteItem) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onNoteUpdated(note: NoteItem) {
        runOnUiThread {
            updatePageCounts()
        }
    }

    override fun onNoteDeleted(note: NoteItem) {
        runOnUiThread {
            updatePageCounts()
            Toast.makeText(this, "已删除笔记", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNoteError(message: String) {
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



    override fun onSyncProgress(message: String) {
        Log.d("MainActivity", message)
    }

    override fun onSyncStatusChanged(status: String) {
        runOnUiThread {
            Log.d("MainActivity-Sync", "状态变化: $status")
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

    private fun updatePageCounts() {
        when (currentPage) {
            0 -> {
                val activeCount = todoManager.getActiveTodosCount()
                val totalCount = todoManager.getAllTodos().size
                binding.todoCountText.text = "$activeCount/$totalCount 条待办"
            }
            1 -> {
                val noteCount = noteManager.getAllNotes().size
                binding.todoCountText.text = "$noteCount 条笔记"
            }
        }
    }

    private fun performSync(isManualRefresh: Boolean = false) {
        if (isSyncing) {
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSyncTime < 5000) { // 5秒冷却
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }

        isSyncing = true
        lastSyncTime = currentTime

        if (!syncManager.performSync(isManualRefresh)) {
            // 如果同步没有开始，重置状态
            isSyncing = false
            binding.swipeRefreshLayout.isRefreshing = false
            updateSyncIndicator("同步未开始", Color.parseColor("#666666"))
        }
    }

    // 同时确保 onSyncSuccess 和 onSyncError 都停止刷新
    override fun onSyncSuccess(message: String) {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = false
            isSyncing = false
            updateSyncIndicator("同步成功", Color.parseColor("#4CAF50"))
            Log.d("MainActivity", message)

            // 重新加载数据
            todoManager.loadLocalTodos()
            noteManager.loadAllNotes()
            updatePageCounts()
        }
    }

    override fun onSyncError(error: String) {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = false
            isSyncing = false
            updateSyncIndicator("同步失败", Color.parseColor("#F44336"))
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
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

    // 待办确认删除对话框
    fun showDeleteTodoConfirmationDialog(todo: TodoItem) {
        AlertDialog.Builder(this)
            .setTitle("删除待办")
            .setMessage("确定要删除 '${todo.title}' 吗？")
            .setPositiveButton("删除") { dialog, which ->
                todoManager.deleteTodo(todo.id)
                syncManager.autoPushTodo("删除", todo)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 笔记确认删除对话框
    fun showDeleteNoteConfirmationDialog(note: NoteItem) {
        AlertDialog.Builder(this)
            .setTitle("删除笔记")
            .setMessage("确定要删除 '${note.title}' 吗？")
            .setPositiveButton("删除") { dialog, which ->
                noteManager.deleteNote(note.id)
                syncManager.autoPushNote("删除笔记", note)
            }
            .setNegativeButton("取消", null)
            .show()
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

        // 重新初始化同步管理器
        if (repoUrl.isNotEmpty() && token.isNotEmpty()) {
            syncManager.initGitManager(repoUrl, token)
        }

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onResetSettings() {
        settingsManager.resetToDefaults()
        Toast.makeText(this, "设置已重置为默认值", Toast.LENGTH_SHORT).show()
    }

    // SettingsManager.SettingsChangeListener 实现
    override fun onGitSettingsChanged(repoUrl: String, token: String) {
        runOnUiThread {
            if (repoUrl.isNotEmpty() && token.isNotEmpty()) {
                syncManager.initGitManager(repoUrl, token)
                Toast.makeText(this, "Git设置已更新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDisplaySettingsChanged(showCompleted: Boolean) {
        runOnUiThread {
            // 可以在这里更新UI显示模式
            Log.d("MainActivity", "显示设置变更: showCompleted=$showCompleted")
            updateTodoDisplayMode(showCompleted)

        }
    }

    private fun updateTodoDisplayMode(showCompleted: Boolean) {
        // 找到当前的TodoFragment
        val fragments = supportFragmentManager.fragments
        for (fragment in fragments) {
            if (fragment is TodoFragment) {
                // 将布尔值转换为显示模式
                val displayMode = if (showCompleted) {
                    TodoAdapter.DisplayMode.ALL
                } else {
                    TodoAdapter.DisplayMode.ACTIVE
                }

                // 更新显示模式
                fragment.setDisplayMode(displayMode)
            }
        }
    }

    override fun onSyncSettingsChanged(autoSync: Boolean, interval: Int) {
        Log.d("MainActivity", "同步设置变更: autoSync=$autoSync, interval=$interval")
    }

    override fun onAppearanceSettingsChanged(themeColor: String) {
        Log.d("MainActivity", "主题颜色变更: $themeColor")
    }

    override fun onNotificationSettingsChanged(enabled: Boolean, vibration: Boolean) {
        Log.d("MainActivity", "通知设置变更: enabled=$enabled, vibration=$vibration")
    }

    override fun onSortSettingsChanged(sortBy: String) {
        Log.d("MainActivity", "排序方式变更: $sortBy")
    }

    // 内部类：ViewPager适配器
    inner class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> TodoFragment()
                1 -> NoteFragment()
                else -> TodoFragment()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val currentTime = System.currentTimeMillis()
        val lastSyncTime = sharedPreferences.getLong("last_sync_time", 0)

        if (currentTime - lastSyncTime > 5 * 60 * 1000) { // 5分钟自动同步
            performSync()
        }
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        syncManager.cleanup()
        settingsManager.cleanup()
        instance = null
    }
}