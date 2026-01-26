package com.hsiun.markdowntodo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 待办列表管理器
class TodoListManager(private val context: Context) {

    companion object {
        private const val TAG = "TodoListManager"
        private const val DEFAULT_LIST_NAME = "默认待办"
        private const val DEFAULT_FILE_NAME = "todos.md"
        private const val GIT_REPO_DIR = "git_repo"
        private const val DIR_TODO_LISTS = "todo_lists"
        private const val TODO_LISTS_FILE = "todo_lists.json"
        // 新增：Git仓库中的元数据文件名
        private const val FILE_METADATA = "metadata.json"
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
        val currentList = getCurrentList()
        return if (currentList != null) {
            currentList.fileName
        } else {
            // 如果没有当前列表，检查是否有多条记录
            val defaultList = todoLists.firstOrNull { it.isDefault }
            defaultList?.fileName ?: DEFAULT_FILE_NAME
        }
    }

    fun getCurrentListName(): String {
        return getCurrentList()?.name ?: DEFAULT_LIST_NAME
    }

    fun getCurrentListId(): String = currentListId

    fun setCurrentList(listId: String): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false

        Log.d(TAG, "设置当前列表: ${list.name} (ID: $listId)")

        // 验证只有一个列表被选中
        todoLists.forEach {
            it.isSelected = (it.id == listId)
            Log.d(TAG, "列表 ${it.name}: isSelected = ${it.isSelected}")
        }

        currentListId = listId
        saveTodoLists()

        // 新增：立即保存到 Git 目录
        saveTodoListsMetadataToGit()

        Log.d(TAG, "当前列表已设置为: ${list.name}")
        return true
    }
    // 新增：保存列表元数据到 Git 目录
    private fun saveTodoListsMetadataToGit() {
        try {
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)

            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            // 保存元数据到 metadata.json 文件
            val metadataFile = File(todoListsDir, FILE_METADATA)
            saveTodoListsMetadata(todoLists, metadataFile)

            Log.d(TAG, "已保存列表元数据到 Git 目录")
        } catch (e: Exception) {
            Log.e(TAG, "保存列表元数据到 Git 目录失败", e)
        }
    }

    // 新增：保存列表元数据到文件
    fun saveTodoListsMetadata(todoLists: List<TodoList>, file: File) {
        try {
            Log.d(TAG, "保存列表元数据到文件: ${file.absolutePath}")

            val jsonArray = JSONArray()

            todoLists.forEach { list ->
                val jsonObject = JSONObject().apply {
                    put("id", list.id)
                    put("name", list.name)
                    put("fileName", list.fileName)
                    put("todoCount", list.todoCount)
                    put("activeCount", list.activeCount)
                    put("createdAt", list.createdAt)
                    put("isDefault", list.isDefault)
                    put("isSelected", list.isSelected)
                    Log.d(TAG, "保存列表: ${list.name}, isSelected: ${list.isSelected}")
                }
                jsonArray.put(jsonObject)
            }

            file.writeText(jsonArray.toString())
            Log.d(TAG, "列表元数据保存成功: ${todoLists.size} 个列表")
        } catch (e: Exception) {
            Log.e(TAG, "保存列表元数据失败", e)
            throw e
        }
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

        // 在Git目录中创建空文件
        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val todoListsDir = File(repoDir, DIR_TODO_LISTS)
        if (!todoListsDir.exists()) {
            todoListsDir.mkdirs()
        }

        val listFile = File(todoListsDir, fileName)
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
        while (todoLists.any { it.fileName == fileName } ||
            File(context.filesDir, "$GIT_REPO_DIR/$DIR_TODO_LISTS/$fileName").exists()) {
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


    fun updateListCount(listId: String, total: Int, active: Int): Boolean {
        val list = todoLists.find { it.id == listId } ?: return false
        list.todoCount = total
        list.activeCount = active
        saveTodoLists()
        return true
    }


    fun getTodoFileForList(listId: String): File {
        val list = todoLists.find { it.id == listId } ?: return getDefaultTodoFile()
        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val todoListsDir = File(repoDir, DIR_TODO_LISTS)
        return File(todoListsDir, list.fileName)
    }

    private fun getDefaultTodoFile(): File {
        val repoDir = File(context.filesDir, GIT_REPO_DIR)
        val todoListsDir = File(repoDir, DIR_TODO_LISTS)
        return File(todoListsDir, DEFAULT_FILE_NAME)
    }

    fun getTodoFileForCurrentList(): File {
        return getTodoFileForList(currentListId)
    }

    fun cleanup() {
        todoLists.clear()
        // 清空列表时同时重置当前列表ID，避免出现“悬空ID”导致 getCurrentList() 返回 null
        currentListId = ""
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

            // 确保Git目录存在
            val repoDir = File(context.filesDir, GIT_REPO_DIR)
            val todoListsDir = File(repoDir, DIR_TODO_LISTS)
            if (!todoListsDir.exists()) {
                todoListsDir.mkdirs()
            }

            // 创建列表文件（如果不存在）
            val listFile = File(todoListsDir, todoList.fileName)
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

}