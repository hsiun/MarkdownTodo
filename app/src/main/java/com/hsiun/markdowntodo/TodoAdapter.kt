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

        // 优化字体渲染
        holder.titleText.typeface = Typeface.DEFAULT
        holder.titleText.paintFlags = holder.titleText.paintFlags or
                Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        // 移除旧的监听器，防止重复绑定
        holder.checkbox.setOnCheckedChangeListener(null)

        // 设置复选框状态
        holder.checkbox.isChecked = todo.isCompleted

        // 根据完成状态设置样式
        if (todo.isCompleted) {
            holder.titleText.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
        } else {
            holder.titleText.paintFlags = 0
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
        }

        // 设置复选框监听器 - 修复这里！！！
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) {
                return@setOnCheckedChangeListener
            }

            isUpdating = true
            try {
                // 重要：创建待办项的副本并更新状态
                val updatedTodo = todo.copy(isCompleted = isChecked)

                // 更新主列表中的待办项
                val indexInTodos = todos.indexOfFirst { it.id == updatedTodo.id }
                if (indexInTodos != -1) {
                    todos[indexInTodos] = updatedTodo
                }

                // 更新过滤列表中的待办项
                val indexInFiltered = filteredTodos.indexOfFirst { it.id == updatedTodo.id }
                if (indexInFiltered != -1) {
                    filteredTodos[indexInFiltered] = updatedTodo
                }

                // 通知外部（MainActivity）待办已更改
                onTodoChanged(updatedTodo)

                // 更新样式
                if (isChecked) {
                    holder.titleText.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
                    holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
                } else {
                    holder.titleText.paintFlags = 0
                    holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
                }

                // 如果设置了只显示未完成，当待办被标记为完成时，从过滤列表中移除
                if (displayMode == DisplayMode.ACTIVE && isChecked) {
                    // 延迟更新列表，避免在RecyclerView布局过程中修改
                    holder.itemView.post {
                        updateFilteredList()
                        notifyDataSetChanged()
                    }
                } else if (displayMode == DisplayMode.COMPLETED && !isChecked) {
                    // 如果设置了只显示已完成，当待办被取消完成时，从过滤列表中移除
                    holder.itemView.post {
                        updateFilteredList()
                        notifyDataSetChanged()
                    }
                }
            } finally {
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

    fun getPositionById(id: Int): Int {
        return filteredTodos.indexOfFirst { it.id == id }
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

    // 确保 updateFilteredList 方法正确工作
    private fun updateFilteredList() {
        filteredTodos.clear()
        when (displayMode) {
            DisplayMode.ALL -> {
                // 显示所有待办
                filteredTodos.addAll(todos)
                Log.d("TodoAdapter", "显示全部: ${todos.size} 条")
            }
            DisplayMode.ACTIVE -> {
                val activeTodos = todos.filter { !it.isCompleted }
                filteredTodos.addAll(activeTodos)
                Log.d("TodoAdapter", "显示未完成: ${activeTodos.size} 条, 总共: ${todos.size} 条")
            }
            DisplayMode.COMPLETED -> {
                val completedTodos = todos.filter { it.isCompleted }
                filteredTodos.addAll(completedTodos)
                Log.d("TodoAdapter", "显示已完成: ${completedTodos.size} 条, 总共: ${todos.size} 条")
            }
        }
    }

    // 获取当前显示模式
    fun getDisplayMode(): DisplayMode = displayMode

    fun updateTodos(newTodos: List<TodoItem>) {
        // 更新内部列表
        todos.clear()
        todos.addAll(newTodos)

        // 更新过滤列表
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

    // 获取所有待办事项数量
    fun getAllTodosCount(): Int = todos.size

    // 获取活跃（未完成）待办事项数量
    fun getActiveTodosCount(): Int = todos.count { !it.isCompleted }

    // 获取已完成待办事项数量
    fun getCompletedTodosCount(): Int = todos.count { it.isCompleted }
}