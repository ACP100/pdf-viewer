package com.pdfviewer.app.ui.home

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class DeviceFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val folder: String)

object DeviceFiles {
    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun scan(context: Context): List<DeviceFile> {
        val out = ArrayList<DeviceFile>()
        val base = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            @Suppress("DEPRECATION") MediaStore.Files.FileColumns.DATA,
        )
        val sel = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("application/pdf", "%.pdf")
        runCatching {
            context.contentResolver.query(base, cols, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(4).orEmpty()
                    // Older MediaStore versions leave DISPLAY_NAME empty for documents.
                    val name = c.getString(1)?.takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
                    val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
                    out += DeviceFile(ContentUris.withAppendedId(base, id), name, c.getLong(2), c.getLong(3) * 1000, folder)
                }
            }
        }
        return out
    }
}
