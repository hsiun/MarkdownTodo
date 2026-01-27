package com.hsiun.markdowntodo.ui.adapter

import android.R
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hsiun.markdowntodo.data.model.TodoList

class TodoListSpinnerAdapter(
    context: Context,
    private val todoLists: List<TodoList>
) : ArrayAdapter<TodoList>(context, R.layout.simple_spinner_item, todoLists) {

    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.simple_spinner_item, parent, false)

        val textView = view.findViewById<TextView>(R.id.text1)

        if (isNewItem(position)) {
            // 如果是"新建"项
            textView.text = "+ 新建待办列表"
            textView.setTextColor(ContextCompat.getColor(context, com.hsiun.markdowntodo.R.color.button_fab))
            textView.textSize = 26f
        } else {
            val list = getItem(position)
            if (list != null) {
                textView.text = list.getDisplayName()
                textView.setTextColor(ContextCompat.getColor(context, R.color.black))
                textView.textSize = 26f
            }
        }

        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.simple_spinner_dropdown_item, parent, false)

        val textView = view.findViewById<TextView>(R.id.text1)

        if (isNewItem(position)) {
            // 如果是"新建"项
            textView.text = "+ 新建待办列表"
            textView.setTextColor(ContextCompat.getColor(context, com.hsiun.markdowntodo.R.color.button_fab)) // 使用主色调
            textView.setBackgroundColor(ContextCompat.getColor(context, R.color.white))
            textView.setPadding(textView.paddingLeft, 16, textView.paddingRight, 16) // 增加内边距
        } else {
            val list = getItem(position)
            if (list != null) {
                textView.text = list.getDisplayName()

                // 强制设置黑色文字
                textView.setTextColor(ContextCompat.getColor(context, R.color.black))
                textView.setBackgroundColor(ContextCompat.getColor(context, R.color.white))

                // 如果是当前选中的列表，设置为选中样式
                if (list.isSelected) {
                    textView.setTypeface(textView.typeface, Typeface.BOLD) // 加粗
                    textView.setBackgroundColor(ContextCompat.getColor(context, com.hsiun.markdowntodo.R.color.selected_background))
                }
            }
        }

        return view
    }

    override fun getCount(): Int {
        return todoLists.size + 1 // 多一个"新建"选项
    }

    override fun getItem(position: Int): TodoList? {
        return if (position < todoLists.size) {
            todoLists[position]
        } else {
            null // 最后一项是"新建"
        }
    }

    override fun getItemId(position: Int): Long {
        return if (position < todoLists.size) {
            todoLists[position].id.hashCode().toLong()
        } else {
            -1L // "新建"项的特殊ID
        }
    }

    fun isNewItem(position: Int): Boolean {
        return position == todoLists.size
    }
}