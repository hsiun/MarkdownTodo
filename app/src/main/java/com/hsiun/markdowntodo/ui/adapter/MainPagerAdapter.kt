package com.hsiun.markdowntodo.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.hsiun.markdowntodo.ui.fragment.NoteFragment
import com.hsiun.markdowntodo.ui.fragment.TodoFragment

class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> TodoFragment()
            1 -> NoteFragment()
            else -> TodoFragment()
        }
    }
}