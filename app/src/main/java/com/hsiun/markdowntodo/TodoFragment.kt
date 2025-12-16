package com.hsiun.markdowntodo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.databinding.FragmentTodoBinding

class TodoFragment : Fragment(), TodoManager.TodoChangeListener {

    companion object {
        private const val TAG = "TodoFragment"
    }

    private lateinit var binding: FragmentTodoBinding
    private lateinit var adapter: TodoAdapter

    private var currentDisplayMode = TodoAdapter.DisplayMode.ACTIVE
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTodoBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 设置显示模式
    fun setDisplayMode(mode: TodoAdapter.DisplayMode) {
        if (::adapter.isInitialized) {
            Log.d(TAG, "设置显示模式: $mode")
            adapter.setDisplayMode(mode)
            updateEmptyView()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "TodoFragment onViewCreated")

        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            Toast.makeText(requireContext(), "MainActivity未初始化", Toast.LENGTH_SHORT).show()
            return
        }

        // 初始化适配器
        adapter = TodoAdapter(
            mutableListOf(),
            onTodoChanged = { todo ->
                Log.d(TAG, "待办状态改变: ${todo.id} - ${todo.title}")
                mainActivity.todoManager.toggleTodoStatus(todo.id)
            },
            onTodoDeleted = { todo ->
                Log.d(TAG, "删除待办: ${todo.id} - ${todo.title}")
                mainActivity.showDeleteTodoConfirmationDialog(todo)
            },
            onTodoClicked = { todo ->
                Log.d(TAG, "点击待办: ${todo.id} - ${todo.title}")
                mainActivity.todoDialogManager.showEditTodoDialog(todo, mainActivity)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 设置左滑删除功能
        setupSwipeToDelete()
        // 设置监听器
        mainActivity.todoManager.setTodoChangeListener(this)

        // 根据当前设置初始化显示模式
        val showCompleted = mainActivity.settingsManager.showCompletedTodos
        val initialDisplayMode = if (showCompleted) {
            TodoAdapter.DisplayMode.ALL
        } else {
            TodoAdapter.DisplayMode.ACTIVE
        }
        setDisplayMode(initialDisplayMode)
        // 初始化时显示空状态
        updateEmptyView()

        Log.d(TAG, "TodoFragment 初始化完成")
    }
    private fun setupSwipeToDelete() {
        // 创建左滑删除的ItemTouchHelper回调
        val swipeToDeleteCallback = object : ItemTouchHelper.SimpleCallback(
            0, // 不支持拖动
            ItemTouchHelper.LEFT // 只支持左滑
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // 不支持拖动
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val todo = adapter.getTodoAtPosition(position)
                    todo?.let {
                        // 先恢复item位置
                        adapter.notifyItemChanged(position)
                        // 直接调用MainActivity的删除确认对话框
                        val mainActivity = activity as? MainActivity
                        mainActivity?.showDeleteTodoConfirmationDialog(it)
                    }
                }
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView

                    // 向左滑动时绘制红色删除背景
                    if (dX < 0) {
                        // 绘制红色背景
                        val background = ColorDrawable(Color.parseColor("#FF3B30"))
                        background.setBounds(
                            itemView.right + dX.toInt(),
                            itemView.top,
                            itemView.right,
                            itemView.bottom
                        )
                        background.draw(canvas)

                        // 绘制删除图标（如果没有图标资源，用文字代替）
                        val paint = Paint()
                        paint.color = Color.WHITE
                        paint.textSize = 42f
                        paint.isAntiAlias = true

                        val text = "删除"
                        val textWidth = paint.measureText(text)
                        val textHeight = paint.descent() - paint.ascent()

                        // 计算文字位置：在红色区域的中间
                        val textX = itemView.right - textWidth - 48f
                        val textY = itemView.top + (itemView.height - textHeight) / 2 - paint.ascent()

                        canvas.drawText(text, textX, textY, paint)
                    }
                }

                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                // 降低滑动触发速度，更容易触发
                return defaultValue * 0.5f
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                // 降低滑动阈值，更容易触发删除
                return 0.2f
            }
        }

        itemTouchHelper = ItemTouchHelper(swipeToDeleteCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "TodoFragment onResume")

        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            // 确保TodoManager已初始化并加载数据
            if (mainActivity.todoManager.getAllTodos().isNotEmpty()) {
                Log.d(TAG, "已有数据，直接加载")
                loadTodos()
            } else {
                Log.d(TAG, "无数据，等待监听器回调")
            }
        }
    }

    fun loadTodos() {
        val mainActivity = activity as? MainActivity ?: return

        Log.d(TAG, "开始加载待办事项...")
        val todos = mainActivity.todoManager.getAllTodos()
        Log.d(TAG, "加载到 ${todos.size} 条待办事项")

        if (::adapter.isInitialized) {
            // 更新适配器数据
            adapter.updateTodos(todos)

            // 更新UI
            updateEmptyView()

            // 打印调试信息
            todos.forEachIndexed { index, todo ->
                Log.d(TAG, "待办 $index: ID=${todo.id}, 标题='${todo.title}', 完成状态=${todo.isCompleted}")
            }
        } else {
            Log.e(TAG, "adapter 未初始化，无法加载数据")
        }
    }

    private fun updateEmptyView() {
        if (!::adapter.isInitialized) {
            Log.e(TAG, "adapter 未初始化，无法更新空视图")
            return
        }

        val hasTodos = adapter.itemCount > 0
        Log.d(TAG, "更新空视图: hasTodos=$hasTodos, itemCount=${adapter.itemCount}")

        binding.emptyView.visibility = if (hasTodos) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasTodos) View.VISIBLE else View.GONE

        // 根据当前显示模式更新提示文本
        val mode = adapter.getDisplayMode()
        val modeText = when (mode) {
            TodoAdapter.DisplayMode.ALL -> "全部"
            TodoAdapter.DisplayMode.ACTIVE -> "未完成"
            else -> {}
        }

        binding.emptyView.text = "暂无$modeText 待办事项\n下拉刷新可同步云端数据"
    }

    // TodoManager.TodoChangeListener 实现
    override fun onTodosChanged(todos: List<TodoItem>) {
        Log.d(TAG, "onTodosChanged: ${todos.size} 条待办")

        if (::adapter.isInitialized) {
            requireActivity().runOnUiThread {
                adapter.updateTodos(todos)
                updateEmptyView()
            }
        } else {
            Log.e(TAG, "adapter 未初始化，无法更新列表")
        }
    }

    override fun onTodoAdded(todo: TodoItem) {
        Log.d(TAG, "onTodoAdded: ${todo.id} - ${todo.title}")

        requireActivity().runOnUiThread {
            val mainActivity = activity as? MainActivity ?: return@runOnUiThread

            if (::adapter.isInitialized) {
                val todos = mainActivity.todoManager.getAllTodos()
                adapter.updateTodos(todos)
                updateEmptyView()
            } else {
                Log.e(TAG, "adapter 未初始化，无法添加待办")
            }
        }
    }

    override fun onTodoUpdated(todo: TodoItem) {
        Log.d(TAG, "onTodoUpdated: ${todo.id} - ${todo.title}")

        requireActivity().runOnUiThread {
            val mainActivity = activity as? MainActivity ?: return@runOnUiThread

            if (::adapter.isInitialized) {
                val todos = mainActivity.todoManager.getAllTodos()
                adapter.updateTodos(todos)
                updateEmptyView()
            } else {
                Log.e(TAG, "adapter 未初始化，无法更新待办")
            }
        }
    }

    override fun onTodoDeleted(todo: TodoItem) {
        Log.d(TAG, "onTodoDeleted: ${todo.id} - ${todo.title}")

        requireActivity().runOnUiThread {
            if (::adapter.isInitialized) {
                adapter.removeTodo(todo)
                updateEmptyView()
            } else {
                Log.e(TAG, "adapter 未初始化，无法删除待办")
            }
        }
    }

    override fun onTodoError(message: String) {
        Log.e(TAG, "onTodoError: $message")

        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}