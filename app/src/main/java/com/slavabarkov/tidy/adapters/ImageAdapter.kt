/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.adapters

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
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
        selectionTracker.clearSelection() // Reset selection state
        notifyDataSetChanged()
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.item_image)
        val checkBox: CheckBox = view.findViewById(R.id.imageCheckbox)
    }

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val adapterLayout =
            LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false)
        return ImageViewHolder(adapterLayout)
    }

    override fun getItemCount(): Int = dataset.size

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