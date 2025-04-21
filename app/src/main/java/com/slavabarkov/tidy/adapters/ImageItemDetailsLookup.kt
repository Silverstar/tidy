package com.slavabarkov.tidy.adapters

import android.graphics.Rect // *** Import Rect ***
import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView

class ImageItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
    override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
        val view = recyclerView.findChildViewUnder(event.x, event.y)
        if (view != null) {
            val viewHolder = recyclerView.getChildViewHolder(view)
            // Check if the ViewHolder is the correct type
            if (viewHolder is ImageAdapter.ImageViewHolder) {

                // *** START FIX: Check if the click is on the enlarge button ***
                val enlargeButton = viewHolder.enlargeButton // Get button from ViewHolder
                val buttonRect = Rect()
                // Get the hit rectangle of the button in the coordinate system of the RecyclerView item view
                enlargeButton.getHitRect(buttonRect)

                // Check if the MotionEvent coordinates (relative to the item view) are inside the button's hit rectangle
                if (buttonRect.contains(event.x.toInt() - view.left, event.y.toInt() - view.top)) {
                    // If the click is inside the button, return null to prevent selection
                    return null
                }
                // *** END FIX ***


                // If the click was NOT on the button, proceed with getting item details
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // Ensure the adapter is the correct type before accessing getItemId
                    val adapter = recyclerView.adapter
                    if (adapter is ImageAdapter) {
                        val itemId = adapter.getItemId(position)
                        return object : ItemDetails<Long>() {
                            override fun getPosition(): Int {
                                return position
                            }

                            override fun getSelectionKey(): Long {
                                return itemId // Return the item ID as the key
                            }

                            // Optional: If you need finer control based on touch area later
                            // override fun inSelectionHotspot(e: MotionEvent): Boolean {
                            //     return true // Or specific logic if needed
                            // }

                            // Optional: If you need drag handles later
                            // override fun inDragRegion(e: MotionEvent): Boolean {
                            //     return false // Or specific logic if needed
                            // }
                        }
                    }
                }
            }
        }
        // If no valid item view or details found (or click was on button), return null
        return null
    }
}