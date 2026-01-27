package com.hsiun.markdowntodo.data.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hsiun.markdowntodo.data.model.RepeatType
import com.hsiun.markdowntodo.data.model.TodoItem
import com.hsiun.markdowntodo.util.ReminderScheduler
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TodoManager(private val context: Context) {

    companion object {
        private const val TAG = "TodoManager"
        private const val GIT_REPO_DIR = "git_repo"
        private const val DIR_TODO_LISTS = "todo_lists"
        private const val MAX_ID_FILE = "max_id.json"
    }

    private var todos = mutableListOf<TodoItem>()
    private var nextId = 1
    private var todoChangeListener: TodoChangeListener? = null
    private lateinit var todoListManager: TodoListManager

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

    fun init(todoListManager: TodoListManager) {
        this.todoListManager = todoListManager
        loadMaxId()
        loadCurrentListTodos()
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

    fun loadCurrentListTodos() {
        try {
            // 1. 获取当前列表
            val currentList = todoListManager.getCurrentList()

            if (currentList == null) {
                // 兜底：如果 currentListId 指向的列表不存在（常见于同步后列表元数据被重建/覆盖），
                // 自动切回默认列表或第一个列表，再重试一次，避免用户看到“列表找不到”
                val fallbackList = todoListManager.getAllLists().firstOrNull { it.isDefault }
                    ?: todoListManager.getAllLists().firstOrNull()

                if (fallbackList != null && todoListManager.setCurrentList(fallbackList.id)) {
                    Log.w(TAG, "当前列表不存在，已自动切换到: ${fallbackList.name} (ID: ${fallbackList.id})")
                    loadCurrentListTodos()
                    return
                }

                Log.w(TAG, "当前列表不存在，且无法自动恢复")
                todoChangeListener?.onTodoError("当前列表不存在")
                return
            }

            Log.d(TAG, "开始加载列表: ${currentList.name}, 文件: ${currentList.fileName}")

            // 2. 从Git目录读取当前列表的待办事项
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)

            if (!todoListsDir.exists()) {
                Log.d(TAG, "Git待办目录不存在，创建空目录")
                todoListsDir.mkdirs()
                todoChangeListener?.onTodosChanged(emptyList())
                return
            }

            val listFile = File(todoListsDir, currentList.fileName)

            if (!listFile.exists()) {
                Log.d(TAG, "待办文件不存在，创建空文件: ${listFile.absolutePath}")
                listFile.createNewFile()
                saveTodosToFile(emptyList(), listFile)
                todoChangeListener?.onTodosChanged(emptyList())
                return
            }

            // 3. 读取文件内容
            val loadedTodos = readTodosFromFile(listFile)
            Log.d(TAG, "从文件 ${currentList.fileName} 加载到 ${loadedTodos.size} 条待办事项")

            // 4. 更新内存中的数据
            todos.clear()
            todos.addAll(loadedTodos)

            // 5. 更新下一个ID
            if (loadedTodos.isNotEmpty()) {
                nextId = loadedTodos.maxOf { it.id } + 1
            } else {
                nextId = 1
            }

            // 6. 保存最大ID和通知监听器
            saveMaxId()
            // 新增：加载后检查一致性
            ensureTodoConsistency()
            todoChangeListener?.onTodosChanged(loadedTodos)

            Log.d(TAG, "加载完成: ${loadedTodos.size} 条待办, nextId: $nextId")

        } catch (e: Exception) {
            Log.e(TAG, "加载失败", e)
            todoChangeListener?.onTodoError("加载失败: ${e.message}")
        }
    }

    fun getAllTodos(): List<TodoItem> {
        return todos.toList()
    }

    fun getTodoById(id: Int): TodoItem? {
        val items = todos.filter { it.id == id }
        if (items.size > 1) {
            Log.w(TAG, "发现多个ID为 $id 的待办事项，返回第一个")
            // 触发修复
            Handler(Looper.getMainLooper()).post {
                detectAndFixDuplicateIds()
            }
        }
        return items.firstOrNull()
    }
    /**
     * 检测并修复重复ID
     */
    private fun detectAndFixDuplicateIds(): Boolean {
        Log.d(TAG, "开始检测重复ID")

        val idMap = mutableMapOf<Int, MutableList<TodoItem>>()

        // 1. 按ID分组，找出重复的ID
        todos.forEach { todo ->
            idMap.getOrPut(todo.id) { mutableListOf() }.add(todo)
        }

        val duplicateIds = idMap.filter { it.value.size > 1 }.keys
        if (duplicateIds.isEmpty()) {
            Log.d(TAG, "未发现重复ID")
            return false
        }

        Log.w(TAG, "发现 ${duplicateIds.size} 个重复ID: $duplicateIds")

        // 2. 修复重复ID
        duplicateIds.forEach { duplicateId ->
            val itemsWithSameId = idMap[duplicateId] ?: return@forEach

            // 保留第一个，其他重新分配ID
            val itemsToFix = itemsWithSameId.drop(1)
            itemsToFix.forEachIndexed { index, todo ->
                val newId = nextId + index
                Log.w(TAG, "修复重复ID: 将待办 '${todo.title}' 的ID从 $duplicateId 改为 $newId")

                // 在列表中更新这个待办事项
                val todoIndex = todos.indexOfFirst { it.uuid == todo.uuid }
                if (todoIndex != -1) {
                    val fixedTodo = todo.copy(id = newId)
                    todos[todoIndex] = fixedTodo

                    // 更新监听器
                    todoChangeListener?.onTodoUpdated(fixedTodo)
                }
            }

            // 更新nextId
            nextId += itemsToFix.size
        }

        // 3. 保存修复后的数据
        saveCurrentListTodos()
        saveMaxId()

        Log.d(TAG, "重复ID修复完成，新的nextId: $nextId")
        return true
    }
    fun addTodo(title: String, setReminder: Boolean = false, remindTime: Long = -1L, repeatType: Int = RepeatType.NONE.value): TodoItem {
        return try {
            if (title.trim().isEmpty()) {
                throw IllegalArgumentException("标题不能为空")
            }

            val newId = nextId++
            val todo = TodoItem(
                id = newId,
                title = title,
                remindTime = if (setReminder) remindTime else -1L,
                repeatType = repeatType,
                originalRemindTime = if (setReminder) remindTime else -1L,
                nextRemindTime = if (setReminder && repeatType != RepeatType.NONE.value) remindTime else -1L,
                updatedAt = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
            )

            Log.d(TAG, "添加待办事项: ID=$newId, 标题='$title', 提醒时间=${if (setReminder) remindTime else "未设置"}, 重复类型=${RepeatType.Companion.fromValue(repeatType).displayName}")

            todos.add(todo)
            saveCurrentListTodos()
            saveMaxId()

            if (setReminder && remindTime > 0) {
                // 设置提醒
                ReminderScheduler.Companion.scheduleReminder(context, todo)
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

    fun updateTodo(id: Int, newTitle: String, setReminder: Boolean = false, remindTime: Long = -1L, repeatType: Int = RepeatType.NONE.value): TodoItem {
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
            if (oldTodo.remindTime > 0 || oldTodo.nextRemindTime > 0) {
                ReminderScheduler.Companion.cancelReminder(context, oldTodo)
            }

            val updatedTodo = oldTodo.copy(
                title = newTitle,
                remindTime = if (setReminder) remindTime else -1L,
                repeatType = repeatType,
                originalRemindTime = if (setReminder && oldTodo.originalRemindTime <= 0) remindTime else oldTodo.originalRemindTime,
                nextRemindTime = if (setReminder && repeatType != RepeatType.NONE.value) {
                    // 如果设置了重复，计算下次提醒时间
                    if (oldTodo.nextRemindTime <= 0) remindTime else oldTodo.nextRemindTime
                } else -1L,
                hasReminded = if (!setReminder) false else oldTodo.hasReminded
            )

            todos[todoIndex] = updatedTodo

            saveCurrentListTodos()

            if (setReminder && remindTime > 0) {
                // 设置新的提醒
                ReminderScheduler.Companion.scheduleReminder(context, updatedTodo)
                Log.d(TAG, "更新了提醒: ${updatedTodo.formatRemindTime()}, 重复类型=${RepeatType.Companion.fromValue(repeatType).displayName}")
            }

            todoChangeListener?.onTodoUpdated(updatedTodo)
            updatedTodo
        } catch (e: Exception) {
            Log.e(TAG, "更新待办失败", e)
            todoChangeListener?.onTodoError("更新失败: ${e.message}")
            throw e
        }
    }

    /**
     * 通过UUID切换待办状态（更可靠）
     */
    fun toggleTodoStatusByUuid(uuid: String): TodoItem {
        return try {
            val todoIndex = todos.indexOfFirst { it.uuid == uuid }
            if (todoIndex == -1) {
                throw IllegalArgumentException("未找到UUID为 $uuid 的待办事项")
            }

            val oldTodo = todos[todoIndex]
            val updatedTodo = oldTodo.copy(isCompleted = !oldTodo.isCompleted)

            // 如果标记为完成，取消提醒
            if (updatedTodo.isCompleted && (oldTodo.remindTime > 0 || oldTodo.nextRemindTime > 0)) {
                ReminderScheduler.Companion.cancelReminder(context, updatedTodo)
            } else if (!updatedTodo.isCompleted && oldTodo.remindTime > 0) {
                // 如果取消完成状态，重新调度提醒
                ReminderScheduler.Companion.scheduleReminder(context, updatedTodo)
            }

            todos[todoIndex] = updatedTodo

            saveCurrentListTodos()
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
            if (todoToDelete.remindTime > 0 || todoToDelete.nextRemindTime > 0) {
                ReminderScheduler.Companion.cancelReminder(context, todoToDelete)
            }

            saveCurrentListTodos()
            todoChangeListener?.onTodoDeleted(todoToDelete)
            todoToDelete
        } catch (e: Exception) {
            Log.e(TAG, "删除待办失败", e)
            todoChangeListener?.onTodoError("删除失败: ${e.message}")
            throw e
        }
    }

    // 保存当前列表的待办事项到Git目录
    private fun saveCurrentListTodos() {
        try {
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)

            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            val currentListFileName = todoListManager.getCurrentListFileName()
            val listFile = File(todoListsDir, currentListFileName)

            saveTodosToFile(todos, listFile)

            // 更新列表统计
            val total = todos.size
            val active = todos.count { !it.isCompleted }
            todoListManager.updateListCount(
                todoListManager.getCurrentListId(),
                total,
                active
            )

            Log.d(TAG, "已保存待办事项到Git目录: ${listFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            todoChangeListener?.onTodoError("保存失败: ${e.message}")
        }
    }

    fun handleRepeatedReminder(todoId: Int) {
        val todoIndex = todos.indexOfFirst { it.id == todoId }
        if (todoIndex == -1) return

        val oldTodo = todos[todoIndex]

        // 检查是否是重复提醒
        if (oldTodo.repeatType != RepeatType.NONE.value && oldTodo.originalRemindTime > 0) {
            // 计算下一次提醒时间
            val nextRemindTime = oldTodo.calculateNextRemindTime()

            if (nextRemindTime > 0) {
                val updatedTodo = oldTodo.copy(
                    nextRemindTime = nextRemindTime,
                    remindTime = nextRemindTime,
                    hasReminded = false
                )

                todos[todoIndex] = updatedTodo
                saveCurrentListTodos()

                // 调度下一次提醒
                ReminderScheduler.Companion.scheduleReminder(context, updatedTodo)
                Log.d(TAG, "已调度下次重复提醒: ${updatedTodo.title} at ${updatedTodo.formatRemindTime()}")
            }
        } else {
            // 非重复提醒，标记为已提醒
            val updatedTodo = oldTodo.copy(hasReminded = true)
            todos[todoIndex] = updatedTodo
            saveCurrentListTodos()
        }

        todoChangeListener?.onTodoUpdated(todos[todoIndex])
    }

    fun getTodosWithReminder(): List<TodoItem> {
        return todos.filter {
            (it.remindTime > 0 || it.nextRemindTime > 0) &&
                    !it.isCompleted &&
                    !it.hasReminded
        }
    }

    fun checkAndTriggerReminders() {
        val todosToRemind = todos.filter {
            it.shouldRemind()
        }

        todosToRemind.forEach { todo ->
            // 触发提醒
            ReminderScheduler.Companion.triggerReminder(context, todo)
            // 处理重复逻辑
            handleRepeatedReminder(todo.id)
        }
    }

    fun rescheduleAllReminders() {
        val todosWithReminder = getTodosWithReminder()
        todosWithReminder.forEach { todo ->
            ReminderScheduler.Companion.scheduleReminder(context, todo)
        }
        Log.d(TAG, "重新调度了 ${todosWithReminder.size} 个提醒")
    }


    fun replaceAllTodos(newTodos: List<TodoItem>) {
        // 先取消所有现有的提醒
        todos.forEach { todo ->
            if (todo.remindTime > 0 || todo.nextRemindTime > 0) {
                ReminderScheduler.Companion.cancelReminder(context, todo)
            }
        }

        todos.clear()
        todos.addAll(newTodos)

        if (newTodos.isNotEmpty()) {
            nextId = newTodos.maxOf { it.id } + 1
        } else {
            nextId = 1
        }

        saveCurrentListTodos()
        saveMaxId()

        // 重新调度所有需要提醒的待办
        rescheduleAllReminders()

        todoChangeListener?.onTodosChanged(newTodos)
        Log.d(TAG, "已替换所有待办: ${todos.size} 条，已重新调度提醒")

        // 重要：确保UI正确更新
        loadCurrentListTodos()  // 重新加载以确保数据一致
    }

    fun getActiveTodosCount(): Int {
        return todos.count { !it.isCompleted }
    }


    fun switchTodoList(listId: String): Boolean {
        // 切换后打印状态todo 待删除
        printAllListsStatus()
        try {
            Log.d(TAG, "开始切换列表: $listId")

            // 1. 验证要切换的列表存在
            val targetList = todoListManager.getAllLists().find { it.id == listId }
            if (targetList == null) {
                Log.e(TAG, "目标列表不存在: $listId")
                return false
            }

            // 2. 获取当前列表ID（切换前）
            val previousListId = todoListManager.getCurrentListId()
            Log.d(TAG, "从列表 $previousListId 切换到 $listId")

            // 3. 如果是切换到不同列表，保存当前列表
            if (previousListId != listId) {
                // 明确使用当前列表的文件名保存
                val previousList = todoListManager.getAllLists().find { it.id == previousListId }
                if (previousList != null) {
                    saveTodosToFileWithName(todos, previousList.fileName)
                    Log.d(TAG, "已保存前一个列表: ${previousList.name} 到文件: ${previousList.fileName}")
                }
            }

            // 4. 切换列表
            if (todoListManager.setCurrentList(listId)) {
                // 5. 清空内存中的待办事项
                todos.clear()

                // 6. 加载新列表的待办事项
                loadCurrentListTodos()

                Log.d(TAG, "成功切换到列表: ${todoListManager.getCurrentListName()}")

                return true
            }
            // 切换后打印状态todo 待删除
            printAllListsStatus()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "切换列表失败", e)
            return false
        }
    }


    // 新增：按文件名保存待办事项
    private fun saveTodosToFileWithName(todosToSave: List<TodoItem>, fileName: String) {
        try {
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)

            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            val listFile = File(todoListsDir, fileName)
            saveTodosToFile(todosToSave, listFile)

            Log.d(TAG, "已保存 ${todosToSave.size} 条待办到文件: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "按文件名保存失败", e)
        }
    }

    // 从文件读取待办事项
    fun readTodosFromFile(file: File): List<TodoItem> {
        return if (file.exists() && file.length() > 0) {
            try {
                val lines = file.readLines()
                if (lines.size > 2) {
                    lines.drop(2)
                        .filter { it.isNotBlank() }
                        .mapNotNull { TodoItem.Companion.fromMarkdownLine(it) }
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

    // 保存待办事项到文件
    private fun saveTodosToFile(todos: List<TodoItem>, file: File) {
        try {
            file.bufferedWriter().use { writer ->
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

    /**
     * 确保待办事项列表状态一致（用于调试和修复）
     */
    fun ensureTodoConsistency() {
        Log.d(TAG, "检查待办一致性: 共 ${todos.size} 条待办")

        // 检查重复ID
        val idSet = mutableSetOf<Int>()
        val duplicateIds = mutableListOf<Int>()

        todos.forEach { todo ->
            if (idSet.contains(todo.id)) {
                duplicateIds.add(todo.id)
                Log.w(TAG, "发现重复ID: ${todo.id}")
            } else {
                idSet.add(todo.id)
            }
        }

        if (duplicateIds.isNotEmpty()) {
            Log.e(TAG, "发现 ${duplicateIds.size} 个重复ID，尝试修复")
            // 移除重复项，保留最新的（基于updatedAt）
            val uniqueTodos = mutableListOf<TodoItem>()
            val groupedByUuid = todos.groupBy { it.uuid }

            groupedByUuid.forEach { (uuid, todoList) ->
                if (todoList.size > 1) {
                    Log.w(TAG, "UUID $uuid 有 ${todoList.size} 个重复项")
                    // 选择更新时间最新的
                    val latestTodo = todoList.maxByOrNull { parseTodoUpdateTime(it) }
                    latestTodo?.let { uniqueTodos.add(it) }
                } else {
                    uniqueTodos.add(todoList.first())
                }
            }

            if (uniqueTodos.size != todos.size) {
                Log.d(TAG, "修复重复项: 从 ${todos.size} 修复到 ${uniqueTodos.size}")
                todos.clear()
                todos.addAll(uniqueTodos)
                saveCurrentListTodos()
            }
        }

        // 统计状态
        val activeCount = todos.count { !it.isCompleted }
        val completedCount = todos.count { it.isCompleted }
        Log.d(TAG, "待办统计: 未完成 $activeCount 条, 已完成 $completedCount 条")
    }

    /**
     * 解析待办更新时间
     */
    private fun parseTodoUpdateTime(todo: TodoItem): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            dateFormat.parse(todo.updatedAt)?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "解析更新时间失败: ${todo.updatedAt}", e)
            0L
        }
    }

    /**
     * 打印所有列表的文件状态
     */
    fun printAllListsStatus() {
        Log.d(TAG, "=== 所有列表状态 ===")

        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val todoListsDir = File(repoDir, DIR_TODO_LISTS)

        if (!todoListsDir.exists()) {
            Log.d(TAG, "待办目录不存在")
            return
        }

        // 列出所有文件
        todoListsDir.listFiles()?.forEach { file ->
            Log.d(TAG, "文件: ${file.name}, 大小: ${file.length()} 字节")
            if (file.length() > 0) {
                val content = file.readLines().take(5) // 只显示前5行
                Log.d(TAG, "  内容预览: ${content.joinToString(" | ")}")
            }
        }

        // 打印列表信息
        val allLists = todoListManager.getAllLists()
        allLists.forEach { list ->
            val listFile = File(todoListsDir, list.fileName)
            val exists = listFile.exists()
            val size = if (exists) listFile.length() else 0
            Log.d(TAG, "列表: ${list.name}, 文件: ${list.fileName}, 存在: $exists, 大小: $size, 选中: ${list.isSelected}")
        }

        Log.d(TAG, "当前内存待办数: ${todos.size}")
    }
}