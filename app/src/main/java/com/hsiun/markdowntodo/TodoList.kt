package com.hsiun.markdowntodo

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// 待办列表数据结构
data class TodoList(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var fileName: String = "",
    var todoCount: Int = 0,
    var activeCount: Int = 0,
    val createdAt: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date()),
    var isDefault: Boolean = false,
    var isSelected: Boolean = false
) {
    fun getDisplayName(): String {
        return if (isDefault) {
            // 默认待办显示未完成数量
            "默认待办 ($activeCount)"
        } else {
            // 其他列表显示总数
            "$name ($todoCount)"
        }
    }

    fun getDisplayText(): String {
        return if (isDefault) "默认待办" else name
    }
}

// 待办列表管理器
class TodoListManager(private val context: Context) {

    companion object {
        private const val TAG = "TodoListManager"
        private const val DEFAULT_LIST_NAME = "默认待办"
        private const val DEFAULT_FILE_NAME = "todos.md"
        private const val TODO_LISTS_FILE = "todo_lists.json"
    }

    private val todoLists = mutableListOf<TodoList>()
    private var currentListId: String = ""

    init {
        loadTodoLists()
        ensureDefaultListExists()
    }

    private fun ensureDefaultListExists() {
        if (todoLists.none { it.isDefault }) {
            val defaultList = TodoList(
                name = DEFAULT_LIST_NAME,
                fileName = DEFAULT_FILE_NAME,
                isDefault = true
            )
            todoLists.add(defaultList)
            currentListId = defaultList.id
            saveTodoLists()
        }
    }

    private fun loadTodoLists() {
        try {
            val file = File(context.filesDir, TODO_LISTS_FILE)
            if (file.exists()) {
                val json = file.readText()
                val jsonArray = org.json.JSONArray(json)

                todoLists.clear()
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val list = TodoList(
                        id = jsonObject.getString("id"),
                        name = jsonObject.getString("name"),
                        fileName = jsonObject.getString("fileName"),
                        todoCount = jsonObject.getInt("todoCount"),
                        activeCount = jsonObject.getInt("activeCount"),
                        createdAt = jsonObject.getString("createdAt"),
                        isDefault = jsonObject.getBoolean("isDefault"),
                        isSelected = jsonObject.getBoolean("isSelected")
                    )
                    todoLists.add(list)

                    if (list.isSelected) {
                        currentListId = list.id
                    }
                }

                // 如果没有选中的列表，选中默认列表
                if (currentListId.isEmpty() && todoLists.isNotEmpty()) {
                    val defaultList = todoLists.firstOrNull { it.isDefault }
                    defaultList?.let {
                        it.isSelected = true
                        currentListId = it.id
                    }
                }

                Log.d(TAG, "加载待办列表成功: ${todoLists.size} 个")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载待办列表失败", e)
        }
    }

    private fun saveTodoLists() {
        try {
            val jsonArray = org.json.JSONArray()
            todoLists.forEach { list ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", list.id)
                    put("name", list.name)
                    put("fileName", list.fileName)
                    put("todoCount", list.todoCount)
                    put("activeCount", list.activeCount)
                    put("createdAt", list.createdAt)
                    put("isDefault", list.isDefault)
                    put("isSelected", list.isSelected)
                }
                jsonArray.put(jsonObject)
            }

            val file = File(context.filesDir, TODO_LISTS_FILE)
            file.writeText(jsonArray.toString())
            Log.d(TAG, "保存待办列表成功: ${todoLists.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "保存待办列表失败", e)
        }
    }

    fun getAllLists(): List<TodoList> = todoLists.toList()

    fun getCurrentList(): TodoList? = todoLists.find { it.id == currentListId }

    fun getCurrentListFileName(): String {
        return getCurrentList()?.fileName ?: DEFAULT_FILE_NAME
    }

    fun getCurrentListName(): String {
        return getCurrentList()?.name ?: DEFAULT_LIST_NAME
    }

    fun getCurrentListId(): String = currentListId

    fun setCurrentList(listId: String): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false

        // 更新选中状态
        todoLists.forEach { it.isSelected = (it.id == listId) }
        currentListId = listId
        saveTodoLists()

        Log.d(TAG, "切换到列表: ${list.name}")
        return true
    }

    fun createList(name: String): TodoList? {
        if (name.isBlank()) {
            Log.w(TAG, "列表名称不能为空")
            return null
        }

        // 检查是否已存在同名列表
        if (todoLists.any { it.name == name && !it.isDefault }) {
            Log.w(TAG, "已存在同名列表: $name")
            return null
        }

        // 生成唯一的文件名
        val fileName = generateUniqueFileName(name)

        val newList = TodoList(
            name = name,
            fileName = fileName,
            isDefault = false
        )

        todoLists.add(newList)
        saveTodoLists()

        // 创建空文件
        val listFile = File(context.filesDir, fileName)
        if (!listFile.exists()) {
            listFile.createNewFile()
            saveEmptyTodosToFile(listFile)
        }

        Log.d(TAG, "创建新列表: $name, 文件: $fileName, ID: ${newList.id}")
        return newList
    }

    // 生成唯一的文件名
    private fun generateUniqueFileName(listName: String): String {
        val cleanName = listName.replace("[^a-zA-Z0-9\u4e00-\u9fa5]".toRegex(), "")
        var baseName = if (cleanName.isNotEmpty()) {
            "${cleanName}_todos"
        } else {
            "todos_${System.currentTimeMillis()}"
        }

        var fileName = "$baseName.md"
        var counter = 1

        // 检查文件名是否已存在
        while (todoLists.any { it.fileName == fileName } || File(context.filesDir, fileName).exists()) {
            fileName = "${baseName}_${counter}.md"
            counter++
        }

        // 如果是默认列表，使用特定的文件名
        if (listName == "默认待办") {
            fileName = "todos.md"
        }

        return fileName
    }

    // 保存空待办文件
    private fun saveEmptyTodosToFile(file: File) {
        try {
            file.bufferedWriter().use { writer ->
                writer.write("# 待办事项\n\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建空待办文件失败", e)
        }
    }

    fun deleteList(listId: String): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false

        // 不能删除默认列表
        if (list.isDefault) {
            Log.w(TAG, "不能删除默认列表")
            return false
        }

        // 如果要删除的是当前选中的列表，切换到默认列表
        if (listId == currentListId) {
            val defaultList = todoLists.find { it.isDefault }
            defaultList?.let {
                it.isSelected = true
                currentListId = it.id
            }
        }

        // 删除对应的待办文件
        val todoFile = File(context.filesDir, list.fileName)
        if (todoFile.exists()) {
            todoFile.delete()
        }

        // 删除列表记录
        todoLists.remove(list)
        saveTodoLists()

        Log.d(TAG, "删除列表: ${list.name}")
        return true
    }

    fun updateListCount(listId: String, total: Int, active: Int): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false
        list.todoCount = total
        list.activeCount = active
        saveTodoLists()
        return true
    }

    fun updateListName(listId: String, newName: String): Boolean {
        if (newName.isBlank()) return false

        val list = todoLists.find { it.id == listId } ?: return false

        // 检查是否已存在同名列表
        if (todoLists.any { it.name == newName && it.id != listId && !it.isDefault }) {
            return false
        }

        list.name = newName
        saveTodoLists()

        // 如果文件是默认的todos.md，不需要重命名文件
        if (list.fileName != DEFAULT_FILE_NAME) {
            val newFileName = generateFileName(newName)
            val oldFile = File(context.filesDir, list.fileName)
            val newFile = File(context.filesDir, newFileName)

            if (oldFile.exists()) {
                oldFile.renameTo(newFile)
                list.fileName = newFileName
                saveTodoLists()
            }
        }

        return true
    }

    private fun generateFileName(listName: String): String {
        // 使用拼音转换或简单的格式化，这里用简单处理
        val cleanName = listName.replace("[^a-zA-Z0-9\u4e00-\u9fa5]".toRegex(), "")
        return if (cleanName.isNotEmpty()) {
            "${cleanName}_todos.md"
        } else {
            // 如果清理后为空，使用默认名称
            "todos_${System.currentTimeMillis()}.md"
        }
    }

    fun getTodoFileForList(listId: String): File {
        val list = todoLists.find { it.id == listId } ?: return File(context.filesDir, DEFAULT_FILE_NAME)
        return File(context.filesDir, list.fileName)
    }

    fun getTodoFileForCurrentList(): File {
        return getTodoFileForList(currentListId)
    }

    fun cleanup() {
        todoLists.clear()
    }

    // 修改 addExistingList 方法，处理可能的重名情况
    fun addExistingList(todoList: TodoList): Boolean {
        try {
            // 检查是否已存在相同ID的列表
            if (todoLists.any { it.id == todoList.id }) {
                Log.w(TAG, "列表已存在: ${todoList.name} (ID: ${todoList.id})")
                return false
            }

            // 检查是否已存在相同文件名的列表
            val existingListWithSameFile = todoLists.find { it.fileName == todoList.fileName }
            if (existingListWithSameFile != null) {
                Log.w(TAG, "相同文件名的列表已存在: ${todoList.fileName}，列表名: ${existingListWithSameFile.name}")

                // 如果文件名相同但列表名不同，可能需要合并
                // 这里我们更新现有列表的名称
                existingListWithSameFile.name = todoList.name
                saveTodoLists()
                Log.d(TAG, "已更新列表名称: ${todoList.name}")
                return true
            }

            // 检查是否已存在相同名称的列表（非默认列表）
            if (todoLists.any { it.name == todoList.name && !it.isDefault && !todoList.isDefault }) {
                // 名称冲突，添加后缀
                var newName = "${todoList.name}_副本"
                var counter = 1
                while (todoLists.any { it.name == newName && !it.isDefault }) {
                    newName = "${todoList.name}_副本${counter}"
                    counter++
                }

                val renamedList = todoList.copy(name = newName)
                todoLists.add(renamedList)
                saveTodoLists()
                Log.d(TAG, "列表名称冲突，已重命名为: $newName")
                return true
            }

            // 创建列表文件（如果不存在）
            val listFile = File(context.filesDir, todoList.fileName)
            if (!listFile.exists()) {
                listFile.createNewFile()
                saveEmptyTodosToFile(listFile)
                Log.d(TAG, "已创建列表文件: ${todoList.fileName}")
            }

            // 添加到列表
            todoLists.add(todoList)
            saveTodoLists()

            Log.d(TAG, "已添加现有列表: ${todoList.name}, 文件: ${todoList.fileName}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "添加现有列表失败", e)
            return false
        }
    }

    // 更新列表的所有信息（不仅仅是名称）
    fun updateListInfo(listId: String, newName: String, newFileName: String, todoCount: Int, activeCount: Int): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false

        if (newName.isBlank()) return false

        // 检查是否已存在同名列表（排除自身）
        if (todoLists.any { it.name == newName && it.id != listId && !it.isDefault }) {
            return false
        }

        list.name = newName
        list.fileName = newFileName
        list.todoCount = todoCount
        list.activeCount = activeCount

        saveTodoLists()

        // 如果需要重命名文件
        if (list.fileName != newFileName && !list.isDefault) {
            val oldFile = File(context.filesDir, list.fileName)
            val newFile = File(context.filesDir, newFileName)

            if (oldFile.exists()) {
                oldFile.renameTo(newFile)
            }
        }

        return true
    }
}