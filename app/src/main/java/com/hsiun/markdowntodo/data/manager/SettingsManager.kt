package com.hsiun.markdowntodo.data.manager

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log

class SettingsManager(private val context: Context) {

    companion object {
        private const val TAG = "SettingsManager"

        // 设置项键值
        private const val KEY_GITHUB_REPO_URL = "github_repo_url"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_SHOW_COMPLETED_TODOS = "show_completed"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_SORT_BY = "sort_by"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    // Git 设置
    var githubRepoUrl: String
        get() = sharedPreferences.getString(KEY_GITHUB_REPO_URL, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_GITHUB_REPO_URL, value).apply()

    var githubToken: String
        get() = sharedPreferences.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    // 显示设置
    var showCompletedTodos: Boolean
        get() = sharedPreferences.getBoolean(KEY_SHOW_COMPLETED_TODOS, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_SHOW_COMPLETED_TODOS, value).apply()

    // 同步设置
    var autoSyncEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_SYNC_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, value).apply()

    var syncIntervalMinutes: Int
        get() = sharedPreferences.getInt(KEY_SYNC_INTERVAL, 5)
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    var lastSyncTime: Long
        get() = sharedPreferences.getLong(KEY_LAST_SYNC_TIME, 0)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var isFirstLaunch: Boolean
        get() = sharedPreferences.getBoolean(KEY_IS_FIRST_LAUNCH, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_IS_FIRST_LAUNCH, value).apply()

    // 外观设置
    var themeColor: String
        get() = sharedPreferences.getString(KEY_THEME_COLOR, "#865EDC") ?: "#865EDC"
        set(value) = sharedPreferences.edit().putString(KEY_THEME_COLOR, value).apply()

    // 语言设置
    var language: String
        get() = sharedPreferences.getString(KEY_LANGUAGE, "auto") ?: "auto"
        set(value) = sharedPreferences.edit().putString(KEY_LANGUAGE, value).apply()

    // 通知设置
    var notificationEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_NOTIFICATION_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()

    var vibrationEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    // 排序设置
    var sortBy: String
        get() = sharedPreferences.getString(KEY_SORT_BY, "id") ?: "id"
        set(value) = sharedPreferences.edit().putString(KEY_SORT_BY, value).apply()

    // 设置变更监听器
    interface SettingsChangeListener {
        fun onGitSettingsChanged(repoUrl: String, token: String)
        fun onDisplaySettingsChanged(showCompleted: Boolean)
        fun onSyncSettingsChanged(autoSync: Boolean, interval: Int)
        fun onAppearanceSettingsChanged(themeColor: String)
        fun onNotificationSettingsChanged(enabled: Boolean, vibration: Boolean)
        fun onSortSettingsChanged(sortBy: String)
    }

    private val listeners = mutableListOf<SettingsChangeListener>()

    fun addSettingsChangeListener(listener: SettingsChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeSettingsChangeListener(listener: SettingsChangeListener) {
        listeners.remove(listener)
    }

    // 保存所有设置
    fun saveAllSettings(
        repoUrl: String,
        token: String,
        showCompleted: Boolean,
        autoSync: Boolean = true,
        syncInterval: Int = 5,
        themeColor: String = "#865EDC",
        notificationEnabled: Boolean = true,
        vibrationEnabled: Boolean = true,
        sortBy: String = "id"
    ) {
        Log.d(TAG, "保存所有设置")

        with(sharedPreferences.edit()) {
            putString(KEY_GITHUB_REPO_URL, repoUrl)
            putString(KEY_GITHUB_TOKEN, token)
            putBoolean(KEY_SHOW_COMPLETED_TODOS, showCompleted)
            putBoolean(KEY_AUTO_SYNC_ENABLED, autoSync)
            putInt(KEY_SYNC_INTERVAL, syncInterval)
            putString(KEY_THEME_COLOR, themeColor)
            putBoolean(KEY_NOTIFICATION_ENABLED, notificationEnabled)
            putBoolean(KEY_VIBRATION_ENABLED, vibrationEnabled)
            putString(KEY_SORT_BY, sortBy)
            apply()
        }

        // 通知监听器
        listeners.forEach { listener ->
            listener.onGitSettingsChanged(repoUrl, token)
            listener.onDisplaySettingsChanged(showCompleted)
            listener.onSyncSettingsChanged(autoSync, syncInterval)
            listener.onAppearanceSettingsChanged(themeColor)
            listener.onNotificationSettingsChanged(notificationEnabled, vibrationEnabled)
            listener.onSortSettingsChanged(sortBy)
        }
    }

    // 保存Git设置
    fun saveGitSettings(repoUrl: String, token: String) {
        Log.d(TAG, "保存Git设置: repoUrl=$repoUrl")

        with(sharedPreferences.edit()) {
            putString(KEY_GITHUB_REPO_URL, repoUrl)
            putString(KEY_GITHUB_TOKEN, token)
            apply()
        }

        listeners.forEach { it.onGitSettingsChanged(repoUrl, token) }
    }

    // 保存显示设置
    fun saveDisplaySettings(showCompleted: Boolean) {
        Log.d(TAG, "保存显示设置: showCompleted=$showCompleted")

        with(sharedPreferences.edit()) {
            putBoolean(KEY_SHOW_COMPLETED_TODOS, showCompleted)
            apply()
        }

        listeners.forEach { it.onDisplaySettingsChanged(showCompleted) }
    }

    // 保存同步设置
    fun saveSyncSettings(autoSync: Boolean, interval: Int) {
        Log.d(TAG, "保存同步设置: autoSync=$autoSync, interval=$interval")

        with(sharedPreferences.edit()) {
            putBoolean(KEY_AUTO_SYNC_ENABLED, autoSync)
            putInt(KEY_SYNC_INTERVAL, interval)
            apply()
        }

        listeners.forEach { it.onSyncSettingsChanged(autoSync, interval) }
    }

    // 检查Git配置是否完整
    fun isGitConfigured(): Boolean {
        return githubRepoUrl.isNotEmpty() && githubToken.isNotEmpty()
    }

    // 获取所有设置的摘要
    fun getSettingsSummary(): Map<String, String> {
        return mapOf(
            "Git仓库" to if (githubRepoUrl.isNotEmpty()) "已配置" else "未配置",
            "显示已完成" to if (showCompletedTodos) "显示" else "隐藏",
            "自动同步" to if (autoSyncEnabled) "开启(${syncIntervalMinutes}分钟)" else "关闭",
            "主题颜色" to themeColor,
            "通知" to if (notificationEnabled) "开启" else "关闭",
            "震动" to if (vibrationEnabled) "开启" else "关闭",
            "排序方式" to when (sortBy) {
                "id" -> "ID"
                "title" -> "标题"
                "created" -> "创建时间"
                else -> "ID"
            }
        )
    }

    // 重置所有设置为默认值
    fun resetToDefaults() {
        Log.d(TAG, "重置所有设置为默认值")

        with(sharedPreferences.edit()) {
            remove(KEY_GITHUB_REPO_URL)
            remove(KEY_GITHUB_TOKEN)
            putBoolean(KEY_SHOW_COMPLETED_TODOS, false)
            putBoolean(KEY_AUTO_SYNC_ENABLED, true)
            putInt(KEY_SYNC_INTERVAL, 5)
            putString(KEY_THEME_COLOR, "#865EDC")
            putBoolean(KEY_NOTIFICATION_ENABLED, true)
            putBoolean(KEY_VIBRATION_ENABLED, true)
            putString(KEY_SORT_BY, "id")
            apply()
        }

        // 通知监听器默认值
        listeners.forEach { listener ->
            listener.onGitSettingsChanged("", "")
            listener.onDisplaySettingsChanged(false)
            listener.onSyncSettingsChanged(true, 5)
            listener.onAppearanceSettingsChanged("#865EDC")
            listener.onNotificationSettingsChanged(true, true)
            listener.onSortSettingsChanged("id")
        }
    }

    // 导出设置到JSON
    fun exportSettings(): String {
        val settingsMap = mapOf(
            "githubRepoUrl" to githubRepoUrl,
            "showCompletedTodos" to showCompletedTodos,
            "autoSyncEnabled" to autoSyncEnabled,
            "syncIntervalMinutes" to syncIntervalMinutes,
            "themeColor" to themeColor,
            "notificationEnabled" to notificationEnabled,
            "vibrationEnabled" to vibrationEnabled,
            "sortBy" to sortBy
        )

        // 注意：出于安全考虑，不导出githubToken
        return settingsMap.toString()
    }

    // 导入设置（从JSON字符串）
    fun importSettings(jsonString: String): Boolean {
        // 这里简单处理，实际项目中应使用JSON解析库
        Log.d(TAG, "导入设置: $jsonString")
        // 实现JSON解析逻辑...
        return false
    }

    // 清理设置
    fun cleanup() {
        listeners.clear()
    }
}