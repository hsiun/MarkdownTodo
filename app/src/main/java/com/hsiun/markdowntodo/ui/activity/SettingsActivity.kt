package com.hsiun.markdowntodo.ui.activity

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hsiun.markdowntodo.data.manager.SettingsManager
import com.hsiun.markdowntodo.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化SettingsManager
        settingsManager = SettingsManager(this)

        // 设置工具栏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "设置"

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

        // 加载当前设置
        loadSettings()

        // 设置保存按钮点击事件
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        // 加载Git设置
        binding.githubRepoUrlInput.setText(settingsManager.githubRepoUrl)
        binding.githubTokenInput.setText(settingsManager.githubToken)

        // 加载待办设置
        binding.showCompletedCheckbox.isChecked = settingsManager.showCompletedTodos
    }

    private fun saveSettings() {
        val repoUrl = binding.githubRepoUrlInput.text.toString().trim()
        val token = binding.githubTokenInput.text.toString().trim()
        val showCompleted = binding.showCompletedCheckbox.isChecked

        // 验证输入
        if (repoUrl.isNotEmpty() && token.isEmpty()) {
            Toast.makeText(this, "请填写Git Token", Toast.LENGTH_SHORT).show()
            return
        }

        if (token.isNotEmpty() && repoUrl.isEmpty()) {
            Toast.makeText(this, "请填写Git仓库地址", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取MainActivity的SettingsManager实例（如果存在），以确保监听器被正确触发
        val mainActivity = MainActivity.getInstance()
        val settingsManagerToUse = if (mainActivity != null) {
            mainActivity.settingsManager
        } else {
            settingsManager
        }

        // 保存设置（使用MainActivity的SettingsManager实例，这样监听器会被触发）
        settingsManagerToUse.saveAllSettings(
            repoUrl,
            token,
            showCompleted,
            settingsManagerToUse.autoSyncEnabled,
            settingsManagerToUse.syncIntervalMinutes,
            settingsManagerToUse.themeColor,
            settingsManagerToUse.notificationEnabled,
            settingsManagerToUse.vibrationEnabled,
            settingsManagerToUse.sortBy
        )

        // 通知MainActivity更新Git设置（如果MainActivity存在）
        if (mainActivity != null && repoUrl.isNotEmpty() && token.isNotEmpty()) {
            mainActivity.syncManager.initGitManager(repoUrl, token)
        }

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
            }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}