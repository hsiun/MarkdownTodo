package com.hsiun.markdowntodo

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

class TodoManager(private val context: Context) {

    companion object {
        private const val TAG = "TodoManager"
        private const val TODO_FILE_NAME = "todos.md"
        private const val MAX_ID_FILE = "max_id.json"
    }

    private lateinit var todoFile: File
    private var todos = mutableListOf<TodoItem>()
    private var nextId = 1
    private var todoChangeListener: TodoChangeListener? = null

    interface TodoChangeListener {
        fun onTodosChanged(todos: List<TodoItem>)
        fun onTodoAdded(todo: TodoItem)
        fun onTodoUpdated(todo: TodoItem)
        fun onTodoDeleted(todo: TodoItem)
        fun onTodoError(message: String)
    }

    fun setTodoChangeListener(listener: TodoChangeListener) {
        this.todoChangeListener = listener
    }

    fun init() {
        todoFile = File(context.filesDir, TODO_FILE_NAME)
        loadMaxId()
        loadLocalTodos()
    }

    private fun loadMaxId() {
        val maxIdFile = File(context.filesDir, MAX_ID_FILE)
        if (maxIdFile.exists()) {
            try {
                val json = maxIdFile.readText()
                val jsonObject = JSONObject(json)
                nextId = jsonObject.getInt("max_id")
                Log.d(TAG, "加载最大ID: $nextId")
            } catch (e: Exception) {
                Log.e(TAG, "加载最大ID失败", e)
                nextId = 1
            }
        }
    }

    private fun saveMaxId() {
        try {
            val maxIdFile = File(context.filesDir, MAX_ID_FILE)
            val jsonObject = JSONObject().apply {
                put("max_id", nextId)
            }
            maxIdFile.writeText(jsonObject.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存最大ID失败", e)
        }
    }

    fun loadLocalTodos() {
        try {
            if (!todoFile.exists()) {
                Log.d(TAG, "待办文件不存在，创建空文件")
                todoFile.createNewFile()
                saveTodosToFile(emptyList())
                todoChangeListener?.onTodosChanged(emptyList())
                return
            }

            val loadedTodos = readTodosFromFile(todoFile)
            // 新添加的放在前面
            val sortedTodos = loadedTodos.sortedBy { it.id }.reversed()

            Log.d(TAG, "加载到 ${sortedTodos.size} 条待办事项")

            todos.clear()
            todos.addAll(sortedTodos)

            if (sortedTodos.isNotEmpty()) {
                nextId = sortedTodos.maxOf { it.id } + 1
            } else {
                nextId = 1
            }

            saveMaxId()
            todoChangeListener?.onTodosChanged(sortedTodos)

            Log.d(TAG, "加载本地待办事项完成: ${sortedTodos.size} 条, nextId: $nextId")
        } catch (e: Exception) {
            Log.e(TAG, "加载失败", e)
            todoChangeListener?.onTodoError("加载失败: ${e.message}")
        }
    }

    fun getAllTodos(): List<TodoItem> {
        return todos.toList()
    }

    fun getTodoById(id: Int): TodoItem? {
        return todos.find { it.id == id }
    }

    fun addTodo(title: String, setReminder: Boolean = false, remindTime: Long = -1L): TodoItem {
        return try {
            if (title.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val newId = nextId++
            val todo = TodoItem(id = newId, title = title, remindTime = if (setReminder) remindTime else -1L)

            Log.d(TAG, "添加待办事项: ID=$newId, 标题='$title', 提醒时间=${if (setReminder) remindTime else "未设置"}")

            todos.add(todo)
            saveLocalTodos()
            saveMaxId()

            if (setReminder && remindTime > 0) {
                // 设置提醒
                ReminderScheduler.scheduleReminder(context, todo)
                Log.d(TAG, "设置了提醒: ${todo.formatRemindTime()}")
            }

            todoChangeListener?.onTodoAdded(todo)
            todo
        } catch (e: Exception) {
            Log.e(TAG, "添加待办失败", e)
            todoChangeListener?.onTodoError("添加失败: ${e.message}")
            throw e
        }
    }

    fun updateTodo(id: Int, newTitle: String, setReminder: Boolean = false, remindTime: Long = -1L): TodoItem {
        return try {
            if (newTitle.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val todoIndex = todos.indexOfFirst { it.id == id }
            if (todoIndex == -1) {
                throw IllegalArgumentException("未找到待办事项")
            }

            val oldTodo = todos[todoIndex]

            // 如果之前有提醒，先取消
            if (oldTodo.remindTime > 0) {
                ReminderScheduler.cancelReminder(context, oldTodo)
            }

            val updatedTodo = oldTodo.copy(
                title = newTitle,
                remindTime = if (setReminder) remindTime else -1L,
                hasReminded = if (!setReminder) false else oldTodo.hasReminded
            )
            todos[todoIndex] = updatedTodo

            saveLocalTodos()

            if (setReminder && remindTime > 0) {
                // 设置新的提醒
                ReminderScheduler.scheduleReminder(context, updatedTodo)
                Log.d(TAG, "更新了提醒: ${updatedTodo.formatRemindTime()}")
            }

            todoChangeListener?.onTodoUpdated(updatedTodo)
            updatedTodo
        } catch (e: Exception) {
            Log.e(TAG, "更新待办失败", e)
            todoChangeListener?.onTodoError("更新失败: ${e.message}")
            throw e
        }
    }

    fun toggleTodoStatus(id: Int): TodoItem {
        return try {
            val todoIndex = todos.indexOfFirst { it.id == id }
            if (todoIndex == -1) {
                throw IllegalArgumentException("未找到待办事项")
            }

            val oldTodo = todos[todoIndex]
            val updatedTodo = oldTodo.copy(isCompleted = !oldTodo.isCompleted)

            // 如果标记为完成，取消提醒
            if (updatedTodo.isCompleted && oldTodo.remindTime > 0) {
                ReminderScheduler.cancelReminder(context, updatedTodo)
            }

            todos[todoIndex] = updatedTodo

            saveLocalTodos()
            todoChangeListener?.onTodoUpdated(updatedTodo)
            updatedTodo
        } catch (e: Exception) {
            Log.e(TAG, "切换待办状态失败", e)
            todoChangeListener?.onTodoError("切换状态失败: ${e.message}")
            throw e
        }
    }

    fun deleteTodo(id: Int): TodoItem {
        return try {
            val todoIndex = todos.indexOfFirst { it.id == id }
            if (todoIndex == -1) {
                throw IllegalArgumentException("未找到待办事项")
            }

            val todoToDelete = todos[todoIndex]
            todos.removeAt(todoIndex)

            // 取消提醒
            if (todoToDelete.remindTime > 0) {
                ReminderScheduler.cancelReminder(context, todoToDelete)
            }

            saveLocalTodos()
            todoChangeListener?.onTodoDeleted(todoToDelete)
            todoToDelete
        } catch (e: Exception) {
            Log.e(TAG, "删除待办失败", e)
            todoChangeListener?.onTodoError("删除失败: ${e.message}")
            throw e
        }
    }

    // 新增：获取所有需要提醒的待办
    fun getTodosWithReminder(): List<TodoItem> {
        return todos.filter { it.remindTime > 0 && !it.isCompleted && !it.hasReminded }
    }

    // 新增：检查并触发到期的提醒
    fun checkAndTriggerReminders() {
        val now = System.currentTimeMillis()
        val todosToRemind = todos.filter {
            it.remindTime > 0 &&
                    !it.hasReminded &&
                    !it.isCompleted &&
                    now >= it.remindTime
        }

        todosToRemind.forEach { todo ->
            // 触发提醒
            ReminderScheduler.triggerReminder(context, todo)
            // 标记为已提醒
            markAsReminded(todo.id)
        }
    }

    // 新增：标记为已提醒
    private fun markAsReminded(id: Int): TodoItem? {
        val todoIndex = todos.indexOfFirst { it.id == id }
        if (todoIndex == -1) return null

        val oldTodo = todos[todoIndex]
        val updatedTodo = oldTodo.copy(hasReminded = true)
        todos[todoIndex] = updatedTodo

        saveLocalTodos()
        todoChangeListener?.onTodoUpdated(updatedTodo)

        return updatedTodo
    }

    // 新增：重新调度所有提醒
    fun rescheduleAllReminders() {
        val todosWithReminder = getTodosWithReminder()
        todosWithReminder.forEach { todo ->
            ReminderScheduler.scheduleReminder(context, todo)
        }
        Log.d(TAG, "重新调度了 ${todosWithReminder.size} 个提醒")
    }

    fun deleteTodoByUuid(uuid: String): Boolean {
        return try {
            val todoIndex = todos.indexOfFirst { it.uuid == uuid }
            if (todoIndex == -1) {
                return false
            }

            val todoToDelete = todos[todoIndex]
            todos.removeAt(todoIndex)

            saveLocalTodos()
            todoChangeListener?.onTodoDeleted(todoToDelete)
            true
        } catch (e: Exception) {
            Log.e(TAG, "根据UUID删除待办失败", e)
            false
        }
    }

    fun replaceAllTodos(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos)

        if (newTodos.isNotEmpty()) {
            nextId = newTodos.maxOf { it.id } + 1
        } else {
            nextId = 1
        }

        saveLocalTodos()
        saveMaxId()
        todoChangeListener?.onTodosChanged(newTodos)
    }

    fun getActiveTodosCount(): Int {
        return todos.count { !it.isCompleted }
    }

    fun getCompletedTodosCount(): Int {
        return todos.count { it.isCompleted }
    }

    fun filterTodosByCompletion(showCompleted: Boolean): List<TodoItem> {
        return if (showCompleted) {
            todos.sortedBy { it.id }
        } else {
            todos.filter { !it.isCompleted }.sortedBy { it.id }
        }
    }

    private fun saveLocalTodos() {
        try {
            saveTodosToFile(todos)
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            todoChangeListener?.onTodoError("保存失败: ${e.message}")
        }
    }

    private fun saveTodosToFile(todos: List<TodoItem>) {
        try {
            todoFile.bufferedWriter().use { writer ->
                writer.write("# 待办事项\n\n")
                todos.forEach { todo ->
                    writer.write("${todo.toMarkdownLine()}\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存文件失败", e)
            throw e
        }
    }

    private fun readTodosFromFile(file: File): List<TodoItem> {
        return if (file.exists() && file.length() > 0) {
            try {
                val lines = file.readLines()
                if (lines.size > 2) {
                    lines.drop(2)
                        .filter { it.isNotBlank() }
                        .mapNotNull { TodoItem.fromMarkdownLine(it) }
                        .toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取待办文件失败", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}