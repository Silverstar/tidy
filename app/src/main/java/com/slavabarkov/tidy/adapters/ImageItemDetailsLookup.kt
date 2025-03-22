package com.slavabarkov.tidy.adapters

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView

class ImageItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
    override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
        val view = recyclerView.findChildViewUnder(event.x, event.y)
        if (view != null) {
            val viewHolder = recyclerView.getChildViewHolder(view) as ImageAdapter.ImageViewHolder // Use your adapter's ViewHolder
            val position = viewHolder.adapterPosition
            if (position != RecyclerView.NO_POSITION) {
                val itemId = (recyclerView.adapter as ImageAdapter).getItemId(position)
                return object : ItemDetails<Long>() {
                    override fun getPosition(): Int {
                        return position
                    }

                    override fun getSelectionKey(): Long {
                        return itemId // Return the item ID as the key
                    }
                }
            }
        }
        return null
    }
}