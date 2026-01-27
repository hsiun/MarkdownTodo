package com.hsiun.markdowntodo

import android.os.Bundle
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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