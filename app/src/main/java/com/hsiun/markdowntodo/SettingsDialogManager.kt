package com.hsiun.markdowntodo

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.google.android.material.tabs.TabLayout
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.PagerAdapter

class SettingsDialogManager(private val context: Context) {

    interface SettingsDialogListener {
        fun onSaveSettings(
            repoUrl: String,
            token: String,
            showCompleted: Boolean,
            autoSync: Boolean,
            syncInterval: Int,
            themeColor: String,
            notificationEnabled: Boolean,
            vibrationEnabled: Boolean,
            sortBy: String
        )

        fun onCancel()
        fun onResetSettings()
    }

    private var settingsDialogListener: SettingsDialogListener? = null

    fun setSettingsDialogListener(listener: SettingsDialogListener) {
        this.settingsDialogListener = listener
    }

    fun showSettingsDialog(settingsManager: SettingsManager) {
        // 创建自定义的适配器
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings_advanced, null)

        // 获取 ViewPager 和 TabLayout
        val viewPager = dialogView.findViewById<ViewPager>(R.id.settingsViewPager)
        val tabLayout = dialogView.findViewById<TabLayout>(R.id.settingsTabLayout)

        // 创建页面列表
        val pages = listOf(
            createSyncPage(settingsManager),
            createDisplayPage(settingsManager),
            createNotificationPage(settingsManager),
            createAppearancePage(settingsManager)
        )

        val tabTitles = listOf("同步", "显示", "通知", "外观")

        // 设置适配器
        val adapter = SettingsPagerAdapter(pages, tabTitles)
        viewPager.adapter = adapter
        tabLayout.setupWithViewPager(viewPager)

        // 创建对话框
        val dialog = AlertDialog.Builder(context)
            .setTitle("应用设置")
            .setView(dialogView)
            .setPositiveButton("保存") { dialog, which ->
                saveAllSettingsFromPages(pages, settingsManager)
            }
            .setNegativeButton("取消") { dialog, which ->
                settingsDialogListener?.onCancel()
            }
            .setNeutralButton("重置") { dialog, which ->
                showResetConfirmationDialog(settingsManager)
            }
            .create()

        dialog.show()
    }

    private fun createSyncPage(settingsManager: SettingsManager): View {
        val view = LayoutInflater.from(context).inflate(R.layout.page_settings_sync, null)

        val repoUrlInput = view.findViewById<EditText>(R.id.githubRepoUrlInput)
        val tokenInput = view.findViewById<EditText>(R.id.githubTokenInput)
        val autoSyncSwitch = view.findViewById<Switch>(R.id.autoSyncSwitch)
        val syncIntervalSeekBar = view.findViewById<SeekBar>(R.id.syncIntervalSeekBar)
        val syncIntervalText = view.findViewById<TextView>(R.id.syncIntervalText)

        // 填充现有设置
        repoUrlInput.setText(settingsManager.githubRepoUrl)
        tokenInput.setText(settingsManager.githubToken)
        autoSyncSwitch.isChecked = settingsManager.autoSyncEnabled

        // 设置SeekBar的范围（1-60分钟）
        syncIntervalSeekBar.max = 59 // 0-59对应1-60分钟
        syncIntervalSeekBar.progress = settingsManager.syncIntervalMinutes - 1
        syncIntervalText.text = "${settingsManager.syncIntervalMinutes} 分钟"

        // SeekBar监听器
        syncIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                syncIntervalText.text = "${progress + 1} 分钟"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 自动同步开关监听器
        autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            syncIntervalSeekBar.isEnabled = isChecked
            syncIntervalText.alpha = if (isChecked) 1.0f else 0.5f
        }

        // 初始化SeekBar状态
        syncIntervalSeekBar.isEnabled = autoSyncSwitch.isChecked
        syncIntervalText.alpha = if (autoSyncSwitch.isChecked) 1.0f else 0.5f

        return view
    }

    private fun createDisplayPage(settingsManager: SettingsManager): View {
        val view = LayoutInflater.from(context).inflate(R.layout.page_settings_display, null)

        val showCompletedCheckbox = view.findViewById<CheckBox>(R.id.showCompletedCheckbox)
        val sortByRadioGroup = view.findViewById<RadioGroup>(R.id.sortByRadioGroup)

        showCompletedCheckbox.isChecked = settingsManager.showCompletedTodos

        // 设置排序选项
        when (settingsManager.sortBy) {
            "id" -> view.findViewById<RadioButton>(R.id.sortByIdRadio).isChecked = true
            "title" -> view.findViewById<RadioButton>(R.id.sortByTitleRadio).isChecked = true
            "created" -> view.findViewById<RadioButton>(R.id.sortByCreatedRadio).isChecked = true
        }

        return view
    }

    private fun createNotificationPage(settingsManager: SettingsManager): View {
        val view = LayoutInflater.from(context).inflate(R.layout.page_settings_notification, null)

        val notificationSwitch = view.findViewById<Switch>(R.id.notificationSwitch)
        val vibrationSwitch = view.findViewById<Switch>(R.id.vibrationSwitch)

        notificationSwitch.isChecked = settingsManager.notificationEnabled
        vibrationSwitch.isChecked = settingsManager.vibrationEnabled

        // 通知开关监听器
        notificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            vibrationSwitch.isEnabled = isChecked
        }

        // 初始化震动开关状态
        vibrationSwitch.isEnabled = notificationSwitch.isChecked

        return view
    }

    private fun createAppearancePage(settingsManager: SettingsManager): View {
        val view = LayoutInflater.from(context).inflate(R.layout.page_settings_appearance, null)

        val themeColorSpinner = view.findViewById<Spinner>(R.id.themeColorSpinner)
        val themeColorPreview = view.findViewById<View>(R.id.themeColorPreview)

        // 设置主题颜色选项
        val themeColors = arrayOf(
            "#865EDC",  // 紫色
            "#1A73E8",  // 蓝色
            "#4CAF50",  // 绿色
            "#FF9800",  // 橙色
            "#F44336"   // 红色
        )

        val colorNames = arrayOf("紫色", "蓝色", "绿色", "橙色", "红色")

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, colorNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        themeColorSpinner.adapter = adapter

        // 设置当前选中的颜色
        val currentColorIndex = themeColors.indexOf(settingsManager.themeColor)
        if (currentColorIndex != -1) {
            themeColorSpinner.setSelection(currentColorIndex)
        }

        // 更新颜色预览
        themeColorPreview.setBackgroundColor(Color.parseColor(settingsManager.themeColor))

        // 颜色选择监听器
        themeColorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedColor = themeColors[position]
                themeColorPreview.setBackgroundColor(Color.parseColor(selectedColor))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        return view
    }

    private fun saveAllSettingsFromPages(pages: List<View>, settingsManager: SettingsManager) {
        // 从各个页面收集设置

        // 同步页面
        val syncPage = pages[0]
        val repoUrlInput = syncPage.findViewById<EditText>(R.id.githubRepoUrlInput)
        val tokenInput = syncPage.findViewById<EditText>(R.id.githubTokenInput)
        val autoSyncSwitch = syncPage.findViewById<Switch>(R.id.autoSyncSwitch)
        val syncIntervalSeekBar = syncPage.findViewById<SeekBar>(R.id.syncIntervalSeekBar)

        // 显示页面
        val displayPage = pages[1]
        val showCompletedCheckbox = displayPage.findViewById<CheckBox>(R.id.showCompletedCheckbox)
        val sortByRadioGroup = displayPage.findViewById<RadioGroup>(R.id.sortByRadioGroup)

        // 通知页面
        val notificationPage = pages[2]
        val notificationSwitch = notificationPage.findViewById<Switch>(R.id.notificationSwitch)
        val vibrationSwitch = notificationPage.findViewById<Switch>(R.id.vibrationSwitch)

        // 外观页面
        val appearancePage = pages[3]
        val themeColorSpinner = appearancePage.findViewById<Spinner>(R.id.themeColorSpinner)
        val themeColors = arrayOf("#865EDC", "#1A73E8", "#4CAF50", "#FF9800", "#F44336")

        // 获取设置值
        val repoUrl = repoUrlInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        val showCompleted = showCompletedCheckbox.isChecked
        val autoSync = autoSyncSwitch.isChecked
        val syncInterval = syncIntervalSeekBar.progress + 1
        val themeColor = themeColors[themeColorSpinner.selectedItemPosition]
        val notificationEnabled = notificationSwitch.isChecked
        val vibrationEnabled = vibrationSwitch.isChecked

        // 获取排序方式
        val sortBy = when (sortByRadioGroup.checkedRadioButtonId) {
            R.id.sortByIdRadio -> "id"
            R.id.sortByTitleRadio -> "title"
            R.id.sortByCreatedRadio -> "created"
            else -> "id"
        }

        // 验证Git配置
        if (repoUrl.isNotEmpty() && token.isEmpty()) {
            Toast.makeText(context, "请填写Git Token", Toast.LENGTH_SHORT).show()
            return
        }

        if (token.isNotEmpty() && repoUrl.isEmpty()) {
            Toast.makeText(context, "请填写Git仓库地址", Toast.LENGTH_SHORT).show()
            return
        }

        settingsDialogListener?.onSaveSettings(
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
    }

    private fun showResetConfirmationDialog(settingsManager: SettingsManager) {
        AlertDialog.Builder(context)
            .setTitle("重置设置")
            .setMessage("确定要重置所有设置为默认值吗？此操作不可撤销。")
            .setPositiveButton("重置") { dialog, which ->
                settingsDialogListener?.onResetSettings()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ViewPager 适配器
    private inner class SettingsPagerAdapter(
        private val pages: List<View>,
        private val titles: List<String>
    ) : PagerAdapter() {

        override fun getCount(): Int = pages.size

        override fun isViewFromObject(view: View, obj: Any): Boolean = view == obj

        override fun instantiateItem(container: android.view.ViewGroup, position: Int): Any {
            val view = pages[position]
            container.addView(view)
            return view
        }

        override fun destroyItem(container: android.view.ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return titles[position]
        }
    }

    // 简单的设置对话框（兼容旧版本）
    fun showSimpleSettingsDialog(settingsManager: SettingsManager) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

        val repoUrlInput = dialogView.findViewById<EditText>(R.id.githubRepoUrlInput)
        val tokenInput = dialogView.findViewById<EditText>(R.id.githubTokenInput)
        val showCompletedCheckbox = dialogView.findViewById<CheckBox>(R.id.showCompletedCheckbox)

        repoUrlInput.setText(settingsManager.githubRepoUrl)
        tokenInput.setText(settingsManager.githubToken)
        showCompletedCheckbox.isChecked = settingsManager.showCompletedTodos

        val dialog = AlertDialog.Builder(context)
            .setTitle("设置")
            .setView(dialogView)
            .setPositiveButton("保存") { dialog, which ->
                val repoUrl = repoUrlInput.text.toString().trim()
                val token = tokenInput.text.toString().trim()
                val showCompleted = showCompletedCheckbox.isChecked

                if (repoUrl.isNotEmpty() && token.isEmpty()) {
                    Toast.makeText(context, "请填写Git Token", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (token.isNotEmpty() && repoUrl.isEmpty()) {
                    Toast.makeText(context, "请填写Git仓库地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                settingsDialogListener?.onSaveSettings(
                    repoUrl,
                    token,
                    showCompleted,
                    settingsManager.autoSyncEnabled,
                    settingsManager.syncIntervalMinutes,
                    settingsManager.themeColor,
                    settingsManager.notificationEnabled,
                    settingsManager.vibrationEnabled,
                    settingsManager.sortBy
                )
            }
            .setNegativeButton("取消") { dialog, which ->
                settingsDialogListener?.onCancel()
            }
            .create()

        dialog.show()
    }
}