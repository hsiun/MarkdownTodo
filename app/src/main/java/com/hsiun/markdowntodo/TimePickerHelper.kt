package com.hsiun.markdowntodo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.util.Calendar

class TimePickerHelper(private val context: Context) {

    interface TimePickerCallback {
        fun onTimeSelected(timeInMillis: Long)
        fun onCancelled()
    }

    fun showDateTimePicker(callback: TimePickerCallback) {
        val calendar = Calendar.getInstance()

        // 先选择日期
        val datePicker = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                // 日期选择后，选择时间
                val timePicker = TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        calendar.set(year, month, dayOfMonth, hourOfDay, minute, 0)

                        // 如果选择的时间已经过去，设置为明天同一时间
                        val now = Calendar.getInstance()
                        if (calendar.timeInMillis <= now.timeInMillis) {
                            calendar.add(Calendar.DAY_OF_MONTH, 1)
                        }

                        callback.onTimeSelected(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                )

                timePicker.setTitle("选择提醒时间")
                timePicker.setButton(DatePickerDialog.BUTTON_POSITIVE, "确定", timePicker)
                timePicker.setButton(DatePickerDialog.BUTTON_NEGATIVE, "取消") { dialog, _ ->
                    dialog.dismiss()
                    callback.onCancelled()
                }
                timePicker.show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePicker.setTitle("选择提醒日期")
        datePicker.datePicker.minDate = System.currentTimeMillis() - 1000 // 不能选择过去的时间
        datePicker.show()
    }

    fun formatDateTime(timeInMillis: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timeInMillis

        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance()
        tomorrow.add(Calendar.DAY_OF_MONTH, 1)

        return when {
            calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    calendar.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                    calendar.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH) -> {
                "今天 ${String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))}"
            }
            calendar.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) &&
                    calendar.get(Calendar.MONTH) == tomorrow.get(Calendar.MONTH) &&
                    calendar.get(Calendar.DAY_OF_MONTH) == tomorrow.get(Calendar.DAY_OF_MONTH) -> {
                "明天 ${String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))}"
            }
            else -> {
                String.format("%d-%02d-%02d %02d:%02d",
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE))
            }
        }
    }
}