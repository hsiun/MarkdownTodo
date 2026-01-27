package com.hsiun.markdowntodo.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.hsiun.markdowntodo.R
import java.util.Calendar

class OneRowTimePickerDialog : DialogFragment() {

    interface TimePickerListener {
        fun onTimeSelected(timeInMillis: Long)
        fun onCancelled()
    }

    companion object {
        private const val TAG = "OneRowTimePickerDialog"

        fun newInstance(
            initialTime: Long = System.currentTimeMillis(),
            minTime: Long = System.currentTimeMillis()
        ): OneRowTimePickerDialog {
            val args = Bundle().apply {
                putLong("initialTime", initialTime)
                putLong("minTime", minTime)
            }
            return OneRowTimePickerDialog().apply {
                arguments = args
            }
        }
    }

    var listener: TimePickerListener? = null
    private lateinit var selectedDateTimeText: TextView
    private lateinit var yearPicker: NumberPicker
    private lateinit var monthPicker: NumberPicker
    private lateinit var dayPicker: NumberPicker
    private lateinit var hourPicker: NumberPicker
    private lateinit var minutePicker: NumberPicker
    private lateinit var secondPicker: NumberPicker

    private val calendar = Calendar.getInstance()
    private var initialTime: Long = 0
    private var minTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            initialTime = it.getLong("initialTime", System.currentTimeMillis())
            minTime = it.getLong("minTime", System.currentTimeMillis())
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is TimePickerListener) {
            listener = parentFragment as TimePickerListener
        } else if (context is TimePickerListener) {
            listener = context
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_one_row_time_picker, null)

        // 初始化视图
        initViews(view)

        // 设置初始时间
        calendar.timeInMillis = initialTime
        updateDateTimePickers()

        // 创建对话框
        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()
    }

    private fun initViews(view: View) {
        selectedDateTimeText = view.findViewById(R.id.selectedDateTimeText)

        yearPicker = view.findViewById(R.id.yearPicker)
        monthPicker = view.findViewById(R.id.monthPicker)
        dayPicker = view.findViewById(R.id.dayPicker)
        hourPicker = view.findViewById(R.id.hourPicker)
        minutePicker = view.findViewById(R.id.minutePicker)
        secondPicker = view.findViewById(R.id.secondPicker)

        // 获取当前年份
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        // 设置年份（当前年和未来10年）
        yearPicker.minValue = currentYear
        yearPicker.maxValue = currentYear + 10
        yearPicker.wrapSelectorWheel = false
        yearPicker.setFormatter { String.format("%04d", it) }

        // 设置月份（1-12）
        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.wrapSelectorWheel = false
        monthPicker.setFormatter { String.format("%02d", it) }

        // 设置小时（0-23）
        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        hourPicker.wrapSelectorWheel = false
        hourPicker.setFormatter { String.format("%02d", it) }

        // 设置分钟（0-59）
        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.wrapSelectorWheel = false
        minutePicker.setFormatter { String.format("%02d", it) }

        // 设置秒钟（0-59）
        secondPicker.minValue = 0
        secondPicker.maxValue = 59
        secondPicker.wrapSelectorWheel = false
        secondPicker.setFormatter { String.format("%02d", it) }

        // 设置选择器监听
        val valueChangeListener = NumberPicker.OnValueChangeListener { _, _, _ ->
            updateDayPicker()
            updateDisplay()
        }

        yearPicker.setOnValueChangedListener(valueChangeListener)
        monthPicker.setOnValueChangedListener(valueChangeListener)
        dayPicker.setOnValueChangedListener(valueChangeListener)
        hourPicker.setOnValueChangedListener { _, _, _ -> updateDisplay() }
        minutePicker.setOnValueChangedListener { _, _, _ -> updateDisplay() }
        secondPicker.setOnValueChangedListener { _, _, _ -> updateDisplay() }

        // 快速选项按钮
        view.findViewById<Button>(R.id.btn30min).setOnClickListener {
            setQuickTime(minutesToAdd = 30)
        }

        view.findViewById<Button>(R.id.btn1hour).setOnClickListener {
            setQuickTime(hoursToAdd = 1)
        }

        view.findViewById<Button>(R.id.btnTomorrow).setOnClickListener {
            setQuickTime(daysToAdd = 1)
        }

        // 确定和取消按钮
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            listener?.onCancelled()
            dismiss()
        }

        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            updateCalendarFromPickers()
            listener?.onTimeSelected(calendar.timeInMillis)
            dismiss()
        }
    }

    private fun updateDayPicker() {
        val year = yearPicker.value
        val month = monthPicker.value

        // 计算该月的天数
        val dayCalendar = Calendar.getInstance()
        dayCalendar.set(Calendar.YEAR, year)
        dayCalendar.set(Calendar.MONTH, month - 1) // Calendar.MONTH 是 0-based
        dayCalendar.set(Calendar.DAY_OF_MONTH, 1)
        val maxDay = dayCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        dayPicker.minValue = 1
        dayPicker.maxValue = maxDay
        dayPicker.wrapSelectorWheel = false

        // 设置日期的格式化器
        dayPicker.setFormatter { String.format("%02d", it) }

        // 如果当前day值大于最大天数，调整为最大天数
        if (dayPicker.value > maxDay) {
            dayPicker.value = maxDay
        }
    }

    private fun updateDisplay() {
        val year = yearPicker.value
        val month = monthPicker.value
        val day = dayPicker.value
        val hour = hourPicker.value
        val minute = minutePicker.value
        val second = secondPicker.value

        // 更新日期时间显示
        val dateText = String.format("%04d-%02d-%02d", year, month, day)
        val timeText = String.format("%02d:%02d:%02d", hour, minute, second)
        selectedDateTimeText.text = "$dateText $timeText"
    }

    private fun updateCalendarFromPickers() {
        calendar.set(
            yearPicker.value,
            monthPicker.value - 1, // Calendar.MONTH 是 0-based
            dayPicker.value,
            hourPicker.value,
            minutePicker.value,
            secondPicker.value
        )

        // 确保时间不小于最小时间
        if (calendar.timeInMillis < minTime) {
            calendar.timeInMillis = minTime
            updateDateTimePickers()
        }
    }

    private fun setQuickTime(daysToAdd: Int = 0, hoursToAdd: Int = 0, minutesToAdd: Int = 0) {
        val quickCalendar = Calendar.getInstance()
        quickCalendar.timeInMillis = System.currentTimeMillis()

        quickCalendar.add(Calendar.DAY_OF_MONTH, daysToAdd)
        quickCalendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
        quickCalendar.add(Calendar.MINUTE, minutesToAdd)

        // 如果添加的是0分钟，设置为整分钟
        if (minutesToAdd == 0) {
            quickCalendar.set(Calendar.SECOND, 0)
        }

        // 如果添加的是0小时且是当前时间，检查是否需要调整
        if (hoursToAdd == 0 && daysToAdd == 0 && minutesToAdd == 0) {
            // 当前时间，保持当前分钟，但将秒设为0
            quickCalendar.set(Calendar.SECOND, 0)
        }

        calendar.timeInMillis = quickCalendar.timeInMillis
        updateDateTimePickers()
    }

    // 修改 updateDateTimePickers 方法，确保初始显示正确
    private fun updateDateTimePickers() {
        yearPicker.value = calendar.get(Calendar.YEAR)
        monthPicker.value = calendar.get(Calendar.MONTH) + 1
        updateDayPicker()
        dayPicker.value = calendar.get(Calendar.DAY_OF_MONTH)
        hourPicker.value = calendar.get(Calendar.HOUR_OF_DAY)
        minutePicker.value = calendar.get(Calendar.MINUTE)
        secondPicker.value = calendar.get(Calendar.SECOND)
        updateDisplay()
    }

    override fun onStart() {
        super.onStart()
        // 确保对话框大小合适
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}