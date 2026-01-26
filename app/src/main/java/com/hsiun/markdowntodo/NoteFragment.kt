package com.hsiun.markdowntodo

import com.hsiun.markdowntodo.NoteItem
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.databinding.FragmentNoteBinding

class NoteFragment : Fragment(), NoteManager.NoteChangeListener {

    private lateinit var binding: FragmentNoteBinding
    private lateinit var adapter: NoteAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 获取MainActivity实例
        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            Toast.makeText(requireContext(), "初始化失败", Toast.LENGTH_SHORT).show()
            return
        }

        // 初始化适配器
        adapter = NoteAdapter(
            mutableListOf(),
            onNoteClicked = { note ->
                // 启动编辑页面
                val intent = android.content.Intent(requireContext(), NoteEditActivity::class.java).apply {
                    putExtra("uuid", note.uuid)
                    putExtra("isNewNote", false)
                }
                startActivity(intent)
            },
            onNoteDeleted = { note ->
                mainActivity.showDeleteNoteConfirmationDialog(note)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 设置左滑删除功能
        setupSwipeToDelete()

        // 设置监听器
        mainActivity.noteManager.setNoteChangeListener(this)

        // 初始化时显示空状态
        updateEmptyView()
    }
    
    /**
     * 设置左滑删除功能
     */
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
                    val note = adapter.getNoteAtPosition(position)
                    note?.let {
                        // 先恢复item位置
                        adapter.notifyItemChanged(position)
                        // 调用MainActivity的删除确认对话框
                        val mainActivity = activity as? MainActivity
                        mainActivity?.showDeleteNoteConfirmationDialog(it)
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

                        // 绘制删除文字
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
        // 确保每次显示时都加载数据
        loadNotes()
    }

    private fun loadNotes() {
        val mainActivity = activity as? MainActivity ?: return

        if (::adapter.isInitialized) {
            adapter.updateNotes(mainActivity.noteManager.getAllNotes())
            updateEmptyView()
        }
    }

    private fun updateEmptyView() {
        if (!::adapter.isInitialized) {
            return
        }

        val hasNotes = adapter.itemCount > 0
        binding.emptyView.visibility = if (hasNotes) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasNotes) View.VISIBLE else View.GONE
    }

    // NoteManager.NoteChangeListener 实现
    override fun onNotesChanged(notes: List<NoteItem>) {
        requireActivity().runOnUiThread {
            if (::adapter.isInitialized) {
                adapter.updateNotes(notes)
                updateEmptyView()
            }
        }
    }

    override fun onNoteAdded(note: NoteItem) {
        requireActivity().runOnUiThread {
            val mainActivity = activity as? MainActivity ?: return@runOnUiThread

            if (::adapter.isInitialized) {
                adapter.updateNotes(mainActivity.noteManager.getAllNotes())
                updateEmptyView()
            }
        }
    }

    override fun onNoteUpdated(note: NoteItem) {
        requireActivity().runOnUiThread {
            val mainActivity = activity as? MainActivity ?: return@runOnUiThread

            if (::adapter.isInitialized) {
                adapter.updateNotes(mainActivity.noteManager.getAllNotes())
                updateEmptyView()
            }
        }
    }

    override fun onNoteDeleted(note: NoteItem) {
        requireActivity().runOnUiThread {
            if (::adapter.isInitialized) {
                adapter.removeNote(note)
                updateEmptyView()
            }
        }
    }

    override fun onNoteError(message: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}