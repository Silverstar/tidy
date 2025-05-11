package com.slavabarkov.tidy.adapters

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView

/**
 * Looks up item details (specifically the key/internalId) for a given MotionEvent
 * occurring on the RecyclerView.
 */
class ImageItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {

    override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
        val view = recyclerView.findChildViewUnder(e.x, e.y)
        if (view != null) {
            val viewHolder = recyclerView.getChildViewHolder(view)
            // Check if the ViewHolder is the correct type for our adapter
            if (viewHolder is ImageAdapter.ImageViewHolder) {
                val position = viewHolder.adapterPosition
                // Check if the position is valid
                if (position != RecyclerView.NO_POSITION) {
                    // Get the stable ID (internalId) using the adapter method
                    val itemId = recyclerView.adapter?.getItemId(position)
                    // Check if the ID is valid
                    if (itemId != null && itemId != RecyclerView.NO_ID) {
                        // Return an ItemDetails object containing the position and the ID
                        return object : ItemDetails<Long>() {
                            override fun getPosition(): Int = position
                            override fun getSelectionKey(): Long = itemId
                        }
                    }
                }
            }
        }
        // Return null if no item details could be found for the event
        return null
    }
}