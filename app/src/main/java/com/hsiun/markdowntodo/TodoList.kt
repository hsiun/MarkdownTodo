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

        val fileName = generateFileName(name)
        val newList = TodoList(
            name = name,
            fileName = fileName,
            isDefault = false
        )

        todoLists.add(newList)
        saveTodoLists()

        Log.d(TAG, "创建新列表: $name, 文件: $fileName")
        return newList
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
        return "${cleanName}_todos.md"
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
}