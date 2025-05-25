/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
import java.io.File
import android.provider.DocumentsContract
import android.widget.CheckBox
import android.widget.ProgressBar
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.slavabarkov.tidy.data.ImageEmbedding
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {
    private var searchText: TextView? = null
    private var searchButton: Button? = null
    private var clearButton: Button? = null
    private var moveButton: Button? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mORTTextViewModel: ORTTextViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()
    // Add SelectionTracker property
    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var imageAdapter: ImageAdapter // Single instance
    private lateinit var recyclerView: RecyclerView
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var modifyPermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var deleteButton: Button
    private var pendingDeleteIds: List<Long>? = null
    private var selectedCountTextView: TextView? = null
    private var pendingMediaStoreMoveItems: List<Pair<Uri, Long>>? = null // Pair(SourceUri, InternalId)
    private var pendingMoveDestinationUri: Uri? = null
    private var listCount = 0
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?> // Added RequiresApi below
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<Array<String>> // Added RequiresApi below
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent> // Added RequiresApi below
    private var operationProgressBar: ProgressBar? = null
    private var progressOverlay: View? = null
    // 1. Add the TextView declaration
    private var operationProgressText: TextView? = null
    private var selectAllCheckbox: CheckBox? = null

    @RequiresApi(Build.VERSION_CODES.R) // Added RequiresApi here due to launchers
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize launchers here
        initializeLaunchers() // Moved launcher initialization to a separate function
    }

    // Moved launcher initialization here for clarity
    @RequiresApi(Build.VERSION_CODES.R)
    private fun initializeLaunchers() {
        storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("SearchFragment", "Storage permissions result: $permissions")
            val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            if (readGranted) {
                moveButton?.isEnabled = true
            } else {
                Toast.makeText(context, "Storage permissions denied", Toast.LENGTH_LONG).show()
                moveButton?.isEnabled = false
            }
        }

        manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("SearchFragment", "Manage storage result: ${result.resultCode}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Toast.makeText(context, "Full storage access granted", Toast.LENGTH_SHORT).show()
                moveButton?.isEnabled = true
            } else {
                Toast.makeText(context, "Full storage access denied", Toast.LENGTH_LONG).show()
                moveButton?.isEnabled = false
            }
        }

        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val idsSuccessfullyProcessed = pendingDeleteIds // Get the IDs we were working on
            pendingDeleteIds = null // Clear the temporary list immediately
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d("SearchFragment", "Delete/Trash permission granted via IntentSender.")
                // On API 30+, this means the trash request was confirmed.
                // On API 29, this means permission for a specific item was granted.
                // We need to re-initiate the deletion for the granted item(s) or inform the user.
                // A simple approach is to inform the user and let them press delete again.
                Toast.makeText(context, "Permission granted. Please try the delete operation again.", Toast.LENGTH_LONG).show()
                // More complex: Re-trigger initiateMediaStoreDeletion(idsSuccessfullyProcessed) if needed.
            } else {
                Log.w("SearchFragment", "Delete/Trash permission denied via IntentSender.")
                Toast.makeText(context, "Operation cancelled or permission denied.", Toast.LENGTH_SHORT).show()
                // Ensure UI reflects that deletion didn't happen (e.g., selection remains)
                // updateAdapterAfterModification(emptyList(), idsSuccessfullyProcessed ?: emptyList()) // Mark original items as failed?
            }
        }

        modifyPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val itemsToProcess = pendingMediaStoreMoveItems
            val destinationUri = pendingMoveDestinationUri
            pendingMediaStoreMoveItems = null
            pendingMoveDestinationUri = null

            if (result.resultCode == Activity.RESULT_OK && itemsToProcess != null && destinationUri != null) {
                Log.d("SearchFragment", "MediaStore write/move permission granted via IntentSender for ${itemsToProcess.size} items.")
                performMediaStoreMove(itemsToProcess, destinationUri)
            } else {
                Log.w("SearchFragment", "MediaStore write/move permission denied or state error.")
                Toast.makeText(context, "Permission denied, cannot move MediaStore files.", Toast.LENGTH_SHORT).show()
                // Mark these items as failed
                if (itemsToProcess != null) {
                    handleMoveCompletion(emptyList(), itemsToProcess.map { it.second })
                }
            }
        }

        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { moveImagesToFolder(it) }
        }
    }

    // --- START: onResume with Consistency Check ---
    override fun onResume() {
        super.onResume()
        Log.d("SearchFragment", "onResume called.")

        // Get the current master list of IDs and the map from ORTViewModel
        val masterIdList = mORTImageViewModel.idxList
        val masterEmbeddingMap = mORTImageViewModel.getAllLoadedEmbeddingsMap()
        Log.d("SearchFragment", "onResume: ORT ViewModel State - idxList size: ${masterIdList.size}, embeddingMap size: ${masterEmbeddingMap.size}")

        // Get the list currently held by SearchViewModel
        var currentSearchIds = mSearchViewModel.searchResults

        // Check for consistency
        var needsReset = false
        if (currentSearchIds == null) {
            // If SearchViewModel is null, initialize it (first launch scenario)
            Log.d("SearchFragment", "onResume: SearchViewModel results are null, initializing.")
            needsReset = true
        } else {
            // If SearchViewModel has IDs, check if the *first* ID it holds
            // actually exists in the *current* master map from ORTViewModel.
            // This is a heuristic: if the first item is gone, the list is likely stale
            // due to re-indexing. Checking just the first is efficient.
            val firstId = currentSearchIds.firstOrNull()
            if (firstId != null && !masterEmbeddingMap.containsKey(firstId)) {
                Log.w("SearchFragment", "onResume: First ID ($firstId) from SearchViewModel not found in ORTViewModel map. Assuming stale data due to re-indexing.")
                needsReset = true
            } else if (firstId == null && masterIdList.isNotEmpty()) {
                // If SearchViewModel has an empty list, but ORTViewModel now has data
                Log.d("SearchFragment", "onResume: SearchViewModel has empty list but ORTViewModel has data. Resetting.")
                needsReset = true
            }
        }

        // Reset SearchViewModel if needed
        if (needsReset) {
            Log.d("SearchFragment", "onResume: Resetting SearchViewModel results to match ORTViewModel.")
            currentSearchIds = masterIdList.reversed() // Use the current default list
            mSearchViewModel.searchResults = currentSearchIds // Update the ViewModel

            // Clear search text and selection when resetting to default
            searchText?.text = null
            if(::selectionTracker.isInitialized) {
                selectionTracker.clearSelection()
                mSearchViewModel.clearSavedSelection()
            }
        } else {
            Log.d("SearchFragment", "onResume: Keeping existing results in SearchViewModel. Count: ${currentSearchIds?.size ?: "null"}")
        }

        // Fetch embeddings based on the (potentially updated) currentSearchIds
        val currentEmbeddings = getEmbeddingsForIds(currentSearchIds ?: emptyList())
        Log.d("SearchFragment", "onResume: Fetched embeddings for adapter. Count: ${currentEmbeddings.size}")

        listCount = mSearchViewModel.searchResults?.size ?: 0 // Use safe call and provide a default
        Log.d("SearchFragment", "onResume: Updated listCount to $listCount")

        // Update the adapter
        if (::imageAdapter.isInitialized) {
            imageAdapter.updateData(currentEmbeddings)
            Log.d("SearchFragment", "onResume: Adapter data updated.")
            // Optionally scroll to top if we reset the list
            if (needsReset) {
                recyclerView.scrollToPosition(0)
            }
        } else {
            Log.w("SearchFragment", "onResume: imageAdapter not initialized yet.")
        }

        // Reset the I2I flag if it was set (independent of the list reset)
        if (mSearchViewModel.fromImg2ImgFlag) {
            recyclerView.scrollToPosition(0)
            mSearchViewModel.fromImg2ImgFlag = false
            Log.d("SearchFragment", "onResume: Reset fromImg2ImgFlag to false.")
        }
        // Update the selectedCountTextView.
        // If a selection exists, updateSelectionUi() will correctly format the "selected/total" text.
        // If no selection, it will show "total Images".
        // Making it visible here ensures it shows up. updateSelectionUi() will refine the text.
        selectedCountTextView?.visibility = View.VISIBLE

        // Call updateSelectionUi() to refresh the text based on current selection
        // and the now updated listCount. This is important if returning to the fragment
        // with an existing selection.
        if (::selectionTracker.isInitialized) { // Ensure tracker is initialized
            updateSelectionUi()
        } else {
            // Fallback if tracker not ready, though updateSelectionUi will also be called in onViewCreated
            if (!selectionTracker.hasSelection()) {
                selectedCountTextView?.text = "$listCount Images"
            }
        }
    }
    // --- END: onResume with Consistency Check ---

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        Log.d("SearchFragment", "onCreateView called.")
        // INITIALIZE VIEWS
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        // Find views here (safer than relying on onResume finding them first)
        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        searchText = view.findViewById(R.id.searchText) // Use view.findViewById
        searchButton = view.findViewById(R.id.searchButton)
        moveButton = view.findViewById(R.id.moveButton)
        deleteButton = view.findViewById(R.id.deleteButton)
        clearButton = view.findViewById(R.id.clearButton)
        selectedCountTextView = view.findViewById<TextView>(R.id.selectedCountTextView)
        operationProgressBar = view.findViewById(R.id.operationProgressBar)
        progressOverlay = view.findViewById(R.id.progress_overlay)
        // 2. Find the TextView in onCreateView or onViewCreated
        operationProgressText = view.findViewById(R.id.operationProgressText)
        selectAllCheckbox = view.findViewById(R.id.selectAllCheckbox)
        // --- START: Reverted Data Initialization ---
        // Initialize searchResults ONLY if it's null in the ViewModel.
        // This preserves the existing list (e.g., search results) when the view is recreated.
        if (mSearchViewModel.searchResults == null) {
            Log.d("SearchFragment", "onCreateView: searchResults is null, initializing from ORTImageViewModel.")
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        } else {
            // If not null, it means we are returning to the fragment or the view
            // is being recreated, and we should use the existing list.
            Log.d("SearchFragment", "onCreateView: searchResults already exists in ViewModel. Using existing list. Count: ${mSearchViewModel.searchResults?.size ?: "null"}")
        }
        // --- END: Reverted Data Initialization ---

        Log.d("SearchFragment", "onCreateView: Synced searchResults in SearchViewModel with ORTViewModel. New Count: ${mSearchViewModel.searchResults?.size}")

        // Log the state *after* syncing
        Log.d("SearchFragment", "onCreateView: ORT idxList size: ${mORTImageViewModel.idxList.size}")
        Log.d("SearchFragment", "onCreateView: ORT embeddingMap size: ${mORTImageViewModel.getAllLoadedEmbeddingsMap().size}")

        // Get initial embeddings based on the freshly synced list
        val initialEmbeddingList = getEmbeddingsForIds(mSearchViewModel.searchResults ?: emptyList()) // Logging inside this function
        Log.d("SearchFragment", "onCreateView: Fetched initial embeddings. Count: ${initialEmbeddingList.size}")
        // --- End Data Initialization ---


        //STEP 1 ADAPTER CREATION
        imageAdapter =  ImageAdapter(requireContext(), initialEmbeddingList) // Pass initial data
        recyclerView.adapter = imageAdapter

        //STEP 3
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

        //--- Setup listeners that DON'T depend on restored state ---
        // Consider if mORTTextViewModel.init() needs to be called every time or just once
        mORTTextViewModel.init()

        searchButton?.setOnClickListener {
            val query = searchText?.text?.toString() ?: ""
            if (query.isBlank()) {
                Toast.makeText(context, "Please enter search text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Log.d("SearchFragment", "Performing text search for: '$query'")
            val textEmbedding: FloatArray = mORTTextViewModel.getTextEmbedding(query)
            // Perform search using the ViewModels
            mSearchViewModel.sortByCosineDistance(textEmbedding, mORTImageViewModel.embeddingsList, mORTImageViewModel.idxList)

            // Update UI after search
            if(::selectionTracker.isInitialized) selectionTracker.clearSelection()
            mSearchViewModel.clearSavedSelection()
            // Get embeddings for the NEW search results
            val searchResultEmbeddings = getEmbeddingsForIds(mSearchViewModel.searchResults ?: emptyList())
            imageAdapter.updateData(searchResultEmbeddings)
            recyclerView.scrollToPosition(0)
            Log.d("SelectionInfo", "Search button clicked. Selection cleared. Has selection now: ${selectionTracker.hasSelection()}")
        }


        clearButton?.setOnClickListener{
            Log.d("SearchFragment", "Clear button clicked.")
            searchText?.text = null
            // Reload default list and update adapter
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            val allEmbeddingsList = getEmbeddingsForIds(mSearchViewModel.searchResults ?: emptyList())
            imageAdapter.updateData(allEmbeddingsList)

            // Clear selection state
            if(::selectionTracker.isInitialized) selectionTracker.clearSelection()
            mSearchViewModel.clearSavedSelection()

            recyclerView.scrollToPosition(0)
        }

        // Set up Select All checkbox listener
        selectAllCheckbox?.setOnClickListener {
            if (selectAllCheckbox?.isChecked == true) {
                // Select all items
                val allItemIds = imageAdapter.getDataset().map { it.internalId }.toSet()
                selectionTracker.setItemsSelected(allItemIds, true)
                Log.d("SelectAll", "Selected all ${allItemIds.size} items.")
            } else {
                // Deselect all items
                selectionTracker.clearSelection()
                Log.d("SelectAll", "Deselected all items.")
            }
        }

        return view
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("LifecycleDebug", "onViewCreated called")

        // Request permissions when the view is created or becomes visible
        setupPermissionChecksAndButtons()

        // --- STEP 4: Restore Tracker State ---
        if (savedInstanceState != null) {
            try {
                if (::selectionTracker.isInitialized) {
                    selectionTracker.onRestoreInstanceState(savedInstanceState)
                    Log.d("SelectionState", "Restored selection from savedInstanceState. Count: ${selectionTracker.selection.size()}")
                }
            } catch (e: Exception) {
                Log.e("SelectionState", "Error restoring selection from savedInstanceState", e)
                if (::selectionTracker.isInitialized) selectionTracker.clearSelection()
            }
        } else {
            val savedSelection = mSearchViewModel.selectedItemIds.value
            if (!savedSelection.isNullOrEmpty()) {
                Log.d("SelectionState", "Attempting to restore selection from ViewModel. Count: ${savedSelection.size}")
                try {
                    if (::selectionTracker.isInitialized) {
                        selectionTracker.setItemsSelected(savedSelection, true)
                        Log.d("SelectionState", "Restored from ViewModel. Has Selection NOW: ${selectionTracker.hasSelection()}, Count: ${selectionTracker.selection.size()}")
                    } else {
                        Log.e("SelectionState", "Cannot restore from ViewModel, tracker not initialized.")
                    }
                } catch (e: Exception) {
                    Log.e("SelectionState", "Error restoring selection from ViewModel", e)
                    if (::selectionTracker.isInitialized) selectionTracker.clearSelection()
                }
            } else {
                Log.d("SelectionState", "No selection found in ViewModel or savedInstanceState to restore.")
            }
        }


        // --- STEP 5: Update UI based on potentially restored state ---
        updateSelectionUi()
        Log.d("SelectionState", "Initial UI update in onViewCreated complete.")

        // --- STEP 6: Add Selection Observer ---
        addSelectionObserver()
        // --- START: Observe uiOperationInProgress ---
        mSearchViewModel.uiOperationInProgress.observe(viewLifecycleOwner) { isInProgress ->
            Log.d("SearchFragment", "uiOperationInProgress changed: $isInProgress")
            progressOverlay?.visibility = if (isInProgress) View.VISIBLE else View.GONE
            operationProgressBar?.visibility = if (isInProgress) View.VISIBLE else View.GONE
            // Disable/Enable other UI elements
            recyclerView.isEnabled = !isInProgress // Example: disable RecyclerView
            searchButton?.isEnabled = !isInProgress
            clearButton?.isEnabled = !isInProgress
            // The delete/move buttons' visibility is already handled by selection,
            // but we should also consider their enabled state.
            deleteButton?.isEnabled = !isInProgress && selectionTracker.hasSelection()
            moveButton?.isEnabled = !isInProgress && selectionTracker.hasSelection() && hasStoragePermission()
            selectAllCheckbox?.isEnabled = !isInProgress // Disable select all during operation
            // Prevent interaction with RecyclerView items by making it non-clickable
            recyclerView.isClickable = !isInProgress
            recyclerView.isFocusable = !isInProgress
            // You might need to iterate through children if the above is not enough
            // or set an overlay view.
        }
        // --- Setup listeners that might depend on tracker state / UI elements ---
        moveButton?.setOnClickListener {
            Log.d("SearchFragment", "Move button clicked")
            // Check permissions first
            if (hasStoragePermission()) {
                folderPickerLauncher.launch(null) // Launch folder picker
            } else {
                requestAppropriateStoragePermissions() // Request permission if not granted
                Toast.makeText(context, "Storage permission required to move files.", Toast.LENGTH_SHORT).show()
            }
        }

        deleteButton.setOnClickListener {
            val selectedIds = selectionTracker.selection.toList()
            if (selectedIds.isEmpty()) {
                Toast.makeText(context, "No images selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showDeleteConfirmationDialog(selectedIds)
        }


        Log.d("LifecycleDebug", "onViewCreated finished")
    }
    // --- END: Observe uiOperationInProgress ---

    // --- Add onSaveInstanceState to save tracker state ---
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::selectionTracker.isInitialized) {
            try {
                selectionTracker.onSaveInstanceState(outState)
                Log.d("SelectionState", "Saved selection state to Bundle. Count: ${selectionTracker.selection.size()}")
            } catch (e: Exception) {
                Log.e("SelectionState", "Error saving selection state", e)
            }
        }
    }
    // --- End onSaveInstanceState ---

    private fun hasStoragePermission(): Boolean {
        // On R+, check for MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        // On Q and below, check for standard READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        // Note: Write permission is handled differently by SAF/MediaStore on Q+
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestFullStoragePermission() {
        Log.d("SearchFragment", "Requesting MANAGE_EXTERNAL_STORAGE")
        if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                Toast.makeText(context, "Full storage access required for moving files freely.", Toast.LENGTH_LONG).show()
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("SearchFragment", "Error launching MANAGE_ALL_FILES_ACCESS_PERMISSION intent", e)
                Toast.makeText(context, "Could not open storage settings.", Toast.LENGTH_SHORT).show()
                // Optionally request standard permissions as fallback?
                // requestStoragePermissions()
            }
        } else {
            Log.d("SearchFragment", "MANAGE_EXTERNAL_STORAGE already granted.")
            moveButton?.isEnabled = true // Ensure button is enabled if permission already granted
        }
    }

    @RequiresApi(Build.VERSION_CODES.R) // Keep RequiresApi for consistency if calling R+ specific APIs inside
    private fun requestStoragePermissions() {
        Log.d("SearchFragment", "Requesting READ_EXTERNAL_STORAGE")
        // Only need READ_EXTERNAL_STORAGE for accessing files on older APIs
        // WRITE_EXTERNAL_STORAGE is deprecated/less relevant for Q+
        storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAppropriateStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFullStoragePermission() // Request MANAGE_EXTERNAL_STORAGE on R+
        } else { // Covers Q and below
            if (!checkStoragePermissions()) { // Check if READ is granted
                requestStoragePermissions() // Request READ if not granted
            }
        }
    }

    private fun checkStoragePermissions(): Boolean { // Renamed for clarity
        val readGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        Log.d("SearchFragment", "Checking READ_EXTERNAL_STORAGE: $readGranted")
        return readGranted
    }

    // Using ContentResolver.update with RELATIVE_PATH (Requires API 29+)
    @RequiresApi(Build.VERSION_CODES.R) // Keep RequiresApi for consistency
    private fun moveImagesToFolder(destinationFolderUri: Uri) {
        Log.d("SearchFragment", "moveImagesToFolder started. Destination: $destinationFolderUri")
        mSearchViewModel.setUiOperationInProgress(true)
        operationProgressText?.visibility = View.VISIBLE
        val selectedInternalIds = selectionTracker.selection.toList()
        if (selectedInternalIds.isEmpty()) {
            Toast.makeText(context, "No items selected.", Toast.LENGTH_SHORT).show()
            return
        }

        val embeddingsToProcess = getEmbeddingsForIds(selectedInternalIds)
        val totalFiles = embeddingsToProcess.size  // Total files to move
        if (embeddingsToProcess.isEmpty()) {
            Toast.makeText(context, "Could not find data for selected items.", Toast.LENGTH_SHORT).show()
            return
        }

        val contentResolver = requireContext().contentResolver
        val mediaStoreItems = mutableListOf<Pair<Uri, Long>>() // Pair(SourceUri, InternalId)
        val documentFileItems = mutableListOf<Pair<Uri, Long>>() // Pair(SourceUri, InternalId)
        var mediaStoreRequestLaunched = false // Track if MediaStore prompt is shown

        // --- 1. Separate items by source type ---
        embeddingsToProcess.forEach { embedding ->
            determineUriFromEmbedding(embedding)?.let { sourceUri ->
                val authority = sourceUri.authority
                if (DocumentsContract.isDocumentUri(requireContext(), sourceUri) ||
                    authority?.contains("com.android.externalstorage.documents") == true) {
                    documentFileItems.add(Pair(sourceUri, embedding.internalId))
                } else if ("media".equals(authority, ignoreCase = true)) {
                    mediaStoreItems.add(Pair(sourceUri, embedding.internalId))
                } else {
                    Log.w("SearchFragment", "Unknown URI type in move: $sourceUri")
                }
            } ?: Log.w("SearchFragment", "Could not determine source URI for internalId ${embedding.internalId}")
        }
        Log.d("SearchFragment", "Separated items - MediaStore: ${mediaStoreItems.size}, DocumentFile: ${documentFileItems.size}")


        // --- 2. Handle MediaStore items (Request Permission via IntentSender) ---
        if (mediaStoreItems.isNotEmpty()) {
            val mediaStoreUrisToModify = mediaStoreItems.map { it.first }
            Log.d("SearchFragment", "Requesting write permission for ${mediaStoreUrisToModify.size} MediaStore URIs.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Use createWriteRequest for API 29+ as it's the standard way to request modification permission
                    val pendingIntent: PendingIntent =
                        MediaStore.createWriteRequest(contentResolver, mediaStoreUrisToModify)

                    if (pendingIntent != null) {
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                    pendingMediaStoreMoveItems = mediaStoreItems
                    pendingMoveDestinationUri = destinationFolderUri
                    modifyPermissionLauncher.launch(intentSenderRequest)
                    mediaStoreRequestLaunched = true
                } else {
                    Log.e("SearchFragment", "MediaStore.createWriteRequest returned null.")
                    Toast.makeText(context, "Failed to create permission request.", Toast.LENGTH_SHORT).show()
                    handleMoveCompletion(emptyList(), mediaStoreItems.map { it.second })
                }
            } else {
                    performMediaStoreMove(mediaStoreItems,destinationFolderUri)
            }
            } catch (e: Exception) {
                Log.e("SearchFragment", "Error creating or launching write request for MediaStore items", e)
                Toast.makeText(context, "Failed to request permission for MediaStore items.", Toast.LENGTH_SHORT).show()
                handleMoveCompletion(emptyList(), mediaStoreItems.map { it.second })
            }
                operationProgressText?.visibility = View.GONE
        }

        // --- 3. Handle DocumentFile items ---
        if (documentFileItems.isNotEmpty()) {
            Log.d("SearchFragment", "Processing ${documentFileItems.size} DocumentFile items for move.")
            val destinationDirDocFile = DocumentFile.fromTreeUri(requireContext(), destinationFolderUri)

            if (destinationDirDocFile == null || !destinationDirDocFile.isDirectory || !destinationDirDocFile.canWrite()) {
                Log.e("SearchFragment", "Invalid or non-writable destination folder URI: $destinationFolderUri")
                Toast.makeText(context, "Cannot write to the selected destination folder.", Toast.LENGTH_LONG).show()
                handleMoveCompletion(emptyList(), documentFileItems.map { it.second })
            } else {
                // Launch background task for file operations
                lifecycleScope.launch(Dispatchers.IO) {
                    val successfullyMovedDocIds = mutableListOf<Long>()
                    val failedDocIds = mutableListOf<Long>()

                    documentFileItems.forEachIndexed { index, (sourceUri, internalId) ->
                        try {
                            val sourceDocFile = DocumentFile.fromSingleUri(requireContext(), sourceUri)
                            if (sourceDocFile == null || !sourceDocFile.exists()) {
                                Log.w("SearchFragment", "Source DocumentFile not found or accessible: $sourceUri")
                                failedDocIds.add(internalId)
                                return@forEachIndexed
                            }

                            val sourceFileName = sourceDocFile.name ?: "file_${System.currentTimeMillis()}"
                            var moved = false

                            // Attempt DocumentsContract.moveDocument (API 24+)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                try {
                                    val sourceParentUri = sourceDocFile.parentFile?.uri ?: kotlin.run {
                                        try { DocumentsContract.buildDocumentUriUsingTree(sourceUri, DocumentsContract.getTreeDocumentId(sourceUri).substringBeforeLast('/')) }
                                        catch (e: Exception) { null }
                                    } ?: destinationDirDocFile.uri // Fallback

                                    val movedUri = DocumentsContract.moveDocument(
                                        contentResolver, sourceUri, sourceParentUri, destinationDirDocFile.uri
                                    )
                                    moved = (movedUri != null)
                                    if (moved) Log.d("SearchFragment", "Moved using DocumentsContract.moveDocument: $sourceFileName to $movedUri")
                                    else Log.w("SearchFragment", "DocumentsContract.moveDocument returned null for: $sourceFileName")

                                    withContext(Dispatchers.Main) {
                                        val progressPercentage = (((index + 1).toDouble() / totalFiles) * 100).toInt()
                                        val progressText = "$progressPercentage%"
                                        operationProgressText?.text = progressText
                                    }
                                } catch (e: Exception) {
                                    Log.w("SearchFragment", "DocumentsContract.moveDocument failed for $sourceFileName, falling back to copy/delete.", e)
                                    moved = false
                                }
                            }

                            // Fallback to Copy/Delete
                            if (!moved) {
                                Log.d("SearchFragment", "Attempting copy/delete for: $sourceFileName")
                                var targetFileName = sourceFileName
                                var counter = 1
                                while (destinationDirDocFile.findFile(targetFileName)?.exists() == true) {
                                    val nameWithoutExtension = sourceFileName.substringBeforeLast('.', sourceFileName)
                                    val extension = sourceFileName.substringAfterLast('.', "")
                                    targetFileName = if (extension.isNotEmpty()) "${nameWithoutExtension}_${counter}.${extension}" else "${nameWithoutExtension}_${counter}"
                                    counter++
                                }

                                val copiedFile = destinationDirDocFile.createFile(sourceDocFile.type ?: "application/octet-stream", targetFileName)

                                if (copiedFile != null) {
                                    var success = false
                                    try {
                                        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                            contentResolver.openOutputStream(copiedFile.uri)?.use { outputStream ->
                                                inputStream.copyTo(outputStream); success = true
                                            }
                                        }
                                        if (success) {
                                            Log.d("SearchFragment", "Successfully copied: $sourceFileName to ${copiedFile.name}")
                                            if (sourceDocFile.delete()) {
                                                Log.d("SearchFragment", "Successfully deleted original: $sourceFileName"); moved = true
                                            } else {
                                                Log.w("SearchFragment", "Failed to delete original after copy: $sourceFileName"); copiedFile.delete(); moved = false
                                            }
                                        } else {
                                            Log.e("SearchFragment", "Copy failed (streams) for: $sourceFileName"); copiedFile.delete(); moved = false
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SearchFragment", "Exception during copy/delete for $sourceFileName", e)
                                        try { copiedFile.delete() } catch (delEx: Exception) { Log.e("SearchFragment", "Failed to cleanup partially copied file", delEx)}
                                        moved = false
                                    }
                                } else {
                                    Log.e("SearchFragment", "Failed to create target file in destination: $targetFileName"); moved = false
                                }
                            }

                            if (moved) successfullyMovedDocIds.add(internalId) else failedDocIds.add(internalId)

                        } catch (e: Exception) {
                            Log.e("SearchFragment", "Error processing DocumentFile item (internalId: $internalId, uri: $sourceUri)", e)
                            failedDocIds.add(internalId)
                        }
                    } // End forEach loop

                    withContext(Dispatchers.Main) {
                        Log.d("SearchFragment", "DocumentFile move completed. Success: ${successfullyMovedDocIds.size}, Failed: ${failedDocIds.size}")
                        handleMoveCompletion(successfullyMovedDocIds, failedDocIds)

                        // Show toast only if ONLY DocumentFile operations happened
                        if (!mediaStoreRequestLaunched) {
                            val message = buildString {
                                if (successfullyMovedDocIds.isNotEmpty()) append("${successfullyMovedDocIds.size} files moved. ")
                                if (failedDocIds.isNotEmpty()) append("${failedDocIds.size} files failed.")
                            }
                            if (message.isNotBlank()) Toast.makeText(context, message.trim(), Toast.LENGTH_LONG).show()
                            // Selection cleared in handleMoveCompletion
                        }
                    }
                    withContext(Dispatchers.Main) {
                        // ... (Existing completion logic)
                        operationProgressText?.visibility = View.GONE
                    }
                } // End lifecycleScope.launch(Dispatchers.IO)
            } // End else (destination is valid)
        } // End if (documentFileItems.isNotEmpty())
    }

    // --- Function to perform the actual MediaStore move (called after permission granted) ---
    private fun performMediaStoreMove(itemsToMove: List<Pair<Uri, Long>>, destinationFolderUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val successfullyMovedIds = mutableListOf<Long>()
            val failedIds = mutableListOf<Long>()
            val contentResolver = requireContext().contentResolver
            val totalFiles = itemsToMove.size
            // Try to get a relative path suitable for MediaStore (API 29+)
            // This might fail if the destination is not a standard MediaStore location
            val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getRelativePathFromTreeUri(requireContext(), destinationFolderUri)
            } else {
                null // Relative path not applicable below Q for MediaStore moves this way
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                // --- Use MediaStore API (ContentResolver.update) for API 29+ ---
                Log.d("SearchFragment", "Attempting MediaStore move using relativePath: $relativePath")
                itemsToMove.forEachIndexed { index, (sourceUri, internalId) ->
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 1) // Mark as pending
                        }
                        // Mark as pending first
                        contentResolver.update(sourceUri, values, null, null)
                        values.clear()
                        // Set the new relative path and clear pending status
                        values.apply {
                            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        val updatedRows = contentResolver.update(sourceUri, values, null, null)
                        if (updatedRows > 0) {
                            Log.d("SearchFragment", "MediaStore update successful for ID: $internalId")
                            successfullyMovedIds.add(internalId)
                            withContext(Dispatchers.Main) {
                                val progressPercentage = (((index + 1).toDouble() / totalFiles) * 100).toInt()
                                val progressText = "$progressPercentage%"
                                operationProgressText?.text = progressText
                            }
                        } else {
                            Log.w("SearchFragment", "MediaStore update failed (0 rows) for ID: $internalId. Trying copy/delete fallback.")
                            // Attempt copy/delete as fallback if update fails
                            if (copyAndDeleteSaf(sourceUri, destinationFolderUri, contentResolver)) {
                                successfullyMovedIds.add(internalId)
                            } else {
                                failedIds.add(internalId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SearchFragment", "Error moving MediaStore item (ID: $internalId) via update. Trying copy/delete.", e)
                        // Attempt copy/delete as fallback on exception
                        if (copyAndDeleteSaf(sourceUri, destinationFolderUri, contentResolver)) {
                            successfullyMovedIds.add(internalId)
                        } else {
                            failedIds.add(internalId)
                        }
                    }
                }
            } else {
                // --- Fallback to Copy/Delete for API < 29 or if relativePath is invalid ---
                Log.d("SearchFragment", "Falling back to copy/delete for MediaStore items (API < 29 or invalid destination path).")
                val destinationDirDocFile = DocumentFile.fromTreeUri(requireContext(), destinationFolderUri)
                if (destinationDirDocFile == null || !destinationDirDocFile.canWrite()) {
                    Log.e("SearchFragment", "Cannot write to destination folder for copy/delete fallback.")
                    failedIds.addAll(itemsToMove.map { it.second }) // Mark all as failed
                } else {
                    itemsToMove.forEach { (sourceUri, internalId) ->
                        if (copyAndDeleteSaf(sourceUri, destinationFolderUri, contentResolver)) {
                            successfullyMovedIds.add(internalId)
                        } else {
                            failedIds.add(internalId)
                        }
                    }
                }
            }

            // Call completion handler on the main thread
            withContext(Dispatchers.Main) {
                handleMoveCompletion(successfullyMovedIds, failedIds)
            }
        }
    }

    // --- Helper for SAF Copy/Delete (can be used by both MediaStore fallback and DocumentFile) ---
    private fun copyAndDeleteSaf(sourceUri: Uri, destinationTreeUri: Uri, contentResolver: ContentResolver): Boolean {
        val destinationDir = DocumentFile.fromTreeUri(requireContext(), destinationTreeUri)
        if (destinationDir == null || !destinationDir.canWrite()) return false

        var sourceFileName: String? = null
        // Try getting name via ContentResolver first
        try {
            contentResolver.query(sourceUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    sourceFileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) { Log.w("SearchFragment", "Could not get display name via query for $sourceUri", e)}

        // Fallback: Try getting name via DocumentFile if it's a document URI
        if (sourceFileName == null && DocumentsContract.isDocumentUri(requireContext(), sourceUri)) {
            sourceFileName = DocumentFile.fromSingleUri(requireContext(), sourceUri)?.name
        }
        // Final fallback name
        sourceFileName = sourceFileName ?: "file_${System.currentTimeMillis()}"

        // Handle potential name collisions
        var targetFileName = sourceFileName!!
        var counter = 1
        while (destinationDir.findFile(targetFileName)?.exists() == true) {
            val nameWithoutExtension = sourceFileName!!.substringBeforeLast('.', sourceFileName!!)
            val extension = sourceFileName!!.substringAfterLast('.', "")
            targetFileName = if (extension.isNotEmpty()) "${nameWithoutExtension}_${counter}.${extension}" else "${nameWithoutExtension}_${counter}"
            counter++
        }

        // Determine MIME type
        val mimeType = contentResolver.getType(sourceUri) ?: "application/octet-stream"

        // Create target file
        val copiedFile = destinationDir.createFile(mimeType, targetFileName) ?: return false

        // Perform copy
        var success = false
        try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                contentResolver.openOutputStream(copiedFile.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream); success = true
                }
            }
            if (success) {
                // Delete original AFTER successful copy
                val deleted = try {
                    // Use appropriate delete method based on URI type
                    if (DocumentsContract.isDocumentUri(requireContext(), sourceUri)) {
                        DocumentsContract.deleteDocument(contentResolver, sourceUri)
                    } else { // Assume MediaStore or other Content URI
                        contentResolver.delete(sourceUri, null, null) > 0
                    }
                } catch (delEx: Exception) {
                    Log.e("SearchFragment", "Exception deleting original $sourceUri after copy", delEx)
                    false
                }

                if (!deleted) {
                    Log.w("SearchFragment", "Failed to delete original $sourceUri after copy.")
                    copiedFile.delete() // Clean up copied file if original couldn't be deleted
                    return false // Consider move failed if delete fails
                }
                Log.d("SearchFragment", "Successfully copied and deleted $sourceUri")
                return true // Move succeeded
            } else {
                Log.e("SearchFragment", "Copy failed (streams) for: $sourceUri")
                copiedFile.delete() // Clean up failed copy
                return false
            }
        } catch (e: Exception) {
            Log.e("SearchFragment", "Exception during copy/delete for $sourceUri", e)
            try { copiedFile.delete() } catch (delEx: Exception) { /* Ignore cleanup error */ }
            return false
        }
    }

    // --- Placeholder for handling completion ---
    private fun handleMoveCompletion(movedInternalIds: List<Long>, failedInternalIds: List<Long>) {
        mSearchViewModel.setUiOperationInProgress(false)
        // --- 1. Update Database/ViewModel ---
        if (movedInternalIds.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d("SearchFragment", "Calling ViewModel to update moved internal IDs in DB: $movedInternalIds")
                movedInternalIds.forEach { internalId ->
                    mORTImageViewModel.embeddingMoved(internalId = internalId, newTimestamp = System.currentTimeMillis())
                }
                Log.d("SearchFragment", "ViewModel update calls completed for moved items.")

                // --- 2. Update SearchViewModel's list ---
                val currentSearchResults = mSearchViewModel.searchResults ?: emptyList()
                val remainingIds = currentSearchResults.filter { it !in movedInternalIds }
                withContext(Dispatchers.Main) {
                    mSearchViewModel.searchResults = remainingIds
                    Log.d("SearchFragment", "SearchViewModel searchResults updated. New size: ${mSearchViewModel.searchResults?.size}")
                    // Trigger UI update AFTER SearchViewModel is updated
                    updateAdapterAfterModification(movedInternalIds, failedInternalIds)
                }
            }
        } else {
            // Still update UI for failures/selection clear even if nothing moved
            updateAdapterAfterModification(movedInternalIds, failedInternalIds)
        }
    }

    /**
     * Helper function to update the adapter and show toasts on the Main thread
     * after Move or Delete operations have affected the underlying data.
     * Also clears selection.
     */
    private fun updateAdapterAfterModification(successfulIds: List<Long>, failedIds: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            var uiRefreshed = false
            // Always refresh the adapter if the operation involved any items
            if (successfulIds.isNotEmpty() || failedIds.isNotEmpty()) {
                val updatedEmbeddingList = getEmbeddingsForIds(mSearchViewModel.searchResults ?: emptyList())
                Log.d("SearchFragment", "Updating adapter after modification. New list size: ${updatedEmbeddingList.size}")
                if (::imageAdapter.isInitialized) {
                    imageAdapter.updateData(updatedEmbeddingList)
                    uiRefreshed = true
                } else {
                    Log.w("SearchFragment", "Adapter not initialized in updateAdapterAfterModification")
                }
            }

            // Clear selection tracker state AFTER the operation completes (success or fail)
            if (::selectionTracker.isInitialized) {
                selectionTracker.clearSelection()
                Log.d("SearchFragment", "Selection tracker cleared after modification.")
                // ViewModel selection state is cleared by the observer automatically
            }


            // Show combined Toast message
            val successCount = successfulIds.size
            val failCount = failedIds.size
            var toastMessage = ""
            listCount = mSearchViewModel.searchResults?.size!!
            selectedCountTextView?.text = "$listCount Images"
            // Customize message based on operation (Move vs Delete) - requires knowing context
            // For now, using generic "processed"
            if (successCount > 0) {
                toastMessage += "$successCount processed. "
            }
            if (failCount > 0) {
                toastMessage += "$failCount failed."
            }
            if (toastMessage.isNotBlank()) {
                Toast.makeText(context, toastMessage.trim(), Toast.LENGTH_LONG).show()
            }
            // --- START: Ensure UI operation state is reset if not already by a more specific path ---
            // This is a fallback. Ideally, setUiOperationInProgress(false) is called
            // more precisely where each specific operation (MediaStore move, DocFile move, Delete) concludes.
            if (mSearchViewModel.uiOperationInProgress.value == true) {
                Log.d("SearchFragment", "updateAdapterAfterModification: Resetting UI operation flag as a fallback.")
                mSearchViewModel.setUiOperationInProgress(false)
            }
            // --- END: Ensure UI operation state is reset ---
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q) // Keep the API level requirement
    private fun getRelativePathFromTreeUri(context: Context, treeUri: Uri): String? {
        // Check if it's a tree URI
        if (!DocumentsContract.isTreeUri(treeUri)) {
            Log.e("getRelativePath", "Uri is not a tree URI: $treeUri")
            return null
        }

        // Get the document ID from the tree URI
        val documentId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) {
            Log.e("getRelativePath", "Could not split document ID: $documentId")
            return null
        }

        val type = parts[0]
        val path = parts[1]
        Log.d("getRelativePath", "Extracted Path: $path from Document ID: $documentId")

        val standardDirs = listOf(
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS
        )

        var isValidPrefix = false
        for (dir in standardDirs) {
            if (path.startsWith(dir, ignoreCase = true)) {
                isValidPrefix = true; break
            }
        }

        if (!isValidPrefix) {
            Log.w("getRelativePath", "Path '$path' does not start with a standard MediaStore directory.")
            return null // Enforce moving only to standard locations for MediaStore API
        }

        // Ensure the path ends with a forward slash '/'
        return if (path.endsWith(File.separator)) path else "$path/"
    }

    // Optional Helper: You might need this if renaming files
    private fun getDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        var displayName: String? = null
        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            Log.e("SearchFragment", "Error getting display name for $uri", e)
        }
        return displayName
    }

    // Show alert dialog before deletion
    private fun showDeleteConfirmationDialog(selectedIdsInternal: List<Long>) {
        val itemCount = selectedIdsInternal.size
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Are you sure you want to move $itemCount selected image(s) to the trash?"
        } else {
            "Are you sure you want to permanently delete $itemCount selected image(s)? This action cannot be undone."
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage(message)
            .setPositiveButton("Delete") { dialog, which ->
                Log.d("SearchFragment", "User confirmed deletion for $itemCount items.")
                // --- START: Set UI operation in progress ---
                mSearchViewModel.setUiOperationInProgress(true)
                operationProgressText?.visibility = View.VISIBLE
                // --- END: Set UI operation in progress ---
                initiateMediaStoreDeletion(selectedIdsInternal)
            }
            .setNegativeButton("Cancel") { dialog, which ->
                Log.d("SearchFragment", "User cancelled deletion.")
                dialog.dismiss()
            }
            .show()
    }

    private fun initiateMediaStoreDeletion(selectedInternalIds: List<Long>) {
        if (selectedInternalIds.isEmpty()){
            mSearchViewModel.setUiOperationInProgress(false) // Reset if no items
            return }

        val contentResolver = requireContext().contentResolver
        val embeddingsToDelete = getEmbeddingsForIds(selectedInternalIds)
        if (embeddingsToDelete.isEmpty()) {
            Log.w("SearchFragment", "Could not find embedding data for selected internal IDs: $selectedInternalIds")
            operationProgressText?.visibility = View.GONE
            Toast.makeText(context, "Error finding items to delete.", Toast.LENGTH_SHORT).show()
            return
        }

        val urisAndIdsToDelete = embeddingsToDelete.mapNotNull { embedding ->
            determineUriFromEmbedding(embedding)?.let { Pair(it, embedding.internalId) }
        }
        if (urisAndIdsToDelete.isEmpty()) {
            Log.w("SearchFragment", "Could not resolve URIs for selected internal IDs.")
            operationProgressText?.visibility = View.GONE
            Toast.makeText(context, "Error resolving items to delete.", Toast.LENGTH_SHORT).show()
            return
        }

        val urisOnly = urisAndIdsToDelete.map { it.first }
        pendingDeleteIds = urisAndIdsToDelete.map { it.second } // Store IDs for potential callback

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    val pendingIntent = MediaStore.createTrashRequest(contentResolver, urisOnly, true)
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                    // Store IDs before launching request - already done above
                    // pendingDeleteIds = selectedInternalIds
                    Log.d("SearchFragment", "Launching createTrashRequest (API 30+).")
                    deleteResultLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    Log.e("SearchFragment", "Error creating or launching trash request", e)
                    Toast.makeText(context, "Failed to initiate trashing operation.", Toast.LENGTH_SHORT).show()
                    pendingDeleteIds = null // Clear pending IDs on immediate failure
                    handleSuccessfulDeletion(emptyList(), selectedInternalIds) // Mark all as failed
                }
            }
            // API 29 and below: Use delete loop with RecoverableSecurityException handling
            else -> { // Covers Q and below
                // Store IDs before starting loop - already done above
                // pendingDeleteIds = selectedInternalIds
                lifecycleScope.launch(Dispatchers.IO) {
                    handleDeletionLoopApi29(urisAndIdsToDelete)
                }
            }
        }
    }

    private suspend fun handleDeletionLoopApi29(urisAndIdsToDelete: List<Pair<Uri, Long>>) {
        val contentResolver = requireContext().contentResolver
        val successfullyDeletedInternalIds = mutableListOf<Long>()
        val failedInternalIds = mutableListOf<Long>()
        var permissionNeededIntentSender: IntentSender? = null
        var permissionNeededId: Long? = null // Track ID needing permission
        val totalFiles = urisAndIdsToDelete.size
        Log.d("DeletionLoop", "Starting loop for ${urisAndIdsToDelete.size} items (API <= 29)")

        for ((index, pair) in urisAndIdsToDelete.withIndex()) {
            val (imageUri, internalId) = pair
            try {
                Log.d("DeletionLoop", "Attempting delete for InternalID: $internalId, URI: $imageUri")
                var deleted = false
                if (DocumentsContract.isDocumentUri(requireContext(), imageUri)) {
                    try {
                        deleted = DocumentsContract.deleteDocument(contentResolver, imageUri)
                        if (!deleted) Log.w("DeletionLoop", "DocumentsContract.deleteDocument returned false for $internalId / $imageUri")
                    } catch(e: Exception) {
                        Log.e("DeletionLoop", "Exception deleting document: $imageUri", e)
                    }
                } else { // Assume MediaStore or other Content URI
                    try {
                        val deletedRows = contentResolver.delete(imageUri, null, null)
                        deleted = deletedRows > 0
                    } catch (secEx: SecurityException) {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && secEx is RecoverableSecurityException) {
                            Log.w("DeletionLoop", "RecoverableSecurityException for MediaStore InternalID $internalId", secEx)
                            if (permissionNeededIntentSender == null) { // Store first encountered
                                permissionNeededIntentSender = secEx.userAction.actionIntent.intentSender
                                permissionNeededId = internalId
                            }
                            // Mark as failed for now, let prompt handle retry
                        } else {
                            throw secEx // Re-throw other security exceptions
                        }
                    }
                }

                if (deleted) {
                    Log.d("DeletionLoop", "Successfully deleted item for InternalID: $internalId")
                    successfullyDeletedInternalIds.add(internalId)
                    withContext(Dispatchers.Main) {
                        val progressPercentage = (((index + 1).toDouble() / totalFiles) * 100).toInt()
                        val progressText = "$progressPercentage%"
                        operationProgressText?.text = progressText
                    }
                } else {
                    // Add to failed list ONLY IF no permission prompt was triggered for it
                    if (internalId != permissionNeededId) {
                        Log.w("DeletionLoop", "Failed to delete item for InternalID: $internalId (URI: $imageUri)")
                        failedInternalIds.add(internalId)
                    } else {
                        Log.w("DeletionLoop", "Deletion pending permission for InternalID: $internalId")
                        // Don't add to failed list yet, wait for permission result
                    }
                }

            } catch (e: Exception) {
                Log.e("DeletionLoop", "Outer error processing delete for InternalID: $internalId", e)
                failedInternalIds.add(internalId) // Ensure added to failed list on any outer exception
            }
        } // End loop

        Log.d("DeletionLoop", "Loop finished. Success: ${successfullyDeletedInternalIds.size}, Fail: ${failedInternalIds.size}, Permission Prompt needed: ${permissionNeededIntentSender != null}")

        // --- Post-Loop Processing ---
        if (permissionNeededIntentSender != null && permissionNeededId != null) {
            try {
                val intentSenderRequest = IntentSenderRequest.Builder(permissionNeededIntentSender).build()
                // Store only the ID for which permission was requested
                // Overwrite pendingDeleteIds which might contain all initial IDs
                pendingDeleteIds = listOf(permissionNeededId)
                Log.d("DeletionLoop", "Launching prompt for the first file needing permission (InternalID: $permissionNeededId)")
                // Launch on Main thread
                withContext(Dispatchers.Main) {
                    deleteResultLauncher.launch(intentSenderRequest)
                }
            } catch (e: Exception) { // Catch SendIntentException or others
                Log.e("DeletionLoop", "Failed to launch delete intent sender for ID: $permissionNeededId", e)
                pendingDeleteIds = null // Clear pending IDs on launch failure
                failedInternalIds.add(permissionNeededId) // Mark the item requiring permission as failed now
            }
        } else {
            // No permission prompt was needed, clear any potentially stale pending IDs
            pendingDeleteIds = null
        }

        // Finalize successes/failures that didn't require a prompt *now*
        handleSuccessfulDeletion(successfullyDeletedInternalIds, failedInternalIds)
    }

    private fun handleSuccessfulDeletion(successfullyDeletedInternalIds: List<Long>, failedInternalIds: List<Long>){
        Log.d("SearchFragment", "handleSuccessfulDeletion: Success count=${successfullyDeletedInternalIds.size}, Fail count=${failedInternalIds.size}")
        // This is a key place to reset, as it's the common end-point for delete operations.
        mSearchViewModel.setUiOperationInProgress(false)
        operationProgressText?.visibility = View.GONE
        if (successfullyDeletedInternalIds.isNotEmpty()) {
            // 1. Call ViewModel to update DB and its internal lists
            lifecycleScope.launch(Dispatchers.IO) {
                mORTImageViewModel.deleteEmbeddingsByInternalId(successfullyDeletedInternalIds)
            }

            // 2. Update SearchViewModel's result list (filter out successful ones)
            val currentSearchResults = mSearchViewModel.searchResults ?: emptyList()
            mSearchViewModel.searchResults = currentSearchResults.filter { it !in successfullyDeletedInternalIds }

        }

        // 3. Update UI on Main Thread (Now handled by updateAdapterAfterModification)
        updateAdapterAfterModification(successfullyDeletedInternalIds, failedInternalIds) // Call helper
    }

    private fun addSelectionObserver() {
        if (!::selectionTracker.isInitialized) return
        selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                Log.d("SelectionState", "Observer onSelectionChanged triggered.")
                val currentSelection = selectionTracker.selection.map { it }.toSet()
                mSearchViewModel.saveSelection(currentSelection)
                Log.d("SelectionState", "Saved selection to ViewModel. Count: ${currentSelection.size}")
                updateSelectionUi()
            }
        })
        Log.d("SelectionState", "Selection observer added.")
    }

    private fun updateSelectionUi() {
        if (!::selectionTracker.isInitialized) return // Safety check
        val totalItems = imageAdapter.itemCount // Get total items from adapter
        val selectedCount = selectionTracker.selection.size()
        val hasSelection = selectedCount > 0
        val lsCount = listCount

        Log.d("SelectionUI", "Updating UI. Count: $selectedCount, HasSelection: $hasSelection")
        // Only enable move/delete buttons if an operation is NOT already in progress
        val operationInProgress = mSearchViewModel.uiOperationInProgress.value ?: false
        moveButton?.visibility = if (hasSelection) View.VISIBLE else View.GONE
        deleteButton.visibility = if (hasSelection) View.VISIBLE else View.GONE
        //selectedCountTextView?.visibility = if (hasSelection) View.VISIBLE else View.GONE

        moveButton?.isEnabled = hasSelection && !operationInProgress && hasStoragePermission()
        deleteButton?.isEnabled = hasSelection && !operationInProgress

        if (hasSelection) {
            selectedCountTextView?.text = "$selectedCount/$lsCount items selected"
            selectAllCheckbox?.visibility = View.VISIBLE // Show select all when selection is active
            selectAllCheckbox?.isChecked = (selectedCount == totalItems) // Check if all are selected
        }else {

            selectedCountTextView?.text = "$lsCount Images" // Clear text when not visible
            // selectedCountTextView?.visibility = View.GONE // Visibility handled above
            selectAllCheckbox?.visibility = View.GONE // Hide select all when no selection
            selectAllCheckbox?.isChecked = false // Ensure it's unchecked when hidden
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setupPermissionChecksAndButtons() {
        // Check permission status and update button state accordingly
        if (hasStoragePermission()) {
            Log.d("SearchFragment", "Storage permission already granted.")
            moveButton?.isEnabled = true
        } else {
            Log.d("SearchFragment", "Storage permission NOT granted.")
            moveButton?.isEnabled = false
            // Optionally request immediately, or wait for button click
            // requestAppropriateStoragePermissions()
        }
    }

    // Helper function within SearchFragment
    private fun getEmbeddingsForIds(internalIds: List<Long>): List<ImageEmbedding> {
        Log.d("SearchFragment", "getEmbeddingsForIds called. Requesting ${internalIds.size} IDs.")
        if (internalIds.isEmpty()) {
            Log.d("SearchFragment", "getEmbeddingsForIds: Requested ID list is empty, returning empty list.")
            return emptyList() // Return early if no IDs requested
        }

        // Use the map directly from the ViewModel
        val allEmbeddingsMap = mORTImageViewModel.getAllLoadedEmbeddingsMap()
        Log.d("SearchFragment", "getEmbeddingsForIds: Current map size in ORTViewModel: ${allEmbeddingsMap.size}")
        Log.d("SearchFragment", "getEmbeddingsForIds: First 5 requested IDs: ${internalIds.take(5)}")

        // Efficiently map and filter out nulls if any ID wasn't found
        val results = internalIds.mapNotNull { id ->
            val embedding = allEmbeddingsMap[id]
            if (embedding == null && internalIds.size < 20) { // Log misses only for smaller lists to avoid spam
                Log.w("SearchFragment", "getEmbeddingsForIds: Embedding NOT found in map for ID: $id")
            }
            embedding // mapNotNull filters nulls
        }
        Log.d("SearchFragment", "getEmbeddingsForIds: Found ${results.size} embeddings out of ${internalIds.size} requested.")
        return results
    }

    // Helper function to determine the URI from an ImageEmbedding object
    private fun determineUriFromEmbedding(embedding: ImageEmbedding): Uri? {
        return when {
            // Prefer Document URI if available
            !embedding.documentUri.isNullOrBlank() -> {
                try { embedding.documentUri.toUri() }
                catch (e: Exception) { Log.e("SearchFragment", "Error parsing documentUri: ${embedding.documentUri}", e); null }
            }
            // Fallback to MediaStore ID
            embedding.mediaStoreId != null -> {
                try { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, embedding.mediaStoreId) }
                catch (e: Exception) { Log.e("SearchFragment", "Error creating MediaStore URI for ID: ${embedding.mediaStoreId}", e); null }
            }
            // No usable URI found
            else -> null
        }
    }

}
