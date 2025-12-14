package com.hsiun.markdowntodo

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.*


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