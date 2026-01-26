package com.hsiun.markdowntodo

import com.hsiun.markdowntodo.NoteItem
import android.R
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.hsiun.markdowntodo.databinding.ActivityMainBinding
import java.io.File

/**
 * 主活动类，负责协调应用的主要功能
 * 
 * 功能包括：
 * - 管理待办事项和笔记的显示
 * - 处理页面切换（待办/笔记）
 * - 协调各个管理器（TodoManager、NoteManager、SyncManager等）
 * - 处理用户交互和事件回调
 * - 管理Git同步功能
 * 
 * @author hsiun
 */
class MainActivity : AppCompatActivity(),
    TodoManager.TodoChangeListener,
    NoteManager.NoteChangeListener,
    SyncManager.SyncListener,
    TodoDialogManager.TodoDialogListener,
    NoteDialogManager.NoteDialogListener,
    SettingsManager.SettingsChangeListener,
    SettingsDialogManager.SettingsDialogListener,
    TodoListDialog.CreateTodoListListener {

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
    private lateinit var todoListManager: TodoListManager
    private lateinit var todoListSpinnerAdapter: TodoListSpinnerAdapter
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

    fun openNoteEditPage(note: NoteItem? = null, isNewNote: Boolean = false) {
        val intent = Intent(this, NoteEditActivity::class.java).apply {
            if (note != null) {
                putExtra("uuid", note.uuid)
                putExtra("isNewNote", false)
            } else if (isNewNote) {
                putExtra("isNewNote", true)
            }
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保存实例引用
        instance = this
        // 处理通知点击
        handleNotificationClick(intent)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayShowTitleEnabled(false)

        // 初始化SharedPreferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // 初始化所有管理器
        initManagers()

        // 初始隐藏笔记标题，显示Spinner（默认是待办页面）
        binding.notesTitleText.visibility = View.GONE
        binding.todoListSpinner.visibility = View.VISIBLE

        setupTodoListSpinner()

        // 设置TodoDialogManager的FragmentManager
        todoDialogManager.setFragmentManager(supportFragmentManager)
        // 新增：初始化提醒调度
        initReminderScheduler()
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

    private fun handleNotificationClick(intent: Intent?) {
        val todoId = intent?.getIntExtra("open_todo_id", -1) ?: -1
        if (todoId != -1) {
            // 延迟一段时间，确保UI已初始化
            Handler(Looper.getMainLooper()).postDelayed({
                openTodoById(todoId)
            }, 500)
        }
    }
    private fun initReminderScheduler() {
        // 检查并重新调度所有未触发的提醒
        todoManager.rescheduleAllReminders()

        // 检查是否有到期的提醒需要立即触发
        todoManager.checkAndTriggerReminders()
    }
    private fun initManagers() {
        ensureGitDirectoryStructure()
        // 初始化SettingsManager
        settingsManager = SettingsManager(this)
        settingsManager.addSettingsChangeListener(this)

        todoListManager = TodoListManager(this)

        // 初始化TodoManager
        todoManager = TodoManager(this)
        todoManager.setTodoChangeListener(this)
        todoManager.init(todoListManager)

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
        syncManager = SyncManager(this, todoManager, noteManager, todoListManager)
        syncManager.setSyncListener(this)

        // 配置GitManager（如果已配置）
        if (settingsManager.githubRepoUrl.isNotEmpty() && settingsManager.githubToken.isNotEmpty()) {
            syncManager.initGitManager(settingsManager.githubRepoUrl, settingsManager.githubToken)
        }
    }

    private fun ensureGitDirectoryStructure() {
        val gitRepoDir = File(filesDir, "git_repo")
        if (!gitRepoDir.exists()) {
            gitRepoDir.mkdirs()
        }

        val todoListsDir = File(gitRepoDir, "todo_lists")
        if (!todoListsDir.exists()) {
            todoListsDir.mkdirs()
        }

        val notesDir = File(gitRepoDir, "notes")
        if (!notesDir.exists()) {
            notesDir.mkdirs()
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
                // 根据页面显示不同的内容
                updatePageTitle(position)

                // 手动更新Tab文字颜色
                for (i in 0 until binding.tabLayout.tabCount) {
                    val tab = binding.tabLayout.getTabAt(i)
                    val tabView = tab?.customView as? TextView
                    tabView?.isSelected = (i == position)
                }
            }
            override fun onPageScrollStateChanged(state: Int) {
                // 在页面滚动状态变化时也更新标题，确保及时性
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    updatePageTitle(binding.viewPager.currentItem)
                }
            }
        })

        // 初始化第一个Tab为选中状态
        val firstTab = binding.tabLayout.getTabAt(0)
        val firstTabView = firstTab?.customView as? TextView
        firstTabView?.isSelected = true
    }
    // 添加辅助方法：确保页面标题视图状态正确
    private fun ensurePageTitleConsistency() {
        when (currentPage) {
            0 -> {
                if (binding.todoListSpinner.visibility != View.VISIBLE) {
                    binding.todoListSpinner.visibility = View.VISIBLE
                }
                if (binding.notesTitleText.visibility != View.GONE) {
                    binding.notesTitleText.visibility = View.GONE
                }
            }
            1 -> {
                if (binding.todoListSpinner.visibility != View.GONE) {
                    binding.todoListSpinner.visibility = View.GONE
                }
                if (binding.notesTitleText.visibility != View.VISIBLE) {
                    binding.notesTitleText.visibility = View.VISIBLE
                    updateNotesTitle()
                }
            }
        }
    }
    private fun refreshSpinner() {
        todoListSpinnerAdapter = TodoListSpinnerAdapter(this, todoListManager.getAllLists())
        val spinner = binding.todoListSpinner
        spinner.adapter = todoListSpinnerAdapter

        val currentListIndex = todoListManager.getAllLists().indexOfFirst { it.isSelected }
        if (currentListIndex != -1) {
            spinner.setSelection(currentListIndex)
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
                    openNoteEditPage(isNewNote = true)                }
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

    override fun onAddTodo(title: String, setReminder: Boolean, remindTime: Long, repeatType: Int) {
        try {
            val todo = todoManager.addTodo(title, setReminder, remindTime, repeatType)
            syncManager.autoPushTodo("添加", todo)
            Toast.makeText(this, "已添加: $title", Toast.LENGTH_SHORT).show()
            updatePageCounts()
        } catch (e: Exception) {
            Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUpdateTodo(id: Int, newTitle: String, setReminder: Boolean, remindTime: Long, repeatType: Int) {
        try {
            val todo = todoManager.updateTodo(id, newTitle, setReminder, remindTime, repeatType)
            syncManager.autoPushTodo("更新", todo)
            Toast.makeText(this, "已更新: $newTitle", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCancel() {
        // 对话框取消，不需要特殊处理
        // 重置Spinner选择
        val spinner = binding.todoListSpinner
        val currentListIndex = todoListManager.getAllLists().indexOfFirst { it.isSelected }
        if (currentListIndex != -1) {
            spinner.setSelection(currentListIndex)
        }
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

    override fun onUpdateNote(uuid: String, title: String, content: String) {
        try {
            val note = noteManager.updateNote(uuid, title, content)
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
            if (currentPage == 1) { // 只有在笔记页面才更新
                updateNotesTitle()
            }
        }
    }

    override fun onNoteAdded(note: NoteItem) {
        runOnUiThread {
            if (currentPage == 1) {
                updateNotesTitle()
            }
        }
    }

    override fun onNoteUpdated(note: NoteItem) {
        runOnUiThread {
            if (currentPage == 1) {
                updateNotesTitle()
            }
        }
    }

    override fun onNoteDeleted(note: NoteItem) {
        runOnUiThread {
            if (currentPage == 1) {
                updateNotesTitle()
                Toast.makeText(this, "已删除笔记", Toast.LENGTH_SHORT).show()
            }
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
    // 新增方法：根据页面更新顶部标题
    private fun updatePageTitle(position: Int) {
        when (position) {
            0 -> {
                // 待办页面 - 显示Spinner和待办列表
                binding.todoListSpinner.visibility = View.VISIBLE
                binding.notesTitleText.visibility = View.GONE
                // 刷新Spinner数据
                refreshSpinner()
            }
            1 -> {
                // 笔记页面 - 显示笔记标题
                binding.todoListSpinner.visibility = View.GONE
                binding.notesTitleText.visibility = View.VISIBLE
                updateNotesTitle() // 更新笔记标题
            }
        }
    }
    // 新增方法：更新笔记标题
    private fun updateNotesTitle() {
        val noteCount = noteManager.getAllNotes().size
        binding.notesTitleText.text = "默认笔记 ($noteCount) 条"
    }
    private fun updatePageCounts() {
        when (currentPage) {
            0 -> {
                val activeCount = todoManager.getActiveTodosCount()
                val totalCount = todoManager.getAllTodos().size
                // 更新当前列表的统计信息
                val currentListId = todoListManager.getCurrentListId()
                todoListManager.updateListCount(currentListId, totalCount, activeCount)
                refreshSpinner()

                // 确保视图状态正确
                ensurePageTitleConsistency()
            }
            1 -> {
                // 笔记页面 - 更新笔记标题
                updateNotesTitle()
                // 确保视图状态正确
                ensurePageTitleConsistency()
            }
        }
    }

    /**
     * 执行同步操作
     * 
     * @param isManualRefresh 是否为手动刷新
     */
    private fun performSync(isManualRefresh: Boolean = false) {
        // 防止重复同步
        if (isSyncing) {
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }

        // 同步冷却时间：5秒内不重复同步
        val currentTime = System.currentTimeMillis()
        if (!isManualRefresh && currentTime - lastSyncTime < 5000) {
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }

        // 检查Git配置
        if (settingsManager.githubRepoUrl.isEmpty() || settingsManager.githubToken.isEmpty()) {
            binding.swipeRefreshLayout.isRefreshing = false
            if (isManualRefresh) {
                Toast.makeText(this, "请先在设置中配置Git仓库信息", Toast.LENGTH_SHORT).show()
            }
            return
        }

        isSyncing = true
        lastSyncTime = currentTime

        try {
            if (!syncManager.performSync(isManualRefresh)) {
                // 如果同步没有开始，重置状态
                isSyncing = false
                binding.swipeRefreshLayout.isRefreshing = false
                updateSyncIndicator("同步未开始", Color.parseColor("#666666"))
            }
        } catch (e: Exception) {
            // 捕获同步过程中的异常
            isSyncing = false
            binding.swipeRefreshLayout.isRefreshing = false
            Log.e("MainActivity", "同步异常", e)
            Toast.makeText(this, "同步异常: ${e.message}", Toast.LENGTH_SHORT).show()
            updateSyncIndicator("同步失败", Color.parseColor("#F44336"))
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
            todoManager.loadCurrentListTodos()  // 改为这个
            noteManager.loadAllNotes()
            updatePageCounts()
        }
    }

    // 在 onSyncError 方法中添加冲突处理的提示
    override fun onSyncError(error: String) {
        runOnUiThread {
            binding.swipeRefreshLayout.isRefreshing = false
            isSyncing = false

            if (error.contains("冲突") || error.contains("Checkout conflict")) {
                // 冲突相关的错误，提示用户
                updateSyncIndicator("同步冲突", Color.parseColor("#FF9800"))
                Toast.makeText(this, "检测到同步冲突，已自动处理", Toast.LENGTH_LONG).show()

                // 重新加载数据
                todoManager.loadCurrentListTodos()
                noteManager.loadAllNotes()
                updatePageCounts()
            } else {
                updateSyncIndicator("同步失败", Color.parseColor("#F44336"))
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
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
// MainActivity.kt
    fun showDeleteNoteConfirmationDialog(note: NoteItem) {
        AlertDialog.Builder(this)
            .setTitle("删除笔记")
            .setMessage("确定要删除 '${note.title}' 吗？")
            .setPositiveButton("删除") { dialog, which ->
                Log.d("MainActivity", "开始删除笔记: ${note.title}")

                try {
                    // 1. 删除本地数据
                    val deletedNote = noteManager.deleteNote(note.uuid)

                    // 2. 删除Git仓库中的文件
                    syncManager.deleteNoteFromGit(deletedNote)

                    // 3. 立即更新UI
                    updatePageCounts()

                    Toast.makeText(this, "已删除笔记", Toast.LENGTH_SHORT).show()

                    // 4. 记录删除日志
                    Log.d("MainActivity", "笔记删除完成: ${note.title}, 剩余笔记: ${noteManager.getAllNotes().size}")

                } catch (e: Exception) {
                    Log.e("MainActivity", "删除笔记失败", e)
                    Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // 在删除后添加验证
        Handler(Looper.getMainLooper()).postDelayed({
            val isDeleted = noteManager.verifyNoteDeleted(note.uuid)
            Log.d("MainActivity", "笔记删除验证结果: UUID=${note.uuid}, 是否删除=$isDeleted")

            if (!isDeleted) {
                Toast.makeText(this, "警告：笔记可能未被完全删除", Toast.LENGTH_LONG).show()
            }
        }, 1000)
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

    // 添加打开特定待办的方法（用于通知点击）
    fun openTodoById(todoId: Int) {
        val todo = todoManager.getTodoById(todoId)
        if (todo != null) {
            // 切换到待办页面
            binding.viewPager.currentItem = 0
            // 打开编辑对话框
            todoDialogManager.showEditTodoDialog(todo, this)
        }
    }

    // 在onResume方法中添加检查提醒
    override fun onResume() {
        super.onResume()
        val currentTime = System.currentTimeMillis()
        val lastSyncTime = sharedPreferences.getLong("last_sync_time", 0)

        if (currentTime - lastSyncTime > 5 * 60 * 1000) { // 5分钟自动同步
            performSync()
        }

        // 每次回到应用时检查提醒
        todoManager.checkAndTriggerReminders()

        // 新增：检查待办数据一致性
        todoManager.ensureTodoConsistency()
        // 确保页面标题视图状态正确
        ensurePageTitleConsistency()
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
    private fun setupTodoListSpinner() {
        // 移除原来的 todoCountText，改用 Spinner
        val spinner = binding.todoListSpinner

        // 创建适配器
        todoListSpinnerAdapter = TodoListSpinnerAdapter(this, todoListManager.getAllLists())
        spinner.adapter = todoListSpinnerAdapter

        // 设置选中项
        val currentListIndex = todoListManager.getAllLists().indexOfFirst { it.isSelected }
        if (currentListIndex != -1) {
            spinner.setSelection(currentListIndex)
        }

        // 设置选择监听器
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (todoListSpinnerAdapter.isNewItem(position)) {
                    // 显示新建列表对话框
                    showCreateTodoListDialog()
                } else {
                    val selectedList = todoListSpinnerAdapter.getItem(position)
                    selectedList?.let { list ->
                        if (!list.isSelected) {
                            // 切换列表
                            switchTodoList(list.id)
                        }
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 不做任何操作
            }
        }
    }
    private fun switchTodoList(listId: String) {
        if (todoManager.switchTodoList(listId)) {
            // 更新UI
            updatePageCounts()
            refreshSpinner()

            // 刷新待办列表显示
            val todoFragment = supportFragmentManager.findFragmentByTag("todo_fragment") as? TodoFragment
            todoFragment?.loadTodos()

            // 新增：立即同步列表元数据到 Git
            syncManager.autoPushTodoLists("切换列表到 ${todoListManager.getCurrentListName()}")


            Toast.makeText(this, "已切换到 ${todoListManager.getCurrentListName()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateTodoListDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("新建待办列表")

        val input = EditText(this)
        input.hint = "请输入列表名称"

        // 简单的样式设置
        input.setTextColor(Color.BLACK)
        input.textSize = 16f
        input.setHintTextColor(Color.GRAY)

        // 添加边距
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.setMargins(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        input.layoutParams = layoutParams

        // 设置背景
        input.setBackgroundColor(Color.WHITE)
        input.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(input)

        builder.setView(container)

        builder.setPositiveButton("创建") { dialog, which ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                onTodoListCreated(name)
            } else {
                Toast.makeText(this, "列表名称不能为空", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("取消") { dialog, which ->
            dialog.cancel()
        }

        val dialog = builder.create()
        dialog.show()

        // 设置按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#865EDC"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.GRAY)
    }
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    override fun onTodoListCreated(name: String) {
        Log.d("MainActivity", "开始创建列表: $name")

        val newList = todoListManager.createList(name)
        if (newList != null) {
            // 记录当前列表状态
            val previousListId = todoListManager.getCurrentListId()
            val previousList = todoListManager.getCurrentList()
            Log.d("MainActivity", "创建新列表前，当前列表: ${previousList?.name}")

            // 切换到新列表
            if (todoManager.switchTodoList(newList.id)) {
                Toast.makeText(this, "已创建列表: $name", Toast.LENGTH_SHORT).show()

                // 验证默认列表是否被修改
                val defaultList = todoListManager.getAllLists().find { it.isDefault }
                if (defaultList != null && defaultList.id != newList.id) {
                    // 检查默认列表的文件是否被修改
                    val defaultFile = File(filesDir, "git_repo/todo_lists/${defaultList.fileName}")
                    if (defaultFile.exists()) {
                        val defaultTodos = todoManager.readTodosFromFile(defaultFile)
                        Log.d("MainActivity", "默认列表 '${defaultList.name}' 仍有 ${defaultTodos.size} 条待办")
                    }
                }
            } else {
                Toast.makeText(this, "切换列表失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "创建失败，可能已存在同名列表", Toast.LENGTH_SHORT).show()
        }

        refreshSpinner()
    }

}