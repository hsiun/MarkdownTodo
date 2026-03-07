package com.hsiun.markdowntodo.data.manager

import android.content.Context
import android.util.Log
import com.hsiun.markdowntodo.data.model.NoteCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NoteCategoryManager(private val context: Context) {

    companion object {
        private const val TAG = "NoteCategoryManager"
        private const val GIT_REPO_DIR = "git_repo"
        private const val DIR_NOTES = "notes"
        private const val CATEGORY_FILE = "note_categories.json"
    }

    private var categories = mutableListOf<NoteCategory>()
    private var currentCategoryId: String? = null

    private val repoDir = File(context.filesDir, GIT_REPO_DIR)
    private val notesDir = File(repoDir, DIR_NOTES)
    private val categoryFile = File(repoDir, CATEGORY_FILE)

    init {
        if (!repoDir.exists()) repoDir.mkdirs()
        if (!notesDir.exists()) notesDir.mkdirs()
        loadCategories()
    }

    fun loadCategories() {
        try {
            categories.clear()
            if (categoryFile.exists() && categoryFile.length() > 0) {
                val json = categoryFile.readText()
                val jsonArray = JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    categories.add(NoteCategory(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        folderName = obj.optString("folderName", ""),
                        noteCount = obj.optInt("noteCount", 0),
                        createdAt = obj.optString("createdAt", ""),
                        isDefault = obj.optBoolean("isDefault", false),
                        isSelected = obj.optBoolean("isSelected", false)
                    ))
                }
            }

            // Ensure default category exists
            if (categories.isEmpty() || categories.none { it.isDefault }) {
                val defaultCat = NoteCategory(
                    id = "default",
                    name = "默认笔记",
                    folderName = "",
                    isDefault = true,
                    isSelected = true
                )
                categories.add(0, defaultCat)
                currentCategoryId = defaultCat.id
                saveCategories()
            } else {
                // Set current to selected or first
                currentCategoryId = categories.find { it.isSelected }?.id ?: categories.first().id
            }
            
            updateCounts()
            Log.d(TAG, "Loaded ${categories.size} categories")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load categories", e)
            // Reset to default
            categories.clear()
            categories.add(NoteCategory(id = "default", name = "默认笔记", folderName = "", isDefault = true, isSelected = true))
            currentCategoryId = "default"
            saveCategories()
        }
    }

    private fun saveCategories() {
        try {
            val jsonArray = JSONArray()
            categories.forEach { cat ->
                val obj = JSONObject()
                obj.put("id", cat.id)
                obj.put("name", cat.name)
                obj.put("folderName", cat.folderName)
                obj.put("noteCount", cat.noteCount)
                obj.put("createdAt", cat.createdAt)
                obj.put("isDefault", cat.isDefault)
                obj.put("isSelected", cat.isSelected)
                jsonArray.put(obj)
            }
            categoryFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save categories", e)
        }
    }

    fun getAllCategories(): List<NoteCategory> = categories.toList()

    fun getCurrentCategory(): NoteCategory? = categories.find { it.id == currentCategoryId }

    fun getCurrentCategoryId(): String? = currentCategoryId

    fun setCurrentCategory(categoryId: String): Boolean {
        val target = categories.find { it.id == categoryId }
        if (target == null) {
            Log.e(TAG, "Category not found: $categoryId")
            return false
        }

        categories.forEach { it.isSelected = (it.id == categoryId) }
        currentCategoryId = categoryId
        saveCategories()
        return true
    }

    fun createCategory(name: String): NoteCategory? {
        if (name.isBlank()) return null
        // Check duplicate
        if (categories.any { it.name == name }) {
            Log.w(TAG, "Category already exists: $name")
            return null
        }

        val folderName = name.trim().replace(" ", "_")
        
        // Create folder
        val folder = File(notesDir, folderName)
        if (!folder.exists()) folder.mkdirs()

        val newCat = NoteCategory(
            name = name.trim(),
            folderName = folderName,
            isDefault = false,
            isSelected = false
        )
        categories.add(newCat)
        saveCategories()
        return newCat
    }

    fun updateCategoryCount(categoryId: String, count: Int) {
        val cat = categories.find { it.id == categoryId }
        if (cat != null) {
            cat.noteCount = count
            saveCategories()
        }
    }
    
    fun updateCounts() {
        // Count files in each folder
        categories.forEach { cat ->
            val folder = if (cat.folderName.isEmpty()) notesDir else File(notesDir, cat.folderName)
            val count = if (folder.exists() && folder.isDirectory) {
                folder.listFiles()?.count { it.isFile && it.name.endsWith(".md") } ?: 0
            } else 0
            cat.noteCount = count
        }
        saveCategories()
    }
}
