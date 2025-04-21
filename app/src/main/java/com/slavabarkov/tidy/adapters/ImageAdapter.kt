/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.FragmentTransaction
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.slavabarkov.tidy.fragments.ImageFragment
import com.slavabarkov.tidy.MainActivity
import com.slavabarkov.tidy.R


class ImageAdapter(private val context: Context, initialDataset: List<Long>) :
    RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {
    private var dataset: List<Long> = initialDataset // Mutable dataset
    private val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    lateinit var selectionTracker: SelectionTracker<Long>

    init {
        setHasStableIds(true) // Enable stable IDs
    }

    fun updateData(newDataset: List<Long>) {
        dataset = newDataset
        Log.d("ImageAdapter", "updateData called. New size: ${newDataset.size}. Selection NOT cleared here.") // Add log
       //selectionTracker.clearSelection() // Reset selection state
        notifyDataSetChanged()
    }

    fun getDataset(): List<Long> = dataset // Add this to access dataset

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.item_image)
        val checkBox: CheckBox = view.findViewById(R.id.imageCheckbox)
        val enlargeButton: ImageButton = view.findViewById(R.id.enlarge_button)
    }

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val adapterLayout =
            LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false)
        return ImageViewHolder(adapterLayout)
    }

    override fun getItemCount(): Int = dataset.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = dataset[position]
        val imageUri = Uri.withAppendedPath(uri, item.toString())
        val checkBox = holder.checkBox

        Glide.with(context).load(imageUri).thumbnail().into(holder.imageView)

        // Selection State Handling
        holder.itemView.isActivated = selectionTracker.isSelected(item) // Use the item ID directly

        checkBox.setOnClickListener {
            if (checkBox.isChecked) {
                selectionTracker.select(item)
            } else {
                selectionTracker.deselect(item)
            }
        }

        holder.imageView.setOnClickListener {
            if (selectionTracker.hasSelection()) {
                // If selection is active, toggle selection
                if (selectionTracker.isSelected(item)) {
                    selectionTracker.deselect(item)
                } else {
                    selectionTracker.select(item)
                }

            } else {
                val arguments = Bundle()
                arguments.putLong("image_id", item)
                arguments.putString("image_uri", imageUri.toString())


                val transaction: FragmentTransaction =
                    (context as MainActivity).supportFragmentManager.beginTransaction()

                val fragment = ImageFragment()
                fragment.arguments = arguments
                transaction.addToBackStack("search_fragment")
                transaction.replace(R.id.fragmentContainerView, fragment)
                transaction.addToBackStack("image_fragment")
                transaction.commit()
            }

        }

        holder.imageView.setOnLongClickListener {
            if (!selectionTracker.hasSelection()) {
                selectionTracker.select(item)
                return@setOnLongClickListener true // Consume the long click
            }
            false
        }
        // Add SuppressLint for the warning we are structurally addressing

        holder.enlargeButton.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Ask parent not to intercept
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    // Return false: We handled the flag, but let event continue
                    false
                }
                MotionEvent.ACTION_UP -> {
                    // Call performClick when touch gesture completes UP
                    // This triggers the OnClickListener and accessibility events
                    view.performClick()
                    // Reset the disallow flag
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    // Return true: Indicate we handled the UP here in the touch listener context
                    // (Though performClick runs the OnClickListener logic)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Reset the disallow flag
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    false
                }
                else -> {
                    // Don't consume other events like MOVE
                    false
                }
            }
        }
        // Define the navigation logic (can use explicit label for early return)
        // Define the navigation logic with detailed logs
        val performNavigation: (View) -> Unit = navLambda@{ view ->
            Log.d("ClickListenerDebug", "==> performNavigation called. HasSelection: ${selectionTracker.hasSelection()}")

            val uriString = imageUri?.toString()
            if (uriString == null) {
                Log.e("ClickListenerDebug", "performNavigation: Image URI is null. Exiting.")
                Toast.makeText(context, "Error: Image data missing.", Toast.LENGTH_SHORT).show()
                return@navLambda // Exit lambda
            }
            Log.d("ClickListenerDebug", "performNavigation: URI is valid: $uriString")

            try {
                Log.d("ClickListenerDebug", "performNavigation: Inside try block.")
                // Ensure context is MainActivity (or use NavController if you switch)
                if (context is MainActivity) {
                    Log.d("ClickListenerDebug", "performNavigation: Context is MainActivity.")
                    val arguments = Bundle()
                    arguments.putLong("image_id", item)
                    arguments.putString("image_uri", uriString)
                    Log.d("ClickListenerDebug", "performNavigation: Arguments created: $arguments")

                    val transaction: FragmentTransaction =
                        context.supportFragmentManager.beginTransaction()
                    Log.d("ClickListenerDebug", "performNavigation: FragmentTransaction begun.")

                    val fragment = ImageFragment()
                    fragment.arguments = arguments
                    Log.d("ClickListenerDebug", "performNavigation: ImageFragment created with args.")

                    transaction.replace(R.id.fragmentContainerView, fragment)
                    Log.d("ClickListenerDebug", "performNavigation: replace called.")

                    transaction.addToBackStack("image_fragment")
                    Log.d("ClickListenerDebug", "performNavigation: addToBackStack called.")

                    // Use commit() first, only use commitAllowingStateLoss if absolutely necessary and understand the implications
                    transaction.commit()
                    // transaction.commitAllowingStateLoss()
                    Log.d("ClickListenerDebug", "performNavigation: commit() called.")

                } else {
                    Log.e("ClickListenerDebug", "performNavigation: Context is NOT MainActivity.")
                    Toast.makeText(context, "Navigation Error (Context)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ClickListenerDebug", "performNavigation: Exception during fragment transaction.", e)
                Toast.makeText(context, "Error opening image.", Toast.LENGTH_SHORT).show()
            }
            Log.d("ClickListenerDebug", "<== performNavigation finished.")
        }


        // Set the OnClickListener for the enlarge button
        holder.enlargeButton.setOnClickListener { clickedView ->
            Log.d("ClickListenerDebug", "EnlargeButton OnClickListener triggered for item $item")
            Log.d("ClickListenerDebug", "*******************************************")
            Log.d("ClickListenerDebug", "EnlargeButton OnClickListener triggered!")
            Log.d("ClickListenerDebug", "Calling performNavigation...")
            Log.d("ClickListenerDebug", "*******************************************")
            // *** Use view.post to delay the navigation ***
                performNavigation(clickedView) // Call the navigation logic
        }
        // *** END Listener Setup ***


        if (selectionTracker.hasSelection()) {
            checkBox.visibility = View.VISIBLE
            checkBox.isChecked = selectionTracker.isSelected(item)
        } else {
            checkBox.visibility = View.GONE
            checkBox.isChecked = false
        }
    }

    override fun getItemId(position: Int): Long {
        return dataset[position] // Directly use the item ID from your dataset
    }


}