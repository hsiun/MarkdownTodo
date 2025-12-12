package com.hsiun.markdowntodo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hsiun.markdowntodo.databinding.FragmentNoteBinding

class NoteFragment : Fragment(), NoteManager.NoteChangeListener {

    private lateinit var binding: FragmentNoteBinding
    private lateinit var adapter: NoteAdapter

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
                mainActivity.noteDialogManager.showEditNoteDialog(note, mainActivity)
            },
            onNoteDeleted = { note ->
                mainActivity.showDeleteNoteConfirmationDialog(note)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 设置监听器
        mainActivity.noteManager.setNoteChangeListener(this)

        // 初始化时显示空状态
        updateEmptyView()
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