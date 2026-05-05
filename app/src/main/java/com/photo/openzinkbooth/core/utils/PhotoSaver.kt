/*
 * openZinkBooth
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.photo.openzinkbooth.core.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// PhotoSaver – saves a Bitmap as JPEG to a SAF tree Uri.
//
// Saving is opt-in: only happens when the user has configured a storage
// location in Settings. If no Uri is set, save() returns null immediately.
// ---------------------------------------------------------------------------

object PhotoSaver {

    private const val SUBFOLDER = "openZinkBooth"
    private const val TAG       = "PhotoSaver"

    /**
     * Saves [bitmap] as JPEG and returns the [Uri] of the saved file,
     * or null if no [storageUri] is configured or on failure.
     *
     * Saving is intentionally opt-in: if [storageUri] is null, nothing is
     * written and null is returned. The caller (ViewModel) checks whether
     * a storage location has been set before calling this.
     */
    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        storageUri: Uri?
    ): Uri? = withContext(Dispatchers.IO) {
        storageUri ?: return@withContext null
        val filename = buildFilename()
        try {
            saveToSafUri(context, bitmap, storageUri, filename)
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to save photo: ${e.message}", e)
            null
        }
    }

    // ── SAF (storage Uri from Settings) ─────────────────────────────────────
    // Uses ContentResolver directly to create a child document inside the
    // tree Uri; avoids the androidx.documentfile dependency.

    private fun saveToSafUri(
        context: Context,
        bitmap: Bitmap,
        treeUri: Uri,
        filename: String
    ): Uri? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

        val childUri = android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            docUri,
            "image/jpeg",
            filename
        ) ?: return null

        context.contentResolver.openOutputStream(childUri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: throw IOException("openOutputStream returned null for $childUri")

        LogManager.d(TAG, "Saved to SAF: $childUri")
        return childUri
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildFilename(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "ZINK_$ts.jpg"
    }
}