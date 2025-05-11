// e.g., com/slavabarkov/tidy/utils/PreferencesHelper.kt
package com.slavabarkov.tidy.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit

object PreferencesHelper {

    private const val PREFS_NAME = "tidy_prefs"
    private const val KEY_SELECTED_FOLDER_URI = "selected_folder_uri"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedFolderUri(context: Context, uri: Uri?) {
        getPreferences(context).edit {
            putString(KEY_SELECTED_FOLDER_URI, uri?.toString())
            // Optionally store the last indexed scope here too if needed for comparison
        }
    }

    fun getSelectedFolderUri(context: Context): Uri? {
        val uriString = getPreferences(context).getString(KEY_SELECTED_FOLDER_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    // Optional: Helper to get a displayable name from the URI
    fun getFolderName(context: Context, uri: Uri): String? {
        return try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
        } catch (e: Exception) {
            // Handle potential security exceptions or invalid URIs
            null
        }
    }
}