/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.slavabarkov.tidy.viewmodels.ORTImageViewModel
import com.slavabarkov.tidy.viewmodels.ORTTextViewModel
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.viewmodels.SearchViewModel
import com.slavabarkov.tidy.adapters.ImageAdapter
import com.slavabarkov.tidy.adapters.ImageItemDetailsLookup
import com.slavabarkov.tidy.adapters.ImageItemKeyProvider


class SearchFragment : Fragment() {
    private var searchText: TextView? = null
    private var searchButton: Button? = null
    private var clearButton: Button? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mORTTextViewModel: ORTTextViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()
    // Add SelectionTracker property
    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var imageAdapter: ImageAdapter // Single instance
    private lateinit var recyclerView: RecyclerView

    override fun onResume() {
        super.onResume()
        searchText = view?.findViewById(R.id.searchText)
        val recyclerView = view?.findViewById<RecyclerView>(R.id.recycler_view)

        if (mSearchViewModel.fromImg2ImgFlag) {
            searchText?.text = null
            recyclerView?.scrollToPosition(0)
            mSearchViewModel.fromImg2ImgFlag = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        val selectedCountTextView = view.findViewById<TextView>(R.id.selectedCountTextView)

        if (mSearchViewModel.searchResults == null) {
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        }
        imageAdapter =  ImageAdapter(requireContext(), emptyList())
        recyclerView.adapter = imageAdapter
        recyclerView.scrollToPosition(0)

        // Initialize Selection Tracker
        selectionTracker = SelectionTracker.Builder(
            "search-image-selection",
            recyclerView,
            ImageItemKeyProvider(imageAdapter),
            ImageItemDetailsLookup(recyclerView),
            StorageStrategy.createLongStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()

        // Pass the selection tracker to the adapter
        imageAdapter.selectionTracker = selectionTracker

        // Restore selection state if available
//        savedInstanceState?.let {
//            selectionTracker.onRestoreInstanceState(it)
//        }

        // Add selection observer
        selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                val selectedCount = selectionTracker.selection.size()
                // Handle selection changes (show ActionMode, etc.)
                if (selectedCount > 0) {
                    // Example: Show contextual action bar with delete/share options
                    //= actionMode = activity?.startActionMode(actionModeCallback)
                    // Show and update the TextView with the count
                   selectedCountTextView.visibility = View.VISIBLE
                   selectedCountTextView.text = "$selectedCount items selected"
                } else {
                    // Hide action mode if no items are selected
                    // actionMode?.finish()
                    // Hide the TextView when no items are selected
                    selectedCountTextView.visibility = View.GONE
                }
            }
        })

        mORTTextViewModel.init()

        searchText = view?.findViewById(R.id.searchText)
        searchButton = view?.findViewById(R.id.searchButton)

        // Set initial data
        if (mSearchViewModel.searchResults == null) {
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        }
        imageAdapter.updateData(mSearchViewModel.searchResults!!)

        searchButton?.setOnClickListener {
            val textEmbedding: FloatArray =
                mORTTextViewModel.getTextEmbedding(searchText?.text.toString())
            mSearchViewModel.sortByCosineDistance(textEmbedding, mORTImageViewModel.embeddingsList, mORTImageViewModel.idxList)

            imageAdapter.updateData(mSearchViewModel.searchResults!!)

            // Refresh RecyclerView layout to ensure proper binding
//            recyclerView.post {
//                recyclerView.layoutManager?.requestLayout()
//            }

            Log.d("SelectionInfo", selectionTracker.hasSelection().toString())

        }

        clearButton = view?.findViewById(R.id.clearButton)
        clearButton?.setOnClickListener{
            searchText?.text = null
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            imageAdapter.updateData(mSearchViewModel.searchResults!!)
           // Refresh RecyclerView layout to ensure proper binding
//            recyclerView.post {
//                recyclerView.layoutManager?.requestLayout()
//            }
        }
        return view
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save selection state
        if (::selectionTracker.isInitialized) {
            selectionTracker.onSaveInstanceState(outState)
        }
    }
}