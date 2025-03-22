package com.slavabarkov.tidy.adapters

import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.widget.RecyclerView

class ImageItemKeyProvider(private val adapter: ImageAdapter) : ItemKeyProvider<Long>(SCOPE_MAPPED) {
    override fun getKey(position: Int): Long {
        return adapter.getItemId(position) // get the key from the adapter
    }

    override fun getPosition(key: Long): Int {
        val position = (0 until adapter.itemCount).indexOfFirst { adapter.getItemId(it) == key }
        return if (position == -1) RecyclerView.NO_POSITION else position
    }
}