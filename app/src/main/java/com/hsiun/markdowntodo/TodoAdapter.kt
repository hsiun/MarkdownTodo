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
    private val onTodoDeleted: (TodoItem) -> Unit
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

    // 添加当前正在滑动的position
    private var swipedPosition = -1

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
        holder.titleText.typeface = Typeface.DEFAULT  // 使用默认字体
        holder.titleText.paintFlags = holder.titleText.paintFlags or
                Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG  // 开启抗锯齿和子像素渲染

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

        // 设置复选框监听器
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) {
                return@setOnCheckedChangeListener
            }

            isUpdating = true
            try {
                // 更新待办项的完成状态
                todo.isCompleted = isChecked

                // 通知外部（MainActivity）待办已更改
                onTodoChanged(todo)

                // 更新样式
                if (isChecked) {
                    holder.titleText.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
                    holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
                } else {
                    holder.titleText.paintFlags = 0
                    holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
                }

                // 如果设置为只显示未完成，当待办被标记为完成时，从过滤列表中移除
                if (displayMode == DisplayMode.ACTIVE && isChecked) {
                    // 延迟更新列表，避免在RecyclerView布局过程中修改
                    holder.itemView.post {
                        updateFilteredList()
                        notifyDataSetChanged()
                    }
                } else if (displayMode == DisplayMode.COMPLETED && !isChecked) {
                    // 如果设置为只显示已完成，当待办被取消完成时，从过滤列表中移除
                    holder.itemView.post {
                        updateFilteredList()
                        notifyDataSetChanged()
                    }
                }
                // 如果是显示全部模式，不需要更新列表，只需要更新当前项
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
                    // 隐藏删除按钮
                    holder.deleteButton.visibility = View.GONE
                    // 通知删除
                    onTodoDeleted(currentTodo)
                }
            }
        }

        // 设置item点击事件（用于恢复滑动状态）
        holder.itemContainer.setOnClickListener {
            // 如果当前有滑动的item，恢复它
            if (swipedPosition != -1 && swipedPosition != holder.adapterPosition) {
                notifyItemChanged(swipedPosition)
                swipedPosition = -1
            }
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

    // 获取指定ID的待办事项在所有列表中的位置（用于更新后刷新）
    fun getPositionById(id: Int): Int {
        return filteredTodos.indexOfFirst { it.id == id }
    }

    // 设置滑动状态
    fun setSwipedPosition(position: Int) {
        swipedPosition = position
    }

    // 重置滑动状态
    fun resetSwipedPosition() {
        swipedPosition = -1
    }

    // 修改 setDisplayMode 方法，添加日志并确保正确更新
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
                filteredTodos.addAll(todos.sortedBy { it.id })
                Log.d("TodoAdapter", "显示全部: ${todos.size} 条")
            }
            DisplayMode.ACTIVE -> {
                val activeTodos = todos.filter { !it.isCompleted }
                filteredTodos.addAll(activeTodos.sortedBy { it.id })
                Log.d("TodoAdapter", "显示未完成: ${activeTodos.size} 条, 总共: ${todos.size} 条")
            }
            DisplayMode.COMPLETED -> {
                val completedTodos = todos.filter { it.isCompleted }
                filteredTodos.addAll(completedTodos.sortedBy { it.id })
                Log.d("TodoAdapter", "显示已完成: ${completedTodos.size} 条, 总共: ${todos.size} 条")
            }
        }
    }

    // 获取当前显示模式
    fun getDisplayMode(): DisplayMode = displayMode

    fun updateTodos(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos.sortedBy { it.id })
        updateFilteredList()
        notifyDataSetChanged()
        Log.d("TodoAdapter", "更新列表: 共 ${todos.size} 条待办, 显示 ${filteredTodos.size} 条")
    }

    // 添加单个待办事项
    fun addTodo(todo: TodoItem) {
        todos.add(todo)
        todos.sortBy { it.id }
        updateFilteredList()
        notifyDataSetChanged()
        Log.d("TodoAdapter", "添加待办: ID=${todo.id}, 标题='${todo.title}'")
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