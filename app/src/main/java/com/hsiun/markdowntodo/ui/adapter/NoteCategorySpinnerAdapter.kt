package com.hsiun.markdowntodo.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.hsiun.markdowntodo.R
import com.hsiun.markdowntodo.data.model.NoteCategory

class NoteCategorySpinnerAdapter(
    context: Context,
    categories: List<NoteCategory>
) : ArrayAdapter<NoteCategory>(context, 0, categories) {

    // 添加一个特殊项作为"新建列表"按钮
    private val items = categories.toMutableList()
    
    // 标记最后一个位置是否是新建项
    fun isNewItem(position: Int): Boolean {
        return position == count - 1
    }

    override fun getCount(): Int {
        // 总数 = 实际列表数 + 1个新建按钮
        return items.size + 1
    }

    override fun getItem(position: Int): NoteCategory? {
        if (position < items.size) {
            return items[position]
        }
        return null
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, true)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, false)
    }

    private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup, isCollapsed: Boolean): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            R.layout.item_todo_list_spinner, parent, false
        )
        
        val textView = view.findViewById<TextView>(R.id.listNameText)
        
        if (position < items.size) {
            val category = items[position]
            if (isCollapsed) {
                // 折叠状态（显示在Toolbar上），字体大一点，黑色
                textView.text = category.getDisplayName()
                textView.textSize = 26f
                textView.setTextColor(Color.BLACK)
                textView.paint.isFakeBoldText = true // 加粗
            } else {
                // 展开状态（下拉菜单中），普通大小
                textView.text = category.getDisplayName()
                textView.textSize = 16f
                
                if (category.isSelected) {
                    textView.setTextColor(Color.parseColor("#865EDC")) // 选中颜色
                    textView.paint.isFakeBoldText = true
                } else {
                    textView.setTextColor(Color.BLACK)
                    textView.paint.isFakeBoldText = false
                }
            }
        } else {
            // 最后一个项：新建分类
            textView.text = "+ 新建笔记分类"
            if (isCollapsed) {
                textView.textSize = 26f
                textView.paint.isFakeBoldText = true
            } else {
                textView.textSize = 16f
                textView.paint.isFakeBoldText = false
            }
            textView.setTextColor(Color.parseColor("#865EDC"))
        }
        
        return view
    }
}
