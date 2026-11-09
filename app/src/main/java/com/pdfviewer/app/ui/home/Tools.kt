package com.pdfviewer.app.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.data.Images
import com.pdfviewer.app.pdf.PageSpec
import com.pdfviewer.app.pdf.PdfEditor
import com.pdfviewer.app.pdf.PdfEngine
import com.pdfviewer.app.ui.viewer.ViewerActivity
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A question the running tool is waiting on. */
sealed interface Ask {
    val result: CompletableDeferred<Any?>
    data class Choice(val title: String, val options: List<String>, val message: String? = null, override val result: CompletableDeferred<Any?> = CompletableDeferred()) : Ask
    data class Input(val title: String, val label: String, val initial: String = "", val password: Boolean = false, val number: Boolean = false,
                     val message: String? = null, override val result: CompletableDeferred<Any?> = CompletableDeferred()) : Ask
    data class Protect(override val result: CompletableDeferred<Any?> = CompletableDeferred()) : Ask
    data class Order(val items: List<Pair<Uri, String>>, override val result: CompletableDeferred<Any?> = CompletableDeferred()) : Ask
}

data class ProtectOptions(val password: String, val allowPrint: Boolean, val allowCopy: Boolean, val allowEdit: Boolean)

enum class ToolId(val title: String, val subtitle: String) {
    ORGANIZE("Organize pages", "Reorder, rotate, delete, insert"),
    FILL_SIGN("Fill & Sign", "Fill forms and add your signature"),
    MERGE("Merge PDFs", "Combine files into one"),
    SPLIT("Split PDF", "Extract ranges or split into parts"),
    COMPRESS("Compress PDF", "Reduce file size"),
    PROTECT("Protect PDF", "Add a password"),
    UNLOCK("Remove password", "Save an unprotected copy"),
    IMAGES("Images to PDF", "Create a PDF from photos"),
    SCAN("Scan to PDF", "Use the camera to scan pages"),
    EXPORT_IMAGES("Export as images", "Save pages as PNG or JPEG"),
    EXPORT_TEXT("Export text", "Save all text as .txt"),
    WATERMARK("Add watermark", "Stamp text on every page"),
    PAGE_NUMBERS("Add page numbers", "Number every page"),
}

class ToolRunner(private val activity: ComponentActivity, private val snackbar: SnackbarHostState) {
    var ask by mutableStateOf<Ask?>(null)
    var progress by mutableStateOf<String?>(null)
    private val ctx = activity.applicationContext

    private var waiting: CompletableDeferred<Any?>? = null
    private fun <I> launcher(contract: androidx.activity.result.contract.ActivityResultContract<I, *>): ActivityResultLauncher<I> =
        activity.registerForActivityResult(contract) { waiting?.complete(it); waiting = null }

    private val openPdf = launcher(ActivityResultContracts.OpenDocument())
    private val openPdfs = launcher(ActivityResultContracts.OpenMultipleDocuments())
    private val openImages = launcher(ActivityResultContracts.GetMultipleContents())
    private val createPdf = launcher(ActivityResultContracts.CreateDocument("application/pdf"))
    private val createTxt = launcher(ActivityResultContracts.CreateDocument("text/plain"))
    private val openTree = launcher(ActivityResultContracts.OpenDocumentTree())
    private val takePicture = launcher(ActivityResultContracts.TakePicture())

    @Suppress("UNCHECKED_CAST")
    private suspend fun <I, O> await(l: ActivityResultLauncher<I>, input: I): O? {
        val d = CompletableDeferred<Any?>()
        waiting = d
        try { l.launch(input) } catch (e: Exception) { waiting = null; msg("No app available for this action"); return null }
        return d.await() as O?
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> question(a: Ask): T? { ask = a; val r = a.result.await(); ask = null; return r as T? }

    private suspend fun choose(title: String, options: List<String>, message: String? = null): Int? = question(Ask.Choice(title, options, message))
    private suspend fun input(title: String, label: String, initial: String = "", password: Boolean = false, number: Boolean = false, message: String? = null): String? =
        question(Ask.Input(title, label, initial, password, number, message))

    fun msg(text: String) { activity.lifecycleScope.launch { snackbar.showSnackbar(text) } }

    private suspend fun <T> work(label: String, block: () -> T): T? {
        progress = label
        return try { withContext(Dispatchers.IO) { block() } } catch (e: Throwable) {
            msg("Failed: ${e.message ?: e.javaClass.simpleName}"); null
        } finally { progress = null }
    }

    private fun temp(prefix: String, ext: String = "pdf") = File(Files.tempDir(ctx), "${prefix}_${System.nanoTime()}.$ext")

    private suspend fun pickOne(): Pair<File, String>? {
        val uri: Uri = await(openPdf, arrayOf("application/pdf")) ?: return null
        val name = Files.displayName(ctx, uri)
        val f = work("Loading…") { Files.copyFromUri(ctx, uri, temp("src")) } ?: return null
        return f to name
    }

    /** "" when no password is needed, null when the user cancelled. */
    private suspend fun passwordFor(f: File): String? {
        val needs = withContext(Dispatchers.IO) {
            try { PdfEditor.load(f, null).close(); false } catch (e: InvalidPasswordException) { true }
        }
        if (!needs) return ""
        var message: String? = "This file is password protected."
        while (true) {
            val pw = input("Password", "Password", password = true, message = message) ?: return null
            val ok = withContext(Dispatchers.IO) { runCatching { PdfEditor.load(f, pw).close() }.isSuccess }
            if (ok) return pw
            message = "Incorrect password. Try again."
        }
    }

    private suspend fun saveResult(file: File, suggested: String, mime: String = "application/pdf") {
        val target: Uri = await(if (mime == "text/plain") createTxt else createPdf, suggested) ?: return
        val ok = work("Saving…") { Files.writeBack(ctx, file, target) } ?: false
        if (!ok) { msg("Couldn't save the file"); return }
        runCatching { activity.contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (mime == "application/pdf") {
            val r = snackbar.showSnackbar("Saved ${Files.displayName(ctx, target)} (${Files.humanSize(file.length())})", actionLabel = "Open", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) openViewer(target)
        } else msg("Saved ${Files.displayName(ctx, target)}")
    }

    fun openViewer(uri: Uri, action: String? = null) {
        activity.startActivity(Intent(activity, ViewerActivity::class.java).setData(uri)
            .putExtra(ViewerActivity.EXTRA_ACTION, action))
    }

    fun run(tool: ToolId) {
        activity.lifecycleScope.launch {
            try {
                when (tool) {
                    ToolId.ORGANIZE -> pickAndOpen("organize")
                    ToolId.FILL_SIGN -> pickAndOpen("fillsign")
                    ToolId.MERGE -> merge()
                    ToolId.SPLIT -> split()
                    ToolId.COMPRESS -> compress()
                    ToolId.PROTECT -> protect()
                    ToolId.UNLOCK -> unlock()
                    ToolId.IMAGES -> imagesToPdf()
                    ToolId.SCAN -> scan()
                    ToolId.EXPORT_IMAGES -> exportImages()
                    ToolId.EXPORT_TEXT -> exportText()
                    ToolId.WATERMARK -> watermark()
                    ToolId.PAGE_NUMBERS -> pageNumbers()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                progress = null; ask = null
                msg("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun pickAndOpen(action: String) {
        val uri: Uri = await(openPdf, arrayOf("application/pdf")) ?: return
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        openViewer(uri, action)
    }

    private suspend fun merge() {
        val uris: List<Uri> = await(openPdfs, arrayOf("application/pdf")) ?: return
        if (uris.size < 2) { msg("Choose at least two PDFs (long-press to select several)"); return }
        val named = uris.map { it to Files.displayName(ctx, it) }
        val ordered: List<Pair<Uri, String>> = question(Ask.Order(named)) ?: return
        val files = ArrayList<File>()
        for ((u, n) in ordered) {
            val f = work("Loading $n…") { Files.copyFromUri(ctx, u, temp("m")) } ?: return
            val pw = passwordFor(f) ?: return
            if (pw.isNotEmpty()) {
                val unlocked = temp("mu")
                work("Unlocking $n…") { PdfEditor.removeSecurity(f, pw, unlocked) } ?: return
                files += unlocked
            } else files += f
        }
        val out = temp("merged")
        work("Merging ${files.size} files…") { PdfEditor.merge(files, out) } ?: return
        saveResult(out, "Merged.pdf")
    }

    private fun parseRanges(s: String, max: Int): List<Int>? {
        val out = ArrayList<Int>()
        for (part in s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(part)
            if (m != null) {
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                if (a < 1 || b < 1 || a > max || b > max) return null
                if (a <= b) (a..b).forEach { out += it - 1 } else (a downTo b).forEach { out += it - 1 }
            } else {
                val n = part.toIntOrNull() ?: return null
                if (n !in 1..max) return null
                out += n - 1
            }
        }
        return out.ifEmpty { null }
    }

    private suspend fun pageCount(f: File, pw: String): Int = withContext(Dispatchers.IO) { PdfEditor.load(f, pw.ifEmpty { null }).use { it.numberOfPages } }

    private suspend fun split() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val n = pageCount(f, pw)
        val mode = choose("Split \"$name\" ($n pages)", listOf("Extract pages (e.g. 1-3, 5)", "Split every N pages", "One file per page")) ?: return
        val base = Files.baseName(name)
        if (mode == 0) {
            var message: String? = null
            while (true) {
                val s = input("Extract pages", "Pages", "1-${minOf(n, 2)}", message = message ?: "Pages 1 to $n. Use commas and ranges.") ?: return
                val pages = parseRanges(s, n)
                if (pages == null) { message = "Couldn't read that. Example: 1-3, 7, 9-12"; continue }
                val out = temp("extract")
                work("Extracting…") { PdfEditor.rearrange(f, pw.ifEmpty { null }, pages.map { PageSpec(it) }, out) } ?: return
                saveResult(out, "$base (pages $s).pdf".replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                return
            }
        }
        val every = if (mode == 2) 1 else (input("Split every N pages", "Pages per file", "2", number = true)?.toIntOrNull() ?: return).coerceAtLeast(1)
        val tree: Uri = await(openTree, null) ?: return
        val dir = File(Files.tempDir(ctx), "split_${System.nanoTime()}").apply { mkdirs() }
        val parts = work("Splitting…") { PdfEditor.splitEvery(f, pw.ifEmpty { null }, every, dir, base) } ?: return
        val written = work("Saving ${parts.size} files…") { parts.count { writeToTree(tree, it, "application/pdf") } } ?: 0
        msg("Saved $written file(s)")
    }

    private fun writeToTree(tree: Uri, file: File, mime: String): Boolean = runCatching {
        val cr = activity.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, mime, file.name) ?: return false
        cr.openOutputStream(doc)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return false
        true
    }.getOrDefault(false)

    private suspend fun compress() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val level = choose("Compression", listOf("Balanced (recommended)", "Smallest file", "High quality")) ?: return
        val (dim, q) = when (level) { 1 -> 1000 to 0.5f; 2 -> 2200 to 0.85f; else -> 1600 to 0.7f }
        val out = temp("compressed")
        val res = work("Compressing…") { PdfEditor.compress(f, pw.ifEmpty { null }, dim, q, out) } ?: return
        if (res.after >= res.before) {
            msg("This PDF is already well optimised (${Files.humanSize(res.before)})")
            if (choose("No size reduction", listOf("Save anyway", "Cancel")) != 0) return
        } else {
            val pct = 100 - (res.after * 100 / res.before)
            msg("${Files.humanSize(res.before)} → ${Files.humanSize(res.after)} (−$pct%)")
        }
        saveResult(out, Files.baseName(name) + " (compressed).pdf")
    }

    private suspend fun protect() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val opts: ProtectOptions = question(Ask.Protect()) ?: return
        val out = temp("protected")
        work("Encrypting…") {
            PdfEditor.protect(f, pw.ifEmpty { null }, opts.password, java.util.UUID.randomUUID().toString(), opts.allowPrint, opts.allowCopy, opts.allowEdit, out)
        } ?: return
        saveResult(out, Files.baseName(name) + " (protected).pdf")
    }

    private suspend fun unlock() {
        val (f, name) = pickOne() ?: return
        val encrypted = withContext(Dispatchers.IO) { PdfEditor.isEncrypted(f) }
        if (!encrypted) { msg("\"$name\" isn't password protected"); return }
        val pw = passwordFor(f) ?: return
        val out = temp("unlocked")
        work("Removing password…") { PdfEditor.removeSecurity(f, pw.ifEmpty { null }, out) } ?: return
        saveResult(out, Files.baseName(name) + " (unlocked).pdf")
    }

    private suspend fun pageFormat(): PdfEditor.PageFormat? {
        val i = choose("Page size", listOf("Fit each image", "A4", "US Letter")) ?: return null
        return PdfEditor.PageFormat.entries[i]
    }

    private suspend fun imagesToPdf() {
        val uris: List<Uri> = await(openImages, "image/*") ?: return
        if (uris.isEmpty()) return
        val format = pageFormat() ?: return
        val out = temp("images")
        work("Creating PDF from ${uris.size} image(s)…") {
            PdfEditor.imagesToPdf(uris.asSequence().mapNotNull { Images.decode(ctx, it) }, format, out)
        } ?: return
        saveResult(out, "Images.pdf")
    }

    private suspend fun scan() {
        val shots = ArrayList<File>()
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        while (true) {
            val f = File(dir, "scan_${System.nanoTime()}.jpg")
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val ok: Boolean = await(takePicture, uri) ?: false
            if (ok && f.length() > 0) shots += f
            if (shots.isEmpty()) return
            val next = choose("${shots.size} page(s) scanned", listOf("Scan another page", "Finish and create PDF", "Cancel")) ?: return
            if (next == 1) break
            if (next == 2) return
        }
        val format = pageFormat() ?: return
        val out = temp("scan")
        work("Creating PDF…") {
            PdfEditor.imagesToPdf(shots.asSequence().mapNotNull { Images.decode(ctx, Uri.fromFile(it)) }, format, out)
        } ?: return
        saveResult(out, "Scan.pdf")
    }

    private suspend fun exportImages() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val fmt = choose("Image format", listOf("PNG (lossless)", "JPEG (smaller)")) ?: return
        val q = choose("Resolution", listOf("Standard (110 dpi)", "High (200 dpi)", "Print (300 dpi)")) ?: return
        val dpi = when (q) { 1 -> 200; 2 -> 300; else -> 110 }
        val tree: Uri = await(openTree, null) ?: return
        val engine = try { PdfEngine.open(ctx, f, pw.ifEmpty { null }) } catch (e: Exception) { msg("Couldn't open PDF"); return }
        try {
            val base = Files.baseName(name)
            var count = 0
            for (i in 0 until engine.pageCount) {
                progress = "Exporting page ${i + 1} of ${engine.pageCount}…"
                val s = engine.pageSizes[i]
                val w = (s.width * dpi / 72f).toInt().coerceIn(1, 6000)
                val bmp = engine.renderThumbnail(i, w) ?: continue
                val ext = if (fmt == 0) "png" else "jpg"
                val file = File(Files.tempDir(ctx), "${base}_page${i + 1}.$ext")
                withContext(Dispatchers.IO) {
                    file.outputStream().use { bmp.compress(if (fmt == 0) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                    if (writeToTree(tree, file, if (fmt == 0) "image/png" else "image/jpeg")) count++
                    file.delete()
                }
            }
            msg("Exported $count image(s)")
        } finally {
            progress = null
            engine.close()
        }
    }

    private suspend fun exportText() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val engine = try { PdfEngine.open(ctx, f, pw.ifEmpty { null }) } catch (e: Exception) { msg("Couldn't open PDF"); return }
        val out = temp("text", "txt")
        try {
            val sb = StringBuilder()
            for (i in 0 until engine.pageCount) {
                progress = "Reading page ${i + 1} of ${engine.pageCount}…"
                sb.append("--- Page ${i + 1} ---\n").append(engine.pageText(i).trim()).append("\n\n")
            }
            withContext(Dispatchers.IO) { out.writeText(sb.toString()) }
        } finally { progress = null; engine.close() }
        if (out.length() < 100) msg("Very little text found — this may be a scanned document")
        saveResult(out, Files.baseName(name) + ".txt", "text/plain")
    }

    private suspend fun watermark() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val text = input("Watermark text", "Text", "CONFIDENTIAL") ?: return
        if (text.isBlank()) return
        val strength = choose("Opacity", listOf("Light", "Medium", "Strong")) ?: return
        val op = when (strength) { 0 -> 0.12f; 2 -> 0.4f; else -> 0.22f }
        val out = temp("wm")
        work("Adding watermark…") { PdfEditor.watermark(f, pw.ifEmpty { null }, text, op, 72f, 0xFF9E9E9E.toInt(), out) } ?: return
        saveResult(out, Files.baseName(name) + " (watermarked).pdf")
    }

    private suspend fun pageNumbers() {
        val (f, name) = pickOne() ?: return
        val pw = passwordFor(f) ?: return
        val formats = listOf("{n}", "Page {n}", "Page {n} of {total}", "{n} / {total}")
        val i = choose("Number style", listOf("1", "Page 1", "Page 1 of N", "1 / N")) ?: return
        val out = temp("num")
        work("Numbering pages…") { PdfEditor.pageNumbers(f, pw.ifEmpty { null }, formats[i], 10f, out) } ?: return
        saveResult(out, Files.baseName(name) + " (numbered).pdf")
    }
}

// ------------------------------------------------------------------------------- dialogs

@Composable
fun ToolDialogs(r: ToolRunner) {
    when (val a = r.ask) {
        null -> {}
        is Ask.Choice -> {
            var sel by remember(a) { mutableStateOf(0) }
            AlertDialog(onDismissRequest = { a.result.complete(null) }, title = { Text(a.title) },
                text = {
                    Column {
                        a.message?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                        a.options.forEachIndexed { i, o ->
                            Row(Modifier.fillMaxWidth().clickable { sel = i }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(sel == i, { sel = i }); Text(o)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { a.result.complete(sel) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("Cancel") } })
        }
        is Ask.Input -> {
            var text by remember(a) { mutableStateOf(a.initial) }
            AlertDialog(onDismissRequest = { a.result.complete(null) }, title = { Text(a.title) },
                text = {
                    Column {
                        a.message?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                        OutlinedTextField(text, { text = it }, label = { Text(a.label) }, singleLine = true,
                            visualTransformation = if (a.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = when { a.password -> KeyboardType.Password; a.number -> KeyboardType.Number; else -> KeyboardType.Text }))
                    }
                },
                confirmButton = { TextButton(onClick = { a.result.complete(text) }, enabled = text.isNotEmpty()) { Text("OK") } },
                dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("Cancel") } })
        }
        is Ask.Protect -> {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var print by remember { mutableStateOf(true) }
            var copy by remember { mutableStateOf(true) }
            var edit by remember { mutableStateOf(false) }
            val ok = pw.length >= 4 && pw == pw2
            AlertDialog(onDismissRequest = { a.result.complete(null) }, title = { Text("Protect with password") },
                text = {
                    Column {
                        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            isError = pw2.isNotEmpty() && pw != pw2)
                        if (pw.isNotEmpty() && pw.length < 4) Text("Use at least 4 characters", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text("Without the password, people can't open the file. Once opened:", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(print, { print = it }); Text("Allow printing") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copy, { copy = it }); Text("Allow copying text") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(edit, { edit = it }); Text("Allow editing") }
                    }
                },
                confirmButton = { TextButton(onClick = { a.result.complete(ProtectOptions(pw, print, copy, edit)) }, enabled = ok) { Text("Protect") } },
                dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("Cancel") } })
        }
        is Ask.Order -> {
            val list = remember(a) { mutableStateListOf(*a.items.toTypedArray()) }
            AlertDialog(onDismissRequest = { a.result.complete(null) }, title = { Text("Merge order") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        list.forEachIndexed { i, (_, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. $n", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { if (i > 0) { val t = list[i]; list[i] = list[i - 1]; list[i - 1] = t } }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                                IconButton(onClick = { if (i < list.size - 1) { val t = list[i]; list[i] = list[i + 1]; list[i + 1] = t } }, enabled = i < list.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { a.result.complete(list.toList()) }) { Text("Merge") } },
                dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("Cancel") } })
        }
    }
}
