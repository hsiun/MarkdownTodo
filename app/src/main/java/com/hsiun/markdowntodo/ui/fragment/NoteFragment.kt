package com.hsiun.markdowntodo.ui.fragment

import android.content.Intent
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
import com.hsiun.markdowntodo.ui.adapter.NoteAdapter
import com.hsiun.markdowntodo.data.model.NoteItem
import com.hsiun.markdowntodo.data.manager.NoteManager
import com.hsiun.markdowntodo.databinding.FragmentNoteBinding
import com.hsiun.markdowntodo.ui.activity.MainActivity
import com.hsiun.markdowntodo.ui.activity.NoteEditActivity

class NoteFragment : Fragment(), NoteManager.NoteChangeListener {

    private lateinit var binding: FragmentNoteBinding
    private lateinit var adapter: NoteAdapter
    private lateinit var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper

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
                val intent = Intent(requireContext(), NoteEditActivity::class.java).apply {
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
        val callback = NoteItemTouchHelperCallback(requireContext(), binding.recyclerView) { position ->
            val note = adapter.getItemAtPosition(position)
            note?.let {
                val mainActivity = activity as? com.hsiun.markdowntodo.ui.activity.MainActivity
                mainActivity?.showDeleteNoteConfirmationDialog(it)
            }
        }
        itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(callback)
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