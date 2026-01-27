package com.hsiun.markdowntodo

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TodoAdapter(
    private var todos: MutableList<TodoItem>,
    private val onTodoChanged: (TodoItem) -> Unit,
    private val onTodoDeleted: (TodoItem) -> Unit,
    private val onTodoClicked: (TodoItem) -> Unit
) : RecyclerView.Adapter<TodoAdapter.ViewHolder>() {

    // 显示模式枚举
    enum class DisplayMode {
        ALL,           // 显示所有待办
        ACTIVE,        // 只显示未完成
        COMPLETED      // 只显示已完成
    }

    private var displayMode: DisplayMode = DisplayMode.ACTIVE
    private var filteredTodos: MutableList<TodoItem> = mutableListOf()

    // 添加显示模式变化监听器
    interface DisplayModeChangeListener {
        fun onDisplayModeChanged(newMode: DisplayMode, itemCount: Int)
    }

    private var displayModeChangeListener: DisplayModeChangeListener? = null

    fun setDisplayModeChangeListener(listener: DisplayModeChangeListener) {
        this.displayModeChangeListener = listener
    }

    // 添加一个标记，防止复选框事件循环
    private var isUpdating = false

    init {
        updateFilteredList()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val titleText: TextView = view.findViewById(R.id.titleText)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        val itemContainer: LinearLayout = view.findViewById(R.id.itemContainer)
        // 新增：提醒时间显示
        val reminderText: TextView = view.findViewById(R.id.reminderText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.todo_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < 0 || position >= filteredTodos.size) {
            Log.e("TodoAdapter", "无效的position: $position, 列表大小: ${filteredTodos.size}")
            return
        }

        val todo = filteredTodos[position]

        // 重置视图状态
        holder.deleteButton.visibility = View.GONE
        holder.deleteButton.translationX = 0f
        holder.itemContainer.translationX = 0f

        holder.titleText.text = todo.title

        // 优化字体渲染，解决中文锯齿问题
        holder.titleText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        
        // 设置字体渲染提示（必须在设置paintFlags之前）
        holder.titleText.paint.isSubpixelText = true
        holder.titleText.paint.isAntiAlias = true
        holder.titleText.paint.isLinearText = true
        
        // 移除旧的监听器，防止重复绑定
        holder.checkbox.setOnCheckedChangeListener(null)

        // 设置复选框状态
        holder.checkbox.isChecked = todo.isCompleted

        // 根据完成状态设置样式（保留字体渲染优化标志）
        val baseFlags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
        if (todo.isCompleted) {
            holder.titleText.paintFlags = baseFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
        } else {
            holder.titleText.paintFlags = baseFlags
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
        }

        // 显示提醒时间
        val reminderStatus = todo.getReminderStatus()
        if (reminderStatus.isNotEmpty()) {
            holder.reminderText.visibility = View.VISIBLE
            holder.reminderText.text = "提醒: $reminderStatus"

            // 根据提醒状态设置不同颜色
            when {
                todo.hasReminded -> {
                    holder.reminderText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
                    holder.reminderText.text = "已提醒"
                }
                todo.remindTime > 0 && System.currentTimeMillis() >= todo.remindTime -> {
                    holder.reminderText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.reminder_overdue))
                    holder.reminderText.text = "待提醒"
                }
                else -> {
                    holder.reminderText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.reminder_pending))
                }
            }
        } else {
            holder.reminderText.visibility = View.GONE
        }

        // 设置复选框监听器 - 简化处理，只触发外部回调，不更新本地数据
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) {
                return@setOnCheckedChangeListener
            }

            isUpdating = true

            try {
                // 使用当前 position 从 filteredTodos 获取 todo
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < filteredTodos.size) {
                    val currentTodo = filteredTodos[currentPosition]
                    // 记录原始状态，用于回滚
                    val originalChecked = todo.isCompleted

                    // 如果状态没有变化，直接返回
                    if (isChecked == originalChecked) {
                        isUpdating = false
                        return@setOnCheckedChangeListener
                    }

                    Log.d(
                        "TodoAdapter",
                        "待办状态改变: ID=${todo.id}, 标题='${todo.title}', 新状态=$isChecked"
                    )

                    // 立即更新视觉反馈（保留字体渲染优化标志）
                    val baseFlags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
                    if (isChecked) {
                        holder.titleText.paintFlags = baseFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        holder.titleText.setTextColor(
                            ContextCompat.getColor(
                                holder.itemView.context,
                                android.R.color.darker_gray
                            )
                        )
                    } else {
                        holder.titleText.paintFlags = baseFlags
                        holder.titleText.setTextColor(
                            ContextCompat.getColor(
                                holder.itemView.context,
                                android.R.color.black
                            )
                        )
                    }

                    // 触发外部回调，让TodoManager处理状态切换和数据更新
                    val updatedTodo = todo.copy(isCompleted = isChecked)
                    onTodoChanged(updatedTodo)
                }
                // 延迟一段时间后重置更新标记
                holder.itemView.postDelayed({
                    isUpdating = false
                }, 500)

            } catch (e: Exception) {
                Log.e("TodoAdapter", "复选框状态改变异常", e)
                isUpdating = false
            }
        }

        // 设置删除按钮点击事件
        holder.deleteButton.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val currentTodo = getItemAtPosition(currentPosition)
                if (currentTodo != null) {
                    onTodoDeleted(currentTodo)
                }
            }
        }

        // 设置item点击事件（用于打开编辑对话框）
        holder.itemContainer.setOnClickListener {
            onTodoClicked(todo)
        }
    }

    override fun getItemCount() = filteredTodos.size

    // 获取指定位置的待办事项
    fun getItemAtPosition(position: Int): TodoItem? {
        return if (position in 0 until filteredTodos.size) {
            filteredTodos[position]
        } else {
            null
        }
    }


    // 设置显示模式
    fun setDisplayMode(mode: DisplayMode) {
        Log.d("TodoAdapter", "设置显示模式: 当前模式=$displayMode, 新模式=$mode")

        if (displayMode != mode) {
            displayMode = mode
            updateFilteredList()
            notifyDataSetChanged()
            Log.d("TodoAdapter", "显示模式已切换为: $mode, 显示 ${filteredTodos.size} 条")
        } else {
            Log.d("TodoAdapter", "显示模式未变化，无需更新")
        }
    }

    // 如果需要使用createdAt字段排序，可以添加一个辅助函数
    private fun parseCreatedAtDate(todo: TodoItem): Date {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(todo.createdAt) ?: Date(0)
        } catch (e: Exception) {
            Date(0)
        }
    }

    // TodoAdapter.kt 中已有的代码保持不变，只需添加这个方法
    fun getTodoAtPosition(position: Int): TodoItem? {
        return if (position in 0 until todos.size) {
            todos[position]
        } else {
            null
        }
    }

    // 然后修改updateFilteredList方法
    private fun updateFilteredList() {
        filteredTodos.clear()

        when (displayMode) {
            DisplayMode.ALL -> {
                // 按创建时间降序（最新创建的在前）
                val activeTodos = todos.filter { !it.isCompleted }
                    .sortedByDescending { parseCreatedAtDate(it) }

                val completedTodos = todos.filter { it.isCompleted }
                    .sortedByDescending { parseCreatedAtDate(it) }

                filteredTodos.addAll(activeTodos)
                filteredTodos.addAll(completedTodos)

                Log.d("TodoAdapter", "显示全部: 未完成 ${activeTodos.size} 条, 已完成 ${completedTodos.size} 条")
            }
            DisplayMode.ACTIVE -> {
                // 只显示未完成的，按创建时间降序
                val activeTodos = todos.filter { !it.isCompleted }
                    .sortedByDescending { parseCreatedAtDate(it) }

                filteredTodos.addAll(activeTodos)
                Log.d("TodoAdapter", "显示未完成: ${activeTodos.size} 条")
            }
            DisplayMode.COMPLETED -> {
                // 只显示已完成的，按创建时间降序
                val completedTodos = todos.filter { it.isCompleted }
                    .sortedByDescending { parseCreatedAtDate(it) }

                filteredTodos.addAll(completedTodos)
                Log.d("TodoAdapter", "显示已完成: ${completedTodos.size} 条")
            }
        }

        // 通知显示模式变化监听器
        displayModeChangeListener?.onDisplayModeChanged(displayMode, filteredTodos.size)
    }

    // 获取当前显示模式
    fun getDisplayMode(): DisplayMode = displayMode


    fun updateTodos(newTodos: List<TodoItem>) {
        // 更新内部列表
        todos.clear()
        todos.addAll(newTodos)

        // 更新过滤列表（应用新的排序逻辑）
        updateFilteredList()

        // 通知数据已更改
        notifyDataSetChanged()

        Log.d("TodoAdapter", "更新列表: 共 ${todos.size} 条待办, 显示 ${filteredTodos.size} 条")
    }

    // 移除单个待办事项
    fun removeTodo(todo: TodoItem) {
        val positionInTodos = todos.indexOfFirst { it.id == todo.id }
        if (positionInTodos != -1) {
            todos.removeAt(positionInTodos)
            updateFilteredList()
            notifyDataSetChanged()
            Log.d("TodoAdapter", "移除待办: ID=${todo.id}")
        } else {
            Log.w("TodoAdapter", "尝试移除不存在的待办: ID=${todo.id}")
        }
    }

}