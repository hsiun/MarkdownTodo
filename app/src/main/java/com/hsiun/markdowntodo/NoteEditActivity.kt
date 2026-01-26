package com.hsiun.markdowntodo

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hsiun.markdowntodo.databinding.ActivityNoteEditBinding

class NoteEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditBinding
    private var uuid: String = ""
    private var isNewNote: Boolean = false
    private var isEditMode: Boolean = false
    private lateinit var mainActivity: MainActivity
    private var originalNote: NoteItem? = null
    
    // 用于区分点击和滑动的变量
    private var touchStartY = 0f
    private var isScrolling = false
    
    // 用于工具栏显示/隐藏的变量
    private var isToolbarVisible = true
    private var isScrollListenerEnabled = false
    private var lastScrollY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取MainActivity实例
        mainActivity = MainActivity.getInstance() ?: run {
            Toast.makeText(this, "无法获取主活动", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 设置工具栏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        // 设置工具栏背景为白色，移除阴影
        binding.toolbar.setBackgroundColor(android.graphics.Color.WHITE)
        binding.toolbar.setTitleTextColor(android.graphics.Color.BLACK)
        binding.toolbar.elevation = 0f
        
        // 设置状态栏颜色为白色
        window.statusBarColor = android.graphics.Color.WHITE
        
        // 设置状态栏图标为深色（适配白色背景）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }
        
        // Android 11+ 使用新的API
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        }

        // 获取传递的数据
        uuid = intent.getStringExtra("uuid") ?: ""
        isNewNote = intent.getBooleanExtra("isNewNote", false)

        Log.d("NoteEditActivity", "接收参数: uuid=$uuid, isNewNote=$isNewNote")

        if (isNewNote) {
            // 新建笔记，直接进入编辑模式
            isEditMode = true
            supportActionBar?.title = "新建笔记"
            binding.toolbar.title = "新建笔记"
            enterEditMode()
        } else if (uuid.isNotEmpty()) {
            // 查看现有笔记，默认进入查看模式
            val note = mainActivity.noteManager.getAllNotes().find { it.uuid == uuid }
            if (note != null) {
                originalNote = note
                supportActionBar?.title = "查看笔记"
                binding.toolbar.title = "查看笔记"
                enterViewMode()
                Log.d("NoteEditActivity", "加载笔记: ${note.title}")
            } else {
                Toast.makeText(this, "未找到笔记", Toast.LENGTH_SHORT).show()
                Log.e("NoteEditActivity", "未找到笔记UUID: $uuid")
                finish()
                return
            }
        } else {
            // 无效状态，返回
            Log.e("NoteEditActivity", "无效参数")
            finish()
            return
        }

        // 设置按钮点击事件
        setupButtons()
    }
    
    /**
     * 进入查看模式
     */
    private fun enterViewMode() {
        isEditMode = false
        isScrollListenerEnabled = true  // 确保启用滚动监听
        
        // 确保工具栏始终显示且位置正确
        isToolbarVisible = true
        binding.toolbar.translationY = 0f
        
        originalNote?.let { note ->
            // 显示查看模式的视图
            binding.noteTitleTextView.visibility = View.VISIBLE
            binding.noteTitleTextView.text = note.title
            
            binding.noteContentScrollView.visibility = View.VISIBLE
            binding.noteContentTextView.text = note.content
            
            // 隐藏编辑模式的视图
            binding.noteTitleInputLayout.visibility = View.GONE
            binding.noteContentEditText.visibility = View.GONE
            binding.hintTextView.visibility = View.GONE
            binding.buttonContainer.visibility = View.GONE
            
            // 重置滚动位置
            binding.noteContentScrollView.scrollTo(0, 0)
            lastScrollY = 0
            
            // 设置滚动监听，实现工具栏的显示/隐藏
            setupScrollListener()
            
            // 设置内容区域点击事件，点击后进入编辑模式
            // 使用OnTouchListener来区分点击和滑动，同时检测滚动
            binding.noteContentScrollView.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.y
                        isScrolling = false
                        // 更新初始滚动位置
                        lastScrollY = binding.noteContentScrollView.scrollY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = kotlin.math.abs(event.y - touchStartY)
                        if (deltaY > 10) { // 如果移动超过10像素，认为是滑动
                            isScrolling = true
                        }
                        
                        // 不再需要隐藏工具栏，工具栏始终显示
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isScrolling) {
                            // 如果没有滑动，认为是点击，进入编辑模式
                            enterEditMode()
                        }
                        // 重置状态
                        isScrolling = false
                    }
                }
                false // 返回false，让ScrollView继续处理滑动事件
            }
            
            // 标题和内容都在同一个ScrollView中，点击ScrollView即可进入编辑模式
            // 不需要单独设置标题点击事件
            
        supportActionBar?.title = "查看笔记"
        binding.toolbar.title = "查看笔记"
        }
    }
    
    /**
     * 设置滚动监听（现在不再需要隐藏工具栏，保留此方法以备将来使用）
     */
    private fun setupScrollListener() {
        isScrollListenerEnabled = true
        // 工具栏始终显示，不需要滚动监听来控制显示/隐藏
    }
    
    /**
     * 隐藏工具栏
     */
    private fun hideToolbar() {
        if (!isToolbarVisible) return
        
        isToolbarVisible = false
        val toolbarHeight = if (binding.toolbar.height > 0) {
            binding.toolbar.height
        } else {
            // 如果高度还未测量，使用固定值（通常工具栏高度约为56dp）
            (56 * resources.displayMetrics.density).toInt()
        }
        
        binding.toolbar.animate()
            .translationY(-toolbarHeight.toFloat())
            .setDuration(200)
            .start()
    }
    
    /**
     * 显示工具栏
     */
    private fun showToolbar() {
        if (isToolbarVisible) return
        
        isToolbarVisible = true
        binding.toolbar.animate()
            .translationY(0f)
            .setDuration(200)
            .start()
    }
    
    /**
     * 进入编辑模式
     */
    private fun enterEditMode() {
        isEditMode = true
        
        // 确保工具栏始终显示（编辑模式下工具栏应该始终显示）
        isToolbarVisible = true
        binding.toolbar.translationY = 0f
        
        // 禁用滚动监听（编辑模式下不需要）
        isScrollListenerEnabled = false
        
        // 隐藏查看模式的视图
        binding.noteTitleTextView.visibility = View.GONE
        binding.noteContentScrollView.visibility = View.GONE
        
        // 显示编辑模式的视图
        binding.noteTitleInputLayout.visibility = View.VISIBLE
        binding.noteContentEditText.visibility = View.VISIBLE
        binding.hintTextView.visibility = View.VISIBLE
        binding.buttonContainer.visibility = View.VISIBLE
        
        // 填充数据
        originalNote?.let { note ->
            binding.noteTitleEditText.setText(note.title)
            binding.noteContentEditText.setText(note.content)
        }
        
        supportActionBar?.title = if (isNewNote) "新建笔记" else "编辑笔记"
        binding.toolbar.title = if (isNewNote) "新建笔记" else "编辑笔记"
        
        // 移除内容区域的触摸事件（编辑模式下不需要）
        binding.noteContentScrollView.setOnTouchListener(null)
    }

    private fun setupButtons() {
        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveNote()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun saveNote() {
        val title = binding.noteTitleEditText.text.toString().trim()
        val content = binding.noteContentEditText.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(this, "标题不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (isNewNote) {
                // 新建笔记
                val note = mainActivity.noteManager.addNote(title, content)
                Toast.makeText(this, "已添加笔记: $title", Toast.LENGTH_SHORT).show()
                // 同步到Git
                mainActivity.syncManager.autoPushNote("添加笔记", note)
                Log.d("NoteEditActivity", "新建笔记成功: ${note.title}")
            } else if (uuid.isNotEmpty()) {
                // 更新现有笔记（使用UUID查找）
                val existingNote = mainActivity.noteManager.getAllNotes().find { it.uuid == uuid }
                if (existingNote != null) {
                    val note = mainActivity.noteManager.updateNote(uuid, title, content)
                    Toast.makeText(this, "已更新笔记: $title", Toast.LENGTH_SHORT).show()
                    // 同步到Git
                    mainActivity.syncManager.autoPushNote("更新笔记", note)
                    Log.d("NoteEditActivity", "更新笔记成功: ${note.title}")
                } else {
                    Toast.makeText(this, "未找到要更新的笔记", Toast.LENGTH_SHORT).show()
                    Log.e("NoteEditActivity", "未找到UUID: $uuid")
                }
            } else {
                Toast.makeText(this, "无效的笔记参数", Toast.LENGTH_SHORT).show()
                Log.e("NoteEditActivity", "无效参数: uuid=$uuid")
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("NoteEditActivity", "保存笔记失败", e)
        }
    }

    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        // 如果在查看模式，直接返回
        if (!isEditMode) {
            super.onBackPressed()
            return
        }
        
        // 在编辑模式下，检查是否有未保存的更改
        val title = binding.noteTitleEditText.text.toString().trim()
        val content = binding.noteContentEditText.text.toString().trim()

        val hasChanges = if (isNewNote) {
            title.isNotEmpty() || content.isNotEmpty()
        } else if (uuid.isNotEmpty() && originalNote != null) {
            title != originalNote!!.title || content != originalNote!!.content
        } else {
            false
        }

        if (hasChanges) {
            android.app.AlertDialog.Builder(this)
                .setTitle("放弃更改？")
                .setMessage("您有未保存的更改，确定要放弃吗？")
                .setPositiveButton("放弃") { _, _ ->
                    // 如果是编辑模式且不是新建，返回查看模式
                    if (!isNewNote && uuid.isNotEmpty()) {
                        enterViewMode()
                    } else {
                        super.onBackPressed()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 如果没有更改，且不是新建笔记，返回查看模式
            if (!isNewNote && uuid.isNotEmpty()) {
                enterViewMode()
            } else {
                super.onBackPressed()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 禁用滚动监听
        isScrollListenerEnabled = false
    }
}