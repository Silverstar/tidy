/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.Manifest
import android.app.Activity
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
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog

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
    private lateinit var deleteButton: Button
    private var pendingDeleteIds: List<Long>? = null

    // Permission launcher for MANAGE_EXTERNAL_STORAGE
    @RequiresApi(Build.VERSION_CODES.R)
    // Permission launchers
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        Log.d("SearchFragment", "Storage permissions result: $permissions")
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        Log.d("SearchFragment", "READ_EXTERNAL_STORAGE: $readGranted, WRITE_EXTERNAL_STORAGE: $writeGranted")
        if (readGranted && writeGranted) {
            Toast.makeText(context, "Storage permissions granted", Toast.LENGTH_SHORT).show()
            moveButton?.isEnabled = true
        } else {
            Toast.makeText(context, "Storage permissions denied", Toast.LENGTH_LONG).show()
            moveButton?.isEnabled = false
        }
    }

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d("SearchFragment", "Manage storage result: ${result.resultCode}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Toast.makeText(context, "Full storage access granted", Toast.LENGTH_SHORT).show()
            moveButton?.isEnabled = true
        } else {
            Toast.makeText(context, "Full storage access denied", Toast.LENGTH_LONG).show()
            moveButton?.isEnabled = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestFullStoragePermission()
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val idsSuccessfullyProcessed = pendingDeleteIds // Get the IDs we were working on
            pendingDeleteIds = null // Clear the temporary list immediately
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d("SearchFragment", "Delete permission granted via IntentSender.")
                // Check if we have IDs stored (meaning this likely came from API 30+ createTrashRequest or API 29 Recoverable)
                if (!idsSuccessfullyProcessed.isNullOrEmpty()) {
                    // Assume the operation succeeded for the requested IDs (either trashed on 30+ or permission granted on 29)
                    // Call handleSuccessfulDeletion to update DB, ViewModel state, and UI
                    Log.d("SearchFragment", "Processing successful deletion/trashing for IDs: $idsSuccessfullyProcessed")
                    handleSuccessfulDeletion(idsSuccessfullyProcessed, emptyList()) // Pass empty list for failures here
                } else {
                    // This case might happen if pendingDeleteIds was null (unexpected) or empty
                    // Or potentially after an API 29 RecoverableSecurityException where we only prompted for one item.
                    // You might just show a generic success/refresh message or specific logic for API 29 retry.
                    Log.w(
                        "SearchFragment",
                        "RESULT_OK received, but no pending IDs found or specific retry logic needed."
                    )
                }
                Toast.makeText(context, "Permission granted. Please try deleting again.", Toast.LENGTH_SHORT).show()
            } else {
                Log.w("SearchFragment", "Delete permission denied.")
                Toast.makeText(context, "Deletion cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Folder picker launcher
    @RequiresApi(Build.VERSION_CODES.R)
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { moveImagesToFolder(it) }
    }


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

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        moveButton = view.findViewById(R.id.moveButton)
        deleteButton = view.findViewById(R.id.deleteButton)  // Get the delete button

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
        // Observe selection changes to show/hide Move button
        selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                val hasSelection = selectionTracker.hasSelection()
                val selectedCount = selectionTracker.selection.size()
                // Handle selection changes (show ActionMode, etc.)
                if (selectedCount > 0) {
                    // Example: Show contextual action bar with delete/share options
                    //= actionMode = activity?.startActionMode(actionModeCallback)
                    // Show and update the TextView with the count
                    moveButton?.visibility = if (selectionTracker.hasSelection()) View.VISIBLE else View.GONE
                    // Ensure deleteButton reference is not null before accessing visibility
                    if(::deleteButton.isInitialized) {
                        deleteButton.visibility = if (hasSelection) View.VISIBLE else View.GONE // Make Delete button visible
                    }
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
            moveButton?.visibility = View.GONE;
            deleteButton.visibility = View.GONE;

            // Refresh RecyclerView layout to ensure proper binding
//            recyclerView.post {
//                recyclerView.layoutManager?.requestLayout()
//            }
        }
        // Initial permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(context, "Full storage access required - redirecting to settings", Toast.LENGTH_LONG).show()
                requestFullStoragePermission()
            } else {
                Log.d("SearchFragment", "Full storage access already granted")
                moveButton?.isEnabled = true
            }
        } else {
            if (!checkStoragePermissions()) {
                Toast.makeText(context, "Storage permissions required", Toast.LENGTH_LONG).show()
                requestStoragePermissions()
            } else {
                Log.d("SearchFragment", "Storage permissions already granted")
                moveButton?.isEnabled = true
            }
        }

        // Handle Move action
        moveButton?.setOnClickListener {
            Log.d("SearchFragment", "Move button clicked")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Check for Android Q or higher
                // Check permissions (MANAGE_EXTERNAL_STORAGE for R+, standard for Q)
                if (hasStoragePermission()) {
                    folderPickerLauncher.launch(null) // Launch folder picker for Q+
                } else {
                    // Request appropriate permission (MANAGE or standard READ/WRITE)
                    requestAppropriateStoragePermissions()
                    Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Handle pre-Q devices: Show a message or implement a fallback strategy
                Toast.makeText(context, "Move feature requires Android 10 or higher", Toast.LENGTH_LONG).show()
            }
        }

        // *** Set Delete Button Click Listener ***
        deleteButton.setOnClickListener {
            val selectedIds = selectionTracker.selection.toList()
            if (selectedIds.isEmpty()) {
                Toast.makeText(context, "No images selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            //show confirmation dialog
            showDeleteConfirmationDialog(selectedIds)
        }

        return view
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // On Q, we just need standard permissions usually granted via manifest or runtime request
            checkStoragePermissions() // Reuse your existing check for READ/WRITE
        } else {
            // Below Q, this strategy isn't used, but keep check for completeness
            checkStoragePermissions()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestFullStoragePermission() {
        Log.d("SearchFragment", "Requesting MANAGE_EXTERNAL_STORAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            manageStorageLauncher.launch(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestStoragePermissions() {
        Log.d("SearchFragment", "Requesting READ/WRITE_EXTERNAL_STORAGE")
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        storagePermissionLauncher.launch(permissions)
    }
    // You might want a single function to decide which permission flow to trigger
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAppropriateStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFullStoragePermission()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Q, request standard READ/WRITE if not granted
            if (!checkStoragePermissions()) {
                requestStoragePermissions()
            }
        } else {
            // Below Q - potentially request READ/WRITE if a fallback is ever implemented
            if (!checkStoragePermissions()) {
                requestStoragePermissions()
            }
        }
    }

    private fun checkStoragePermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        Log.d("SearchFragment", "Checking permissions - READ: $readGranted, WRITE: $writeGranted")
        return readGranted && writeGranted
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save selection state
        if (::selectionTracker.isInitialized) {
            selectionTracker.onSaveInstanceState(outState)
        }
    }

// Using ContentResolver.update with RELATIVE_PATH (Requires API 29+)
    @RequiresApi(Build.VERSION_CODES.Q) // Mark function as requiring Q or higher
    private fun moveImagesToFolder(folderUri: Uri) {
        Log.d("SearchFragment", "moveImagesToFolder started (Strategy 1: Update RELATIVE_PATH)")
        val selectedIds = selectionTracker.selection.toList()
        if (selectedIds.isEmpty()) {
            Log.d("SearchFragment", "No images selected for move.")
            return
        }

        val contentResolver = requireContext().contentResolver
        val movedIds = Collections.synchronizedList(mutableListOf<Long>()) // Keep thread-safe list
        val completionCounter = AtomicInteger(selectedIds.size) // Keep completion counter

        // **Crucial Step**: Get the relative path for MediaStore from the chosen folder URI
        // This conversion can be complex and depends on where the user can pick folders.
        // This is a placeholder - you'll need a robust way to implement getRelativePathFromTreeUri.
        val targetRelativePath = getRelativePathFromTreeUri(requireContext(), folderUri)

        if (targetRelativePath == null) {
            Toast.makeText(context, "Could not determine a valid MediaStore path for the selected folder.", Toast.LENGTH_LONG).show()
            Log.e("SearchFragment", "Failed to get relative path from folderUri: $folderUri")
            return // Cannot proceed without a valid relative path
        }

        Log.d("SearchFragment", "Target MediaStore Relative Path: $targetRelativePath")

        selectedIds.forEach { imageId ->
            val sourceUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
            val values = ContentValues()

            // Prepare the ContentValues for the update operation
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
            // Optional: If you want to rename the file during the move
            // val originalDisplayName = getDisplayName(contentResolver, sourceUri) // Need a helper function for this
            // if (originalDisplayName != null) {
            //    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "moved_$originalDisplayName")
            // }

            // IMPORTANT: On Android R (API 30) and later, MediaStore *might* automatically
            // set IS_PENDING to 1 during the update. It *should* clear it upon success,
            // but it's sometimes necessary to clear it manually *after* the update if issues arise.
            // For simplicity, we'll rely on the system first. If metadata/visibility issues
            // persist, a second update might be needed:
            // val pendingValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            // contentResolver.update(sourceUri, pendingValues, null, null) // Call after successful move

            try {
                // Perform the update
                val updatedRows = contentResolver.update(sourceUri, values, null, null)

                if (updatedRows > 0) {
                    Log.d("SearchFragment", "Successfully updated RELATIVE_PATH for $sourceUri to $targetRelativePath")
                    movedIds.add(imageId) // Mark as successfully moved
                } else {
                    Log.w("SearchFragment", "Failed to update RELATIVE_PATH for $sourceUri (rows updated: $updatedRows)")
                    Toast.makeText(context, "Failed to move image ID: $imageId", Toast.LENGTH_SHORT).show()
                }

            } catch (securityException: SecurityException) {
                // Handle potential SecurityExceptions, especially RecoverableSecurityException on R+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && securityException is android.app.RecoverableSecurityException) {
                    Log.e("SearchFragment", "RecoverableSecurityException for $sourceUri", securityException)
                    // You need an ActivityResultLauncher prepared to handle IntentSender requests
                    // val intentSenderRequest = IntentSenderRequest.Builder(securityException.userAction.actionIntent.intentSender).build()
                    // recoverableSecurityExceptionLauncher.launch(intentSenderRequest) // Launch the prompt
                    // For now, just notify the user permission is needed. The move for this file failed.
                    Toast.makeText(context, "Permission needed to move file $imageId", Toast.LENGTH_LONG).show()

                } else {
                    Log.e("SearchFragment", "SecurityException moving file $imageId via update", securityException)
                    Toast.makeText(context, "Permission error moving file $imageId", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Catch other potential exceptions during the update
                Log.e("SearchFragment", "Error moving file $imageId via update", e)
                Toast.makeText(context, "Error moving file $imageId", Toast.LENGTH_SHORT).show()
            } finally {
                // Decrement counter regardless of success/failure for this file
                // and check if all operations are done to update UI
                if (completionCounter.decrementAndGet() == 0) {
                    activity?.runOnUiThread { finalizeMove(movedIds) }
                }
            }
        } // End of forEach loop

        // Initial check in case the list was empty or path was invalid
        if (selectedIds.isEmpty() || targetRelativePath == null) {
            finalizeMove(movedIds) // Update UI even if nothing was processed
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
        // Example documentId: "primary:Pictures/MyFolder" or "1234-5678:DCIM/Vacation"

        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) {
            Log.e("getRelativePath", "Could not split document ID: $documentId")
            return null // Unexpected format
        }

        val type = parts[0] // e.g., "primary" or "1234-5678" (volume identifier)
        val path = parts[1] // e.g., "Pictures/MyFolder" or "DCIM/Vacation"

        Log.d("getRelativePath", "Extracted Path: $path from Document ID: $documentId")

        // Validate if the path starts with a standard public directory MediaStore recognizes well.
        // This is crucial because RELATIVE_PATH works best with these.
        val standardDirs = listOf(
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS
            // Add others if needed
        )

        var isValidPrefix = false
        for (dir in standardDirs) {
            if (path.startsWith(dir, ignoreCase = true)) {
                isValidPrefix = true
                break
            }
        }

        if (!isValidPrefix) {
            // If the selected folder is not inside a standard directory (e.g., root of SD card),
            // using RELATIVE_PATH might be problematic or not work as expected.
            // You might need a different strategy or restrict folder selection.
            Log.w("getRelativePath", "Path '$path' does not start with a standard MediaStore directory.")
            // Returning null here enforces moving only to standard locations.
            // Alternatively, you could try returning just the path, but it might fail.
            return null
        }

        // Ensure the path ends with a forward slash '/' as required by RELATIVE_PATH
        return if (path.endsWith(File.separator)) {
            path
        } else {
            "$path/"
        }
        // Example valid return values: "Pictures/MyFolder/", "DCIM/Vacation/"
    }

    private fun finalizeMove(movedIds: List<Long>) {
        Log.d("SearchFragment", "Finalizing move operation. Moved count: ${movedIds.size}")
        val currentDataset = imageAdapter.getDataset()
        val newDataset = currentDataset.filter { !movedIds.contains(it) }
        imageAdapter.updateData(newDataset)
        selectionTracker.clearSelection()
        if (movedIds.isNotEmpty()) { // Only show toast if something was actually moved
            Toast.makeText(context, "${movedIds.size} images moved", Toast.LENGTH_SHORT).show()
        }
        // Hide progress indicator if any
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
    private fun showDeleteConfirmationDialog(selectedIds: List<Long>) {
        val itemCount = selectedIds.size
        // Determine message based on API level (Trash vs. Permanent)
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Are you sure you want to move $itemCount selected image(s) to the trash?" // API 30+ message
        } else {
            "Are you sure you want to permanently delete $itemCount selected image(s)? This action cannot be undone." // Pre-API 30 message
        }

        // Use MaterialAlertDialogBuilder if using Material Components theme (recommended)
        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage(message)
            .setPositiveButton("Delete") { dialog, which ->
                // User clicked DELETE - now proceed with the actual deletion
                Log.d("SearchFragment", "User confirmed deletion for $itemCount items.")
                initiateMediaStoreDeletion(selectedIds) // Call the function that starts the deletion process
            }
            .setNegativeButton("Cancel") { dialog, which ->
                // User clicked Cancel - do nothing, just dismiss dialog
                Log.d("SearchFragment", "User cancelled deletion.")
                dialog.dismiss()
            }
            .show() // Display the dialog
    }

    private fun initiateMediaStoreDeletion(selectedIds: List<Long>) {
        if (selectedIds.isEmpty()) return // Should be checked before calling, but good practice

        val contentResolver = requireContext().contentResolver
        val urisToDelete = selectedIds.map { id ->
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        // --- Choose method based on API level ---
        when {
            // --- API 30+ (Android 11+): Use createTrashRequest ---
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    // Request to move files to trash (setTrashedState = true)
                    val pendingIntent = MediaStore.createTrashRequest(contentResolver, urisToDelete, true)
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()

                    // *** Store the IDs before launching the request ***
                    pendingDeleteIds = selectedIds

                    // Launch the system dialog using the *same* launcher used for recoverable exceptions
                    Log.d("SearchFragment", "Launching createTrashRequest for ${urisToDelete.size} items.")
                    deleteResultLauncher.launch(intentSenderRequest)
                    // The actual DB/UI update will happen in the launcher's result callback IF RESULT_OK
                } catch (e: Exception) {
                    Log.e("SearchFragment", "Error creating or launching trash request", e)
                    Toast.makeText(context, "Failed to initiate trashing operation.", Toast.LENGTH_SHORT).show()
                    pendingDeleteIds = null // Clear pending IDs on immediate failure
                }
            }

            // --- API 29 (Android 10): Use delete() + RecoverableSecurityException ---
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                // Store IDs before starting loop
                pendingDeleteIds = selectedIds
                lifecycleScope.launch(Dispatchers.IO) {
                    handleDeletionLoop(selectedIds, urisToDelete)
                }
            }

            // --- API 28 and below: Use delete() (Permanent) ---
            else -> { // Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                lifecycleScope.launch(Dispatchers.IO) {
                    handleDeletionLoop(selectedIds, urisToDelete)
                }
            }
        }
    }
    // Helper function for the deletion loop used by API 29 and below
    // (Moved the loop logic here for clarity)
    private suspend fun handleDeletionLoop(selectedIds: List<Long>, urisToDelete: List<Uri>) {
        val contentResolver = requireContext().contentResolver
        val successfullyDeletedIds = mutableListOf<Long>()
        val failedIds = mutableListOf<Long>()
        var requiresPermission = false

        for (i in urisToDelete.indices) {
            val imageUri = urisToDelete[i]
            val imageId = selectedIds[i] // Assumes lists are aligned

            try {
                val deletedRows = contentResolver.delete(imageUri, null, null)
                if (deletedRows > 0) {
                    Log.d("SearchFragment", "Successfully deleted MediaStore (API < 30) ID: $imageId")
                    successfullyDeletedIds.add(imageId)
                } else {
                    Log.w("SearchFragment", "Failed to delete MediaStore (API < 30) ID: $imageId")
                    failedIds.add(imageId)
                }
            } catch (securityException: SecurityException) {
                // Only handle RecoverableSecurityException on Q (API 29)
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && securityException is RecoverableSecurityException) {
                    requiresPermission = true
                    try {
                        val intentSenderRequest = IntentSenderRequest.Builder(securityException.userAction.actionIntent.intentSender).build()
                        // *** Store the *single* ID needing permission (overwrites batch IDs) ***
                        // Note: This simplistic approach only handles one permission request at a time.
                        // If multiple files need permission, only the first one will be prompted for.
                        pendingDeleteIds = listOf(imageId) // Store only the ID causing the exception
                        Log.d("SearchFragment","RecoverableSecurityException for ID $imageId, launching prompt.")
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { // Must launch on Main thread
                            deleteResultLauncher.launch(intentSenderRequest)
                        }
                        break
                    } catch (e: IntentSender.SendIntentException) {
                        Log.e("SearchFragment", "Failed to launch delete intent sender (API 29) for ID: $imageId", e)
                        failedIds.add(imageId)
                        pendingDeleteIds = null // Clear pending IDs on launch failure
                    }
                } else {
                    // Non-recoverable or pre-Q security exception (likely missing WRITE_EXTERNAL_STORAGE)
                    Log.e("SearchFragment", "SecurityException (non-recoverable or API < 29) deleting ID: $imageId", securityException)
                    failedIds.add(imageId)
                }
            } catch (e: Exception) {
                Log.e("SearchFragment", "Error deleting ID: $imageId", e)
                failedIds.add(imageId)
            }
        } // End loop

        // If we completed the loop without needing permission, finalize the successful deletions
        if (!requiresPermission) {
            pendingDeleteIds = null // Clear pending IDs as the batch completed normally
            handleSuccessfulDeletion(successfullyDeletedIds, failedIds)
        }
    }

    private fun handleSuccessfulDeletion(successfullyDeletedIds: List<Long>, failedIds: List<Long>){
        // After (successful) MediaStore deletions, notify ViewModel and update UI
        if (successfullyDeletedIds.isNotEmpty()) {
            // Call the ViewModel function (on IO thread)
            mORTImageViewModel.handleSuccessfulDeletions(successfullyDeletedIds)

            // Update UI on the main thread (using viewLifecycleOwner)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                // mSearchViewModel.searchResults should be updated by the view model.
                Log.d("SearchFragment", "Updating UI directly after deletion request.")

                // Get the currently displayed list (might be search results or full list)
                val currentDisplayedList = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed() // Or however you get the default list

                // Create the new list for the adapter by filtering out deleted items
                val newListForAdapter = currentDisplayedList.filter { it !in successfullyDeletedIds }

                // Update the SearchViewModel's cache as well (important!)
                mSearchViewModel.searchResults = newListForAdapter


                imageAdapter.updateData(newListForAdapter)  // Use emptyList() as a safe default

                selectionTracker.clearSelection()

                Toast.makeText(context, "${successfullyDeletedIds.size} images deleted", Toast.LENGTH_SHORT).show()
            }
        }
        if(failedIds.isNotEmpty()){
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main){
                Toast.makeText(context, "${failedIds.size} images failed to delete", Toast.LENGTH_SHORT).show()
            }
        }
    }





}