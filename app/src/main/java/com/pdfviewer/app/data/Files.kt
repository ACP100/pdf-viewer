package com.pdfviewer.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

object Files {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "document.pdf").name
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        if (name.isNullOrBlank() && uri.authority == "media") {
            // MediaStore rows for documents may lack a display name on older Android versions.
            runCatching {
                @Suppress("DEPRECATION")
                context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)?.substringAfterLast('/')
                }
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun size(context: Context, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "").length()
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        }
        return size
    }

    fun workDir(context: Context): File = File(context.cacheDir, "docs").apply { mkdirs() }
    fun tempDir(context: Context): File = File(context.cacheDir, "tmp").apply { mkdirs() }
    fun signatureDir(context: Context): File = File(context.filesDir, "signatures").apply { mkdirs() }
    fun stampDir(context: Context): File = File(context.filesDir, "stamps").apply { mkdirs() }

    /** Copies the document into the app's cache so pdfium and PdfBox can use random file access. */
    fun copyToWork(context: Context, uri: Uri): File {
        val target = File(workDir(context), Library.key(uri.toString()) + ".pdf")
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            val input = if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).inputStream()
            else context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            if (target.exists() && target.length() > 0) return target // offline copy from earlier session
            throw e
        }
        return target
    }

    fun copyFromUri(context: Context, uri: Uri, target: File): File {
        context.contentResolver.openInputStream(uri)!!.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        return target
    }

    /** Writes [file] over [uri]. Returns false if the provider refuses writes. */
    fun writeBack(context: Context, file: File, uri: Uri): Boolean = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            file.copyTo(File(uri.path!!), overwrite = true); return true
        }
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: return false
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        true
    }.getOrDefault(false)

    fun canWrite(context: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "").canWrite()
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } ||
            context.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun shareUri(context: Context, file: File, name: String): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.pdf" }
        val out = File(dir, safe)
        file.copyTo(out, overwrite = true)
        return FileProvider.getUriForFile(context, context.packageName + ".files", out)
    }

    fun share(context: Context, file: File, name: String, mime: String = "application/pdf") {
        val uri = shareUri(context, file, name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    fun humanDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun baseName(name: String) = name.removeSuffix(".pdf").removeSuffix(".PDF")

    /** PDF dates look like D:20240115103000+01'00' */
    fun pdfDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.removePrefix("D:")
        if (s.length < 8) return raw
        return runCatching {
            val y = s.substring(0, 4); val mo = s.substring(4, 6); val d = s.substring(6, 8)
            val h = s.substring(8, 10.coerceAtMost(s.length)).ifEmpty { null }
            val mi = if (s.length >= 12) s.substring(10, 12) else null
            if (h != null && mi != null) "$y-$mo-$d $h:$mi" else "$y-$mo-$d"
        }.getOrDefault(raw)
    }
}
