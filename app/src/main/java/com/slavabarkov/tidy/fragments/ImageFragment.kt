/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.viewmodels.ORTImageViewModel
import com.slavabarkov.tidy.viewmodels.SearchViewModel
import java.io.File
import java.text.DateFormat


class ImageFragment : Fragment() {
    private var imageUri: Uri? = null
    private var imageId: Long? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_image, container, false)
        val bundle = this.arguments
        bundle?.let {




            imageId = it.getLong("image_id")
            imageUri = it.getString("image_uri")?.toUri()
        }

        //Get image date from image URI
        val cursor: Cursor =
            requireContext().contentResolver.query(imageUri!!, null, null, null, null)!!
        cursor.moveToFirst()
        val idx: Int = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_MODIFIED)
        val date: Long = cursor.getLong(idx) * 1000
        cursor.close()

        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        dateTextView.text = DateFormat.getDateInstance().format(date)

        val singleImageView: PhotoView = view.findViewById(R.id.singeImageView)
        Glide.with(view).load(imageUri).into(singleImageView)


        // --- *** NEW: Get and Display File Path *** ---
        val locationTextView: TextView = view.findViewById(R.id.locationTextView)
        val filePath = getPathFromUri(requireContext().contentResolver, imageUri!!)
        locationTextView.text = filePath ?: "Location not available" // Display path or fallback text
        Log.d("ImageFragment", "Image Path: $filePath") // Optional logging
        // --- *** END: Get and Display File Path *** ---

        val buttonImage2Image: Button = view.findViewById(R.id.buttonImage2Image)
        buttonImage2Image.setOnClickListener {
            imageId?.let {
                val imageIndex = mORTImageViewModel.idxList.indexOf(it)
                val imageEmbedding = mORTImageViewModel.embeddingsList[imageIndex]
                mSearchViewModel.sortByCosineDistance(
                    imageEmbedding,
                    mORTImageViewModel.embeddingsList,
                    mORTImageViewModel.idxList
                )
            }
            mSearchViewModel.fromImg2ImgFlag = true
            parentFragmentManager.popBackStack()
        }

        val buttonShare: Button = view.findViewById(R.id.buttonShare)
        buttonShare.setOnClickListener {
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, imageUri)
                type = "image/*"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
        return view
    }


    // --- *** NEW Helper Function to Get Path *** ---
    private fun getPathFromUri(contentResolver: ContentResolver, uri: Uri): String? {
        var filePath: String? = null
        // Define columns to query based on Android version
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME)
        } else {
            arrayOf(MediaStore.Images.Media.DATA)
        }

        try {
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use { // Use 'use' for automatic closing
                if (it.moveToFirst()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePathColumn = it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        val displayNameColumn = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)

                        if (relativePathColumn != -1 && displayNameColumn != -1) {
                            val relativePath = it.getString(relativePathColumn)
                            val displayName = it.getString(displayNameColumn)
                            // Combine relative path and display name. You might want to adjust this
                            // based on how you want to display it (e.g., add base storage path if known)
                            // For simplicity, just showing relative path + filename:
                            val sanitizedDisplayName = displayName?.replace("/", "_")?.replace("\\", "_") ?: "unknown_file"
                            filePath = if (relativePath.isNullOrEmpty()) {
                                sanitizedDisplayName
                            }
                            else {
                                File(relativePath, sanitizedDisplayName).path
                            }
                        } else {
                            Log.w("ImageFragment", "RELATIVE_PATH or DISPLAY_NAME column not found on API ${Build.VERSION.SDK_INT}.")
                            // Fallback attempt for Q+ if columns missing (unlikely for standard MediaStore)
                            filePath = getPathWithLegacyColumn(it)
                        }
                    } else {
                        // Legacy approach for older Android versions
                        filePath = getPathWithLegacyColumn(it)
                    }
                }
            } ?: Log.w("ImageFragment", "Path cursor is null for URI: $uri")
        } catch (e: Exception) {
            Log.e("ImageFragment", "Error querying image path for URI: $uri", e)
        }
        return filePath
    }

    // Helper for the deprecated DATA column
    private fun getPathWithLegacyColumn(cursor: Cursor) : String? {
        val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
        return if (dataColumn != -1) {
            cursor.getString(dataColumn)
        } else {
            Log.w("ImageFragment", "DATA column not found.")
            null
        }
    }
}