package com.hsiun.markdowntodo

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hsiun.markdowntodo.databinding.ActivityNoteEditBinding

class NoteEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditBinding
    private var noteId: Int = -1
    private var uuid: String = ""
    private var isNewNote: Boolean = false
    private lateinit var mainActivity: MainActivity

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

        // 设置ActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // 获取传递的数据
        noteId = intent.getIntExtra("noteId", -1)
        uuid = intent.getStringExtra("uuid").toString()
        isNewNote = intent.getBooleanExtra("isNewNote", false)

        Log.d("NoteEditActivity", "接收参数: noteId=$noteId, isNewNote=$isNewNote")

        if (isNewNote) {
            // 新建笔记
            supportActionBar?.title = "新建笔记"
            binding.toolbar.title = "新建笔记"
        } else if (uuid.isNotEmpty()) {
            // 编辑现有笔记
            val note = mainActivity.noteManager.getAllNotes().find { it.uuid == uuid }
            if (note != null) {
                supportActionBar?.title = "编辑笔记"
                binding.toolbar.title = "编辑笔记"
                binding.noteTitleEditText.setText(note.title)
                binding.noteContentEditText.setText(note.content)
                Log.d("NoteEditActivity", "加载笔记: ${note.title}")
            } else {
                Toast.makeText(this, "未找到笔记", Toast.LENGTH_SHORT).show()
                Log.e("NoteEditActivity", "未找到笔记ID: $noteId")
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
            } else if (noteId != -1) {
                // 更新现有笔记
                val note = mainActivity.noteManager.updateNote(noteId, title, content)
                Toast.makeText(this, "已更新笔记: $title", Toast.LENGTH_SHORT).show()
                // 同步到Git
                mainActivity.syncManager.autoPushNote("更新笔记", note)
                Log.d("NoteEditActivity", "更新笔记成功: ${note.title}")
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("NoteEditActivity", "保存笔记失败", e)
        }
    }

    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        // 检查是否有未保存的更改
        val title = binding.noteTitleEditText.text.toString().trim()
        val content = binding.noteContentEditText.text.toString().trim()

        val hasChanges = if (isNewNote) {
            title.isNotEmpty() || content.isNotEmpty()
        } else if (noteId != -1) {
            val originalNote = mainActivity.noteManager.getAllNotes().find { it.id == noteId }
            originalNote?.let {
                title != it.title || content != it.content
            } ?: false
        } else {
            false
        }

        if (hasChanges) {
            android.app.AlertDialog.Builder(this)
                .setTitle("放弃更改？")
                .setMessage("您有未保存的更改，确定要放弃吗？")
                .setPositiveButton("放弃") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}