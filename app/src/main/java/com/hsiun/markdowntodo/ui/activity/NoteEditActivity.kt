package com.hsiun.markdowntodo.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hsiun.markdowntodo.data.model.NoteItem
import com.hsiun.markdowntodo.R
import com.hsiun.markdowntodo.databinding.ActivityNoteEditBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.abs

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

    // Markdown 渲染器
    private lateinit var markwon: Markwon

    // 用于撤销的原始内容
    private var originalTitle: String = ""
    private var originalContent: String = ""

    // 图片相关
    private var currentPhotoUri: Uri? = null
    // Git 仓库中的图片目录
    private val gitImagesDir: File by lazy {
        val repoDir = File(filesDir, "git_repo")
        val dir = File(repoDir, "images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageUri(it) }
    }

    // 相机拍照
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            currentPhotoUri?.let { handleImageUri(it) }
        }
    }

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            showImageSourceDialog()
        } else {
            Toast.makeText(this, "需要权限才能添加图片", Toast.LENGTH_SHORT).show()
        }
    }

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
        binding.toolbar.setBackgroundColor(Color.WHITE)
        binding.toolbar.setTitleTextColor(Color.BLACK)
        binding.toolbar.elevation = 0f

        // 设置状态栏颜色为白色
        window.statusBarColor = Color.WHITE

        // 设置状态栏图标为深色（适配白色背景）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }

        // Android 11+ 使用新的API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        }

        // 处理系统窗口插入，避免内容与状态栏重叠
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 为工具栏添加状态栏高度的 padding，避免内容与状态栏重叠
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                systemBars.top,
                binding.toolbar.paddingRight,
                binding.toolbar.paddingBottom
            )
            insets
        }

        // 获取传递的数据
        uuid = intent.getStringExtra("uuid") ?: ""
        isNewNote = intent.getBooleanExtra("isNewNote", false)

        Log.d("NoteEditActivity", "接收参数: uuid=$uuid, isNewNote=$isNewNote")

        // 初始化 Markdown 渲染器（必须在 enterViewMode 之前）
        initMarkwon()

        // 设置 Markdown 快捷按钮
        setupMarkdownButtons()

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
    }

    /**
     * 初始化 Markdown 渲染器
     */
    private fun initMarkwon() {
        try {
            val repoDir = File(filesDir, "git_repo")

            markwon = Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())  // 删除线支持
                .usePlugin(TablePlugin.create(this))       // 表格支持
                .usePlugin(TaskListPlugin.create(this))   // 任务列表支持
                .usePlugin(LinkifyPlugin.create())        // 链接自动识别
                .usePlugin(ImagesPlugin.create(object : ImagesPlugin.ImagesConfigure {
                    override fun configureImages(plugin: ImagesPlugin) {
                        // 添加自定义的 SchemeHandler 来处理 file:// 路径
                        // 相对路径已经在预处理阶段转换为 file:// 路径
                        plugin.addSchemeHandler(object : SchemeHandler() {
                            override fun handle(raw: String, uri: Uri): ImageItem {
                                Log.d("NoteEditActivity", "SchemeHandler 被调用: raw=$raw, uri=$uri")
                                return try {
                                    // 移除 file:// 前缀
                                    val filePath = if (raw.startsWith("file://")) {
                                        raw.substring(7)
                                    } else {
                                        raw
                                    }
                                    val file = File(filePath)

                                    Log.d("NoteEditActivity", "尝试加载图片: ${file.absolutePath}, 存在: ${file.exists()}")

                                    if (file.exists() && file.canRead()) {
                                        val inputStream = FileInputStream(file)
                                        // 根据文件扩展名确定 content-type
                                        val contentType = when (file.extension.lowercase()) {
                                            "jpg", "jpeg" -> "image/jpeg"
                                            "png" -> "image/png"
                                            "gif" -> "image/gif"
                                            "webp" -> "image/webp"
                                            else -> "image/jpeg"
                                        }
                                        ImageItem.withDecodingNeeded(contentType, inputStream)
                                    } else {
                                        Log.e("NoteEditActivity", "图片文件不存在或无法读取: ${file.absolutePath}")
                                        throw FileNotFoundException("图片文件不存在: ${file.absolutePath}")
                                    }
                                } catch (e: Exception) {
                                    Log.e("NoteEditActivity", "加载图片失败: $raw", e)
                                    throw e
                                }
                            }

                            override fun supportedSchemes(): Collection<String> {
                                return listOf("file")
                            }
                        })
                    }
                }))
            .build()
            Log.d("NoteEditActivity", "Markwon 初始化成功")
        } catch (e: Exception) {
            Log.e("NoteEditActivity", "Markwon 初始化失败", e)
            // 如果初始化失败，创建一个基本的 Markwon 实例
            markwon = Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(TaskListPlugin.create(this))
                .usePlugin(LinkifyPlugin.create())
                .build()
            Log.d("NoteEditActivity", "使用基本 Markwon 配置")
        }
    }

    /**
     * 预处理 Markdown 内容，将相对路径转换为 file:// 绝对路径
     */
    private fun preprocessImagePaths(content: String): String {
        val repoDir = File(filesDir, "git_repo")
        // 匹配 Markdown 图片语法: ![alt](path)
        val pattern = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

        return pattern.replace(content) { matchResult ->
            val altText = matchResult.groupValues[1]
            val imagePath = matchResult.groupValues[2]

            // 如果已经是 file:// 路径，直接返回
            if (imagePath.startsWith("file://")) {
                matchResult.value
            } else if (!imagePath.contains("://")) {
                // 相对路径，转换为 file:// 绝对路径
                val imageFile = File(repoDir, imagePath)
                val absolutePath = "file://${imageFile.absolutePath}"
                Log.d("NoteEditActivity", "预处理图片路径: $imagePath -> $absolutePath")
                "![$altText]($absolutePath)"
            } else {
                // 其他协议（http/https），保持不变
                matchResult.value
            }
        }
    }

    /**
     * 进入查看模式
     */
    private fun enterViewMode() {
        // 确保 markwon 已初始化
        if (!::markwon.isInitialized) {
            Log.e("NoteEditActivity", "markwon 未初始化，尝试重新初始化")
            initMarkwon()
        }

        isEditMode = false
        isScrollListenerEnabled = true  // 确保启用滚动监听

        // 确保工具栏始终显示且位置正确
        isToolbarVisible = true
        binding.toolbar.translationY = 0f

        originalNote?.let { note ->
            // 显示查看模式的视图
            binding.noteTitleTextView.visibility = View.VISIBLE
            binding.noteTitleTextView.text = note.title

            // 显示时间信息
            binding.noteTimeTextView.visibility = View.VISIBLE
            binding.noteTimeTextView.text = "创建时间: ${note.createdAt} | 修改时间: ${note.updatedAt}"

            binding.noteContentScrollView.visibility = View.VISIBLE
            // 移除边框（查看模式下不显示边框）
            binding.noteContentScrollView.background = null
            // 预处理 Markdown 内容：将相对路径转换为 file:// 绝对路径
            val processedContent = preprocessImagePaths(note.content)
            // 使用 Markwon 渲染 Markdown 内容
            try {
                markwon.setMarkdown(binding.noteContentTextView, processedContent)
            } catch (e: Exception) {
                Log.e("NoteEditActivity", "渲染 Markdown 失败", e)
                binding.noteContentTextView.text = note.content
            }

            // 隐藏编辑模式的视图
            binding.noteEditScrollView.visibility = View.GONE
            binding.noteTimeEditTextView.visibility = View.GONE
            binding.markdownButtonsContainer.visibility = View.GONE
            binding.hintTextView.visibility = View.GONE

            // 隐藏工具栏菜单（查看模式下不显示）
            invalidateOptionsMenu()

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
                        val deltaY = abs(event.y - touchStartY)
                        // 提高阈值到30像素，只有明显滑动才认为是滚动
                        if (deltaY > 30) {
                            isScrolling = true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 如果移动距离小于30像素，且滚动位置没有明显变化，认为是点击
                        val deltaY = abs(event.y - touchStartY)
                        val scrollDelta = abs(binding.noteContentScrollView.scrollY - lastScrollY)

                        if (deltaY <= 30 && scrollDelta <= 10) {
                            // 明显的点击操作，进入编辑模式
                            enterEditMode()
                        }
                        // 重置状态
                        isScrolling = false
                    }
                }
                false // 返回false，让ScrollView继续处理滑动事件
            }

            // 标题、时间和内容都可以点击进入编辑模式
            binding.noteTitleTextView.setOnClickListener {
                enterEditMode()
            }

            binding.noteTimeTextView.setOnClickListener {
                enterEditMode()
            }

            binding.noteContentTextView.setOnClickListener {
                enterEditMode()
            }

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
        binding.noteContentScrollView.visibility = View.GONE
        binding.noteTimeTextView.visibility = View.GONE

        // 显示编辑模式的视图
        binding.noteEditScrollView.visibility = View.VISIBLE
        binding.markdownButtonsContainer.visibility = View.VISIBLE
        binding.hintTextView.visibility = View.VISIBLE

        // 填充数据并保存原始内容用于撤销
        originalNote?.let { note ->
            originalTitle = note.title
            originalContent = note.content
            binding.noteTitleEditText.setText(note.title)
            binding.noteContentEditText.setText(note.content)

            // 显示时间信息
            binding.noteTimeEditTextView.visibility = View.VISIBLE
            binding.noteTimeEditTextView.text = "创建时间: ${note.createdAt} | 修改时间: ${note.updatedAt}"
        } ?: run {
            // 新建笔记时，原始内容为空
            originalTitle = ""
            originalContent = ""

            // 新建笔记时不显示时间
            binding.noteTimeEditTextView.visibility = View.GONE
        }

        // 显示工具栏菜单（编辑模式下显示）
        invalidateOptionsMenu()

        supportActionBar?.title = if (isNewNote) "新建笔记" else "编辑笔记"
        binding.toolbar.title = if (isNewNote) "新建笔记" else "编辑笔记"

        // 移除内容区域的触摸事件（编辑模式下不需要）
        binding.noteContentScrollView.setOnTouchListener(null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_note_edit, menu)
        // 只在编辑模式下显示菜单
        menu.findItem(R.id.action_spacer)?.isVisible = isEditMode
        menu.findItem(R.id.action_undo)?.isVisible = isEditMode
        menu.findItem(R.id.action_save)?.isVisible = isEditMode

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> {
                undoChanges()
                true
            }
            R.id.action_save -> {
                saveNote()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 撤销修改
     */
    private fun undoChanges() {
        binding.noteTitleEditText.setText(originalTitle)
        binding.noteContentEditText.setText(originalContent)
        Toast.makeText(this, "已撤销修改", Toast.LENGTH_SHORT).show()
    }

    /**
     * 设置 Markdown 快捷按钮
     */
    private fun setupMarkdownButtons() {
        // 粗体
        binding.btnBold.setOnClickListener {
            insertMarkdownTag("**", "**")
        }

        // 斜体
        binding.btnItalic.setOnClickListener {
            insertMarkdownTag("*", "*")
        }

        // 代码
        binding.btnCode.setOnClickListener {
            insertMarkdownTag("`", "`")
        }

        // 链接
        binding.btnLink.setOnClickListener {
            insertMarkdownTag("[", "]()")
        }

        // 列表
        binding.btnList.setOnClickListener {
            insertMarkdownTag("- ", "")
        }

        // 图片
        binding.btnImage.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    /**
     * 检查并请求权限
     */
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 READ_MEDIA_IMAGES
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // Android 12 及以下需要 READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isEmpty()) {
            showImageSourceDialog()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * 显示图片来源选择对话框
     */
    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("选择图片")
            .setItems(arrayOf("从相册选择", "拍照")) { _, which ->
                when (which) {
                    0 -> imagePickerLauncher.launch("image/*")
                    1 -> takePhoto()
                }
            }
            .show()
    }

    /**
     * 拍照
     */
    private fun takePhoto() {
        val photoFile = File(gitImagesDir, "temp_photo_${System.currentTimeMillis()}.jpg")
        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        currentPhotoUri = photoUri
        cameraLauncher.launch(photoUri)
    }

    /**
     * 处理图片URI
     */
    private fun handleImageUri(uri: Uri) {
        try {
            // 复制图片到 Git 仓库的 images 目录
            val imageFile = saveImageToLocal(uri)
            if (imageFile != null) {
                // 生成相对路径（相对于 Git 仓库根目录）
                val imagePath = "images/${imageFile.name}"
                // 插入Markdown图片语法（使用相对路径）
                insertImageMarkdown(imagePath)
                Toast.makeText(this, "图片已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "保存图片失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("NoteEditActivity", "处理图片失败", e)
            Toast.makeText(this, "处理图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存图片到 Git 仓库的 images 目录
     */
    private fun saveImageToLocal(uri: Uri): File? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                // 压缩图片
                val compressedBitmap = compressBitmap(bitmap)
                val imageFile = File(gitImagesDir, "image_${System.currentTimeMillis()}.jpg")
                val outputStream = FileOutputStream(imageFile)
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                outputStream.flush()
                outputStream.close()
                imageFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("NoteEditActivity", "保存图片失败", e)
            null
        }
    }

    /**
     * 压缩图片
     */
    private fun compressBitmap(bitmap: Bitmap): Bitmap {
        val maxWidth = 1920
        val maxHeight = 1920

        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 插入图片Markdown语法
     */
    private fun insertImageMarkdown(imagePath: String) {
        val editText = binding.noteContentEditText
        val start = editText.selectionStart
        val text = editText.text.toString()
        // 使用相对路径，这样在不同设备上都能正确显示
        val imageMarkdown = "![图片]($imagePath)\n"
        val newText = text.substring(0, start) + imageMarkdown + text.substring(start)
        editText.setText(newText)
        editText.setSelection(start + imageMarkdown.length)
    }

    /**
     * 在光标位置插入 Markdown 标签
     */
    private fun insertMarkdownTag(before: String, after: String) {
        val editText = binding.noteContentEditText
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val text = editText.text.toString()

        if (start == end) {
            // 没有选中文本，在光标位置插入标签
            val newText = text.substring(0, start) + before + after + text.substring(start)
            editText.setText(newText)
            // 将光标移动到标签中间
            editText.setSelection(start + before.length)
        } else {
            // 有选中文本，用标签包裹选中的文本
            val selectedText = text.substring(start, end)
            val newText = text.substring(0, start) + before + selectedText + after + text.substring(end)
            editText.setText(newText)
            // 选中被包裹的文本
            editText.setSelection(start + before.length, start + before.length + selectedText.length)
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
            AlertDialog.Builder(this)
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