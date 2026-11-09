package com.pdfviewer.app.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pdfviewer.app.annot.ExistingAnnot
import com.pdfviewer.app.annot.FieldType
import com.pdfviewer.app.annot.FormFieldInfo
import com.pdfviewer.app.annot.Item
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.NoteItem
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.StampKind
import com.pdfviewer.app.annot.TextItem
import com.pdfviewer.app.annot.Tool
import com.pdfviewer.app.annot.ToolStyle
import com.pdfviewer.app.annot.newId
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.data.Library
import com.pdfviewer.app.data.Settings
import com.pdfviewer.app.data.UserBookmark
import com.pdfviewer.app.pdf.DocMeta
import com.pdfviewer.app.pdf.Extras
import com.pdfviewer.app.pdf.OutlineItem
import com.pdfviewer.app.pdf.PageLink
import com.pdfviewer.app.pdf.PasswordRequiredException
import com.pdfviewer.app.pdf.PdfEditor
import com.pdfviewer.app.pdf.PdfEngine
import com.pdfviewer.app.pdf.SearchHit
import com.pdfviewer.app.view.PdfView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

enum class ViewerMode { VIEW, ANNOTATE, FILL_SIGN }
enum class Sheet { OUTLINE, THUMBNAILS, COMMENTS, FORM_FIELDS }

sealed interface ViewerDialog {
    data object GoToPage : ViewerDialog
    data object Info : ViewerDialog
    data object SaveOnExit : ViewerDialog
    data object Settings : ViewerDialog
    data class Link(val uri: String) : ViewerDialog
    data class Annot(val annot: ExistingAnnot) : ViewerDialog
    data class Field(val field: FormFieldInfo) : ViewerDialog
    data class TextInput(
        val purpose: Purpose, val initial: String, val page: Int, val x: Float, val y: Float,
        val itemId: Long = 0, val annot: ExistingAnnot? = null,
    ) : ViewerDialog {
        enum class Purpose { NOTE, COMMENT_TEXT, FILL_TEXT, EDIT_ITEM, EDIT_ANNOT, MARKUP_NOTE }
    }
    data class Signature(val initials: Boolean, val page: Int, val x: Float, val y: Float) : ViewerDialog
}

class ViewerController(private val activity: ComponentActivity, val view: PdfView) : PdfView.Listener {
    private val ctx: Context = activity.applicationContext
    val scope = activity.lifecycleScope

    // ---------------------------------------------------------------- document
    var uri by mutableStateOf<Uri?>(null); private set
    var name by mutableStateOf("Document"); private set
    var workFile: File? = null; private set
    var password: String? = null; private set
    var engine by mutableStateOf<PdfEngine?>(null); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var passwordPrompt by mutableStateOf<Boolean?>(null) // null = hidden, true = wrong pw
    var fileSize by mutableStateOf(0L); private set

    var page by mutableIntStateOf(0); private set
    var pageCount by mutableIntStateOf(0); private set
    var chrome by mutableStateOf(true)
    var zoom by mutableFloatStateOf(1f); private set

    // ---------------------------------------------------------------- editing
    var mode by mutableStateOf(ViewerMode.VIEW); private set
    var tool by mutableStateOf(Tool.NONE); private set
    var style by mutableStateOf(ToolStyle()); private set
    var items by mutableStateOf<List<Item>>(emptyList()); private set
    private val undoStack = ArrayDeque<List<Item>>()
    private val redoStack = ArrayDeque<List<Item>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    /** The working copy has changes not yet written to the original file. */
    var dirtyOriginal by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    var extras by mutableStateOf<Extras?>(null); private set
    var pendingImageFor: Triple<Int, Float, Float>? = null
    /** Action requested by the launcher ("organize", "fillsign", "annotate"). */
    var pendingAction: String? = null

    // ---------------------------------------------------------------- navigation data
    var outline by mutableStateOf<List<OutlineItem>>(emptyList()); private set
    var bookmarks by mutableStateOf<List<UserBookmark>>(emptyList()); private set
    var meta by mutableStateOf<DocMeta?>(null); private set

    // ---------------------------------------------------------------- search
    var searchOpen by mutableStateOf(false); private set
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var hits by mutableStateOf<List<SearchHit>>(emptyList()); private set
    var hitIndex by mutableIntStateOf(-1); private set
    var searching by mutableStateOf(false); private set
    var searchProgress by mutableFloatStateOf(0f); private set
    private var searchJob: Job? = null

    // ---------------------------------------------------------------- selection & UI
    var selection by mutableStateOf<PdfView.TextSelection?>(null); private set
    var selectedItem by mutableStateOf<Item?>(null); private set
    var sheet by mutableStateOf<Sheet?>(null)
    var dialog by mutableStateOf<ViewerDialog?>(null)
    var reflow by mutableStateOf(false)
    var colorMode by mutableIntStateOf(Settings.colorMode); private set
    var horizontal by mutableStateOf(Settings.horizontalScroll); private set
    var keepScreenOn by mutableStateOf(Settings.keepScreenOn); private set
    val snackbar = SnackbarHostState()
    val tts = TtsController(ctx, scope)

    /** Called by the activity to request "Save as" / image picking. */
    var requestSaveAs: ((String) -> Unit)? = null
    var requestPickImage: (() -> Unit)? = null
    var requestOrganize: ((File, String?) -> Unit)? = null
    var onFinish: (() -> Unit)? = null

    private val thumbCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    init {
        view.listener = this
        view.colorMode = PdfView.ColorMode.entries[colorMode.coerceIn(0, 2)]
        view.scrollMode = if (horizontal) PdfView.ScrollMode.HORIZONTAL else PdfView.ScrollMode.VERTICAL
        view.highlightFormFields = Settings.highlightFields
        tts.onPageChange = { p -> if (p != page) view.goToPage(p) }
    }

    fun msg(text: String) { scope.launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Short) } }

    // ================================================================= opening

    fun open(u: Uri) {
        uri = u
        name = Files.displayName(ctx, u)
        loading = true; error = null
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Files.copyToWork(ctx, u) }
                workFile = f
                fileSize = f.length()
                openEngine(null, initialPage = Library.get(u.toString())?.lastPage ?: 0)
            } catch (e: Exception) {
                loading = false
                error = "Couldn't open this file.\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun submitPassword(pw: String) {
        passwordPrompt = null
        scope.launch { openEngine(pw, Library.get(uri.toString())?.lastPage ?: 0) }
    }

    private suspend fun openEngine(pw: String?, initialPage: Int) {
        val f = workFile ?: return
        loading = true
        try {
            val e = PdfEngine.open(ctx, f, pw)
            password = pw
            engine = e
            pageCount = e.pageCount
            view.setEngine(e, initialPage)
            page = view.currentPage
            loading = false
            Library.opened(uri.toString(), name, fileSize, e.pageCount)
            bookmarks = Library.bookmarks(uri.toString())
            afterEngineLoaded(e)
            saveThumbnail(e)
            when (pendingAction) {
                "organize" -> organize()
                "fillsign" -> enterMode(ViewerMode.FILL_SIGN)
                "annotate" -> enterMode(ViewerMode.ANNOTATE)
            }
            pendingAction = null
        } catch (e: PasswordRequiredException) {
            loading = false
            passwordPrompt = e.wrongPassword
        } catch (e: Exception) {
            loading = false
            error = "This file couldn't be opened. It may be damaged or not a PDF.\n(${e.message ?: e.javaClass.simpleName})"
        }
    }

    private fun afterEngineLoaded(e: PdfEngine) {
        thumbCache.evictAll()
        scope.launch { outline = e.outline() }
        scope.launch { meta = e.meta() }
        loadExtras()
    }

    private fun loadExtras() {
        val f = workFile ?: return
        val e = engine ?: return
        scope.launch {
            val ex = withContext(Dispatchers.IO) { runCatching { PdfEditor.readExtras(f, password, e.pageSizes) }.getOrNull() }
            if (engine !== e) return@launch
            extras = ex
            view.existingAnnots = ex?.annots ?: emptyMap()
            view.formFields = ex?.fields ?: emptyMap()
            if (ex?.hasForm == true && mode == ViewerMode.VIEW) {
                val r = snackbar.showSnackbar("This document has fillable form fields", actionLabel = "Fill", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) sheet = Sheet.FORM_FIELDS
            }
        }
    }

    private fun saveThumbnail(e: PdfEngine) {
        val key = uri?.toString() ?: return
        scope.launch {
            val bmp = e.renderThumbnail(0, 240) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { Library.thumbFile(key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            }
        }
    }

    suspend fun thumbnail(index: Int, width: Int = 220): Bitmap? {
        thumbCache.get(index)?.let { return it }
        val e = engine ?: return null
        val b = e.renderThumbnail(index, width) ?: return null
        thumbCache.put(index, b)
        return b
    }

    suspend fun pageText(index: Int): String = engine?.pageText(index) ?: ""

    fun persistPosition() {
        uri?.let { Library.updatePage(it.toString(), page) }
    }

    fun release() {
        persistPosition()
        searchJob?.cancel()
        tts.shutdown()
        view.release()
        engine?.close()
        engine = null
    }

    // ================================================================= PdfView.Listener

    override fun onPageChanged(page: Int) {
        this.page = page
    }

    override fun onTap() {
        if (mode == ViewerMode.VIEW && !searchOpen) chrome = !chrome
    }

    override fun onZoomChanged(zoom: Float) { this.zoom = zoom }

    override fun onLink(link: PageLink) {
        val p = link.page
        if (p != null && p in 0 until pageCount) { view.goToPage(p); return }
        link.uri?.let { dialog = ViewerDialog.Link(it) }
    }

    override fun onSelectionChanged(selection: PdfView.TextSelection?) {
        this.selection = selection
        if (selection != null) chrome = true
    }

    override fun onItemAdded(item: Item) {
        pushUndo(); items = items + item; syncItems()
    }

    override fun onItemUpdated(item: Item) {
        pushUndo(); items = items.map { if (it.id == item.id) item else it }; selectedItem = item; syncItems()
    }

    override fun onItemsErased(ids: Set<Long>) {
        pushUndo(); items = items.filter { it.id !in ids }; syncItems()
    }

    override fun onItemSelected(item: Item?) { selectedItem = item }

    override fun onExistingAnnotationTap(annot: ExistingAnnot) { dialog = ViewerDialog.Annot(annot) }

    override fun onFormFieldTap(field: FormFieldInfo) {
        when (field.type) {
            FieldType.CHECKBOX -> {
                val on = field.value.isNotEmpty() && field.value != "Off"
                setFieldValue(field, if (on) "Off" else (field.onValue ?: "Yes"))
            }
            FieldType.RADIO -> setFieldValue(field, field.onValue ?: return)
            FieldType.SIGNATURE -> {
                dialog = ViewerDialog.Signature(false, field.page, field.rect.centerX(), field.rect.centerY())
                pendingSignatureRect = field.page to RectF(field.rect)
            }
            FieldType.BUTTON -> {}
            else -> dialog = ViewerDialog.Field(field)
        }
    }

    private var pendingSignatureRect: Pair<Int, RectF>? = null

    override fun onPlace(page: Int, x: Float, y: Float) {
        val e = engine ?: return
        val ps = e.pageSizes[page]
        when (tool) {
            Tool.NOTE -> dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.NOTE, "", page, x, y)
            Tool.TEXT -> dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.COMMENT_TEXT, "", page, x, y)
            Tool.FILL_TEXT -> dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.FILL_TEXT, "", page, x, y)
            Tool.DATE -> addText(page, x, y, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()), asAnnotation = false)
            Tool.CHECK, Tool.CROSS, Tool.DOT, Tool.CIRCLE_MARK, Tool.LINE_MARK -> {
                val kind = when (tool) {
                    Tool.CHECK -> StampKind.CHECK; Tool.CROSS -> StampKind.CROSS; Tool.DOT -> StampKind.DOT
                    Tool.CIRCLE_MARK -> StampKind.CIRCLE; else -> StampKind.LINE
                }
                val w = if (kind == StampKind.LINE) 48f else 14f
                val h = if (kind == StampKind.LINE) 6f else 14f
                val r = fitRect(x - w / 2, y - h / 2, w, h, ps.width.toFloat(), ps.height.toFloat())
                val item = StampItem(newId(), page, r, kind, null, style.color)
                onItemAdded(item); view.selectItem(item)
            }
            Tool.SIGNATURE, Tool.INITIALS -> {
                val initials = tool == Tool.INITIALS
                val f = signatureFile(initials)
                if (f.exists()) placeImage(page, x, y, f.absolutePath, if (initials) 60f else 150f)
                else dialog = ViewerDialog.Signature(initials, page, x, y)
            }
            Tool.IMAGE -> { pendingImageFor = Triple(page, x, y); requestPickImage?.invoke() }
            else -> {}
        }
    }

    private fun fitRect(l: Float, t: Float, w: Float, h: Float, pw: Float, ph: Float): RectF {
        val left = l.coerceIn(0f, max(0f, pw - w)); val top = t.coerceIn(0f, max(0f, ph - h))
        return RectF(left, top, left + w, top + h)
    }

    fun signatureFile(initials: Boolean) = File(Files.signatureDir(ctx), if (initials) "initials.png" else "signature.png")

    fun placeImage(page: Int, x: Float, y: Float, path: String, width: Float) {
        val e = engine ?: return
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0) { msg("Couldn't read image"); return }
        val ps = e.pageSizes[page]
        var w = min(width, ps.width * 0.8f)
        var h = w * opts.outHeight / opts.outWidth
        if (h > ps.height * 0.8f) { h = ps.height * 0.8f; w = h * opts.outWidth / opts.outHeight }
        val r = fitRect(x - w / 2, y - h / 2, w, h, ps.width.toFloat(), ps.height.toFloat())
        val item = StampItem(newId(), page, r, StampKind.IMAGE, path, 0xFF000000.toInt())
        onItemAdded(item)
        view.selectItem(item)
        if (tool == Tool.SIGNATURE || tool == Tool.INITIALS || tool == Tool.IMAGE) selectTool(Tool.NONE)
    }

    fun onSignatureSaved(initials: Boolean, page: Int, x: Float, y: Float) {
        view.painterForget(signatureFile(initials).absolutePath)
        val field = pendingSignatureRect
        pendingSignatureRect = null
        if (field != null) {
            // Fit inside the signature form field.
            val (p, r) = field
            val path = signatureFile(initials).absolutePath
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0) {
                val k = min(r.width() / opts.outWidth, r.height() / opts.outHeight)
                val w = opts.outWidth * k; val h = opts.outHeight * k
                val rect = RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
                onItemAdded(StampItem(newId(), p, rect, StampKind.IMAGE, path, 0xFF000000.toInt()))
                if (mode == ViewerMode.VIEW) msg("Signature added. Tap Save to keep it.")
            }
            return
        }
        placeImage(page, x, y, signatureFile(initials).absolutePath, if (initials) 60f else 150f)
    }

    fun addText(page: Int, x: Float, y: Float, text: String, asAnnotation: Boolean) {
        if (text.isBlank()) return
        val e = engine ?: return
        val ps = e.pageSizes[page]
        val size = style.fontSize
        val paint = Paint().apply { textSize = size }
        val lines = text.split('\n')
        val w = min(ps.width - x, max(40f, lines.maxOf { paint.measureText(it) } + 6f)).coerceAtLeast(30f)
        val h = lines.size * size * 1.2f + 2f
        val r = fitRect(x, y - size * 0.6f, w, h, ps.width.toFloat(), ps.height.toFloat())
        val item = TextItem(newId(), page, r, text, if (asAnnotation) style.color else style.color, size, asAnnotation)
        onItemAdded(item)
        view.selectItem(item)
    }

    fun addNote(page: Int, x: Float, y: Float, text: String) {
        val item = NoteItem(newId(), page, x - NoteItem.SIZE / 2, y - NoteItem.SIZE / 2, text, style.color)
        onItemAdded(item)
    }

    fun editItemText(id: Long, text: String) {
        val it = items.firstOrNull { it.id == id } ?: return
        val updated = when (it) {
            is TextItem -> {
                val paint = Paint().apply { textSize = it.fontSize }
                val lines = text.split('\n')
                val w = max(it.rect.width(), lines.maxOf { l -> paint.measureText(l) } + 6f)
                it.copy(text = text, rect = RectF(it.rect.left, it.rect.top, it.rect.left + w, it.rect.top + lines.size * it.fontSize * 1.2f + 2f))
            }
            is NoteItem -> it.copy(text = text)
            is MarkupItem -> it.copy(note = text)
            else -> return
        }
        onItemUpdated(updated)
    }

    fun recolorSelected(color: Int) {
        val it = selectedItem ?: return
        val updated = when (it) {
            is TextItem -> it.copy(color = color)
            is NoteItem -> it.copy(color = color)
            is MarkupItem -> it.copy(color = color)
            is com.pdfviewer.app.annot.InkItem -> it.copy(color = color)
            is com.pdfviewer.app.annot.ShapeItem -> it.copy(color = color)
            is StampItem -> if (it.kind == StampKind.IMAGE) return else it.copy(color = color)
        }
        onItemUpdated(updated)
    }

    fun deleteSelected() {
        val it = selectedItem ?: return
        onItemsErased(setOf(it.id))
        view.selectItem(null)
    }

    private fun pushUndo() {
        undoStack.addLast(items)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true; canRedo = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(items)
        items = prev; syncItems()
        canUndo = undoStack.isNotEmpty(); canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(items)
        items = next; syncItems()
        canUndo = true; canRedo = redoStack.isNotEmpty()
    }

    private fun syncItems() {
        view.items = items
        selectedItem = view.selectedItem
    }

    // ================================================================= modes & tools

    fun enterMode(m: ViewerMode) {
        mode = m
        chrome = true
        closeSearch()
        selectTool(Tool.NONE)
        view.clearSelection()
    }

    fun exitMode() {
        selectTool(Tool.NONE)
        view.selectItem(null)
        mode = ViewerMode.VIEW
    }

    fun selectTool(t: Tool) {
        tool = t
        view.tool = t
        val key = t.name
        val defColor = when (t) {
            Tool.HIGHLIGHT -> 0xFFFFEB3B.toInt()
            Tool.UNDERLINE -> 0xFF2E7D32.toInt()
            Tool.STRIKEOUT, Tool.SQUIGGLY -> 0xFFE53935.toInt()
            Tool.NOTE -> 0xFFFFD54F.toInt()
            Tool.INK -> 0xFF1E88E5.toInt()
            Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW -> 0xFFE53935.toInt()
            Tool.TEXT -> 0xFFD32F2F.toInt()
            else -> 0xFF111111.toInt()
        }
        val defWidth = when (t) { Tool.INK -> 2.5f; else -> 2f }
        style = ToolStyle(
            color = Settings.toolColor(key, defColor),
            width = Settings.toolWidth(key, defWidth),
            opacity = Settings.toolWidth(key + "_op", 1f),
            fontSize = Settings.toolWidth(key + "_fs", if (t == Tool.TEXT) 12f else 14f),
        )
        view.toolStyle = style
    }

    fun updateStyle(s: ToolStyle) {
        style = s
        view.toolStyle = s
        val key = tool.name
        Settings.setToolColor(key, s.color)
        Settings.setToolWidth(key, s.width)
        Settings.setToolWidth(key + "_op", s.opacity)
        Settings.setToolWidth(key + "_fs", s.fontSize)
    }

    // ================================================================= saving

    private suspend fun swapIn(newFile: File) {
        val f = workFile ?: return
        val old = engine
        engine = null
        old?.close()
        withContext(PdfEngine.dispatcher) { } // wait for close to run on the pdfium thread
        withContext(Dispatchers.IO) {
            if (f.exists()) f.delete()
            if (!newFile.renameTo(f)) { newFile.copyTo(f, true); newFile.delete() }
        }
        fileSize = f.length()
        val e = PdfEngine.open(ctx, f, password)
        engine = e
        pageCount = e.pageCount
        view.setEngine(e, keepPosition = true)
        page = view.currentPage
        afterEngineLoaded(e)
    }

    /** Runs a PdfBox transformation on the working copy and reloads it. */
    suspend fun commit(label: String, op: (src: File, out: File) -> Unit): Boolean {
        val src = workFile ?: return false
        busy = label
        return try {
            val out = File(Files.tempDir(ctx), "commit_${System.nanoTime()}.pdf")
            withContext(Dispatchers.IO) { op(src, out) }
            swapIn(out)
            dirtyOriginal = true
            true
        } catch (e: Throwable) {
            msg("Failed: ${e.message ?: e.javaClass.simpleName}")
            // reopen if the engine was closed mid-way
            if (engine == null) runCatching { swapInExisting() }
            false
        } finally {
            busy = null
        }
    }

    private suspend fun swapInExisting() {
        val f = workFile ?: return
        val e = PdfEngine.open(ctx, f, password)
        engine = e; pageCount = e.pageCount
        view.setEngine(e, keepPosition = true)
    }

    /** Writes pending annotations into the working copy. */
    suspend fun flushItems(): Boolean {
        if (items.isEmpty()) return true
        val e = engine ?: return false
        val list = items
        val sizes = e.pageSizes
        val ok = commit("Saving annotations…") { src, out -> PdfEditor.writeItems(src, password, list, Settings.author, sizes, out) }
        if (ok) {
            items = emptyList(); undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
            view.selectItem(null)
            syncItems()
        }
        return ok
    }

    val hasUnsaved get() = items.isNotEmpty() || dirtyOriginal

    fun save(thenFinish: Boolean = false) {
        scope.launch {
            if (!flushItems()) return@launch
            val u = uri ?: return@launch
            val f = workFile ?: return@launch
            busy = "Saving…"
            val ok = withContext(Dispatchers.IO) { Files.writeBack(ctx, f, u) }
            busy = null
            if (ok) {
                dirtyOriginal = false
                Library.opened(u.toString(), name, f.length(), pageCount)
                msg("Saved")
                if (thenFinish) onFinish?.invoke()
            } else {
                msg("This file is read-only here. Choose where to save a copy.")
                saveAsThenFinish = thenFinish
                requestSaveAs?.invoke(name)
            }
        }
    }

    private var saveAsThenFinish = false

    fun saveAs() {
        scope.launch {
            if (!flushItems()) return@launch
            requestSaveAs?.invoke(Files.baseName(name) + " (copy).pdf")
        }
    }

    fun onSaveAsTarget(target: Uri?) {
        if (target == null) { saveAsThenFinish = false; return }
        val f = workFile ?: return
        scope.launch {
            busy = "Saving…"
            val ok = withContext(Dispatchers.IO) { Files.writeBack(ctx, f, target) }
            busy = null
            if (ok) {
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                // Continue working on the new file.
                val newName = Files.displayName(ctx, target)
                val oldKey = uri.toString()
                uri = target; name = newName
                val newWork = File(Files.workDir(ctx), Library.key(target.toString()) + ".pdf")
                withContext(Dispatchers.IO) { f.copyTo(newWork, true) }
                val keepPw = password
                swapWorkFile(newWork, keepPw)
                Library.opened(target.toString(), newName, f.length(), pageCount)
                Library.setBookmarks(target.toString(), Library.bookmarks(oldKey))
                dirtyOriginal = false
                msg("Saved as $newName")
                if (saveAsThenFinish) onFinish?.invoke()
            } else msg("Couldn't save to that location")
            saveAsThenFinish = false
        }
    }

    private suspend fun swapWorkFile(newWork: File, pw: String?) {
        val old = engine
        engine = null
        old?.close()
        withContext(PdfEngine.dispatcher) { }
        workFile = newWork
        password = pw
        val e = PdfEngine.open(ctx, newWork, pw)
        engine = e; pageCount = e.pageCount
        view.setEngine(e, keepPosition = true)
        afterEngineLoaded(e)
    }

    fun discardAndFinish() {
        items = emptyList()
        dirtyOriginal = false
        onFinish?.invoke()
    }

    fun requestExit(): Boolean {
        if (hasUnsaved) { dialog = ViewerDialog.SaveOnExit; return false }
        return true
    }

    // ================================================================= forms & existing annotations

    fun setFieldValue(field: FormFieldInfo, value: String) {
        scope.launch {
            commit("Updating form…") { src, out -> PdfEditor.setFormValues(src, password, mapOf(field.name to value), out) }
        }
    }

    fun setFieldValues(values: Map<String, String>) {
        if (values.isEmpty()) return
        scope.launch { commit("Updating form…") { src, out -> PdfEditor.setFormValues(src, password, values, out) } }
    }

    fun flattenForm() {
        scope.launch {
            if (!flushItems()) return@launch
            if (commit("Flattening…") { src, out -> PdfEditor.flattenForm(src, password, out) }) msg("Form flattened")
        }
    }

    fun deleteExisting(a: ExistingAnnot) {
        scope.launch {
            if (!flushItems()) return@launch
            commit("Deleting…") { src, out -> PdfEditor.deleteAnnotation(src, password, a.page, a.index, out) }
        }
    }

    fun editExisting(a: ExistingAnnot, text: String) {
        scope.launch {
            if (!flushItems()) return@launch
            commit("Updating…") { src, out -> PdfEditor.editAnnotationContents(src, password, a.page, a.index, text, out) }
        }
    }

    fun updateMetadata(title: String, author: String, subject: String, keywords: String) {
        scope.launch {
            if (!flushItems()) return@launch
            if (commit("Updating…") { src, out -> PdfEditor.setMetadata(src, password, title, author, subject, keywords, out) }) msg("Properties updated")
        }
    }

    fun organize() {
        scope.launch {
            if (!flushItems()) return@launch
            val f = workFile ?: return@launch
            requestOrganize?.invoke(f, password)
        }
    }

    fun onOrganized(result: File?) {
        if (result == null || !result.exists()) return
        scope.launch {
            busy = "Applying…"
            try { swapIn(result); dirtyOriginal = true; msg("Pages updated") } catch (e: Exception) { msg("Failed: ${e.message}") }
            busy = null
        }
    }

    // ================================================================= search

    fun openSearch() { searchOpen = true; chrome = true; view.clearSelection() }

    fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        hits = emptyList(); hitIndex = -1; searching = false
        view.searchHits = emptyList(); view.currentHit = -1
    }

    fun runSearch(q: String = query) {
        val e = engine ?: return
        searchJob?.cancel()
        hits = emptyList(); hitIndex = -1
        view.searchHits = emptyList(); view.currentHit = -1
        if (q.isBlank()) return
        searching = true; searchProgress = 0f
        val start = page
        val found = ArrayList<SearchHit>()
        searchJob = scope.launch {
            var lastPush = 0L
            e.search(q.trim(), matchCase, wholeWord, start,
                onHit = { h ->
                    synchronized(found) { found += h }
                },
                onProgress = { done ->
                    val now = System.currentTimeMillis()
                    if (now - lastPush > 250 || done == e.pageCount) {
                        lastPush = now
                        val snapshot = synchronized(found) { ArrayList(found) }
                        scope.launch { publishHits(snapshot, done.toFloat() / e.pageCount) }
                    }
                })
            val snapshot = synchronized(found) { ArrayList(found) }
            publishHits(snapshot, 1f)
            searching = false
            if (snapshot.isEmpty()) msg("No matches for \"$q\"")
        }
    }

    private fun publishHits(list: List<SearchHit>, progress: Float) {
        // Order by page starting from where the search began; keep document order for navigation.
        val sorted = list.sortedWith(compareBy({ it.page }, { it.start }))
        val current = hits.getOrNull(hitIndex)
        hits = sorted
        searchProgress = progress
        view.searchHits = sorted
        if (current != null) {
            hitIndex = sorted.indexOfFirst { it.page == current.page && it.start == current.start }
            view.currentHit = hitIndex
        }
        if (hitIndex < 0 && sorted.isNotEmpty()) {
            val first = sorted.indexOfFirst { it.page >= page }.let { if (it < 0) 0 else it }
            goToHit(first)
        }
    }

    fun goToHit(i: Int) {
        if (hits.isEmpty()) return
        val idx = ((i % hits.size) + hits.size) % hits.size
        hitIndex = idx
        view.currentHit = idx
        val h = hits[idx]
        val r = com.pdfviewer.app.annot.union(h.rects)
        view.scrollToRect(h.page, r, onlyIfHidden = true)
    }

    fun nextHit() = goToHit(hitIndex + 1)
    fun prevHit() = goToHit(hitIndex - 1)

    // ================================================================= selection actions

    fun copySelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PDF text", text))
            msg("Copied")
            view.clearSelection()
        }
    }

    fun shareSelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel)
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            activity.startActivity(Intent.createChooser(i, "Share text"))
            view.clearSelection()
        }
    }

    fun searchSelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel).trim()
            view.clearSelection()
            query = text.take(120)
            openSearch()
            runSearch(query)
        }
    }

    fun webSearchSelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel).trim()
            view.clearSelection()
            val i = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", text) }
            runCatching { activity.startActivity(i) }.onFailure {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(text))))
            }
        }
    }

    fun readSelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel)
            view.clearSelection()
            tts.readText(text)
        }
    }

    fun markupSelection(kind: MarkupKind) {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel).trim()
            val color = when (kind) {
                MarkupKind.HIGHLIGHT -> Settings.toolColor(Tool.HIGHLIGHT.name, 0xFFFFEB3B.toInt())
                MarkupKind.UNDERLINE -> Settings.toolColor(Tool.UNDERLINE.name, 0xFF2E7D32.toInt())
                MarkupKind.STRIKEOUT -> Settings.toolColor(Tool.STRIKEOUT.name, 0xFFE53935.toInt())
                MarkupKind.SQUIGGLY -> Settings.toolColor(Tool.SQUIGGLY.name, 0xFFE53935.toInt())
            }
            onItemAdded(MarkupItem(newId(), sel.page, kind, com.pdfviewer.app.view.mergeLineRects(sel.rects), color, text))
            view.clearSelection()
            msg("Added. Tap Save to keep it in the file.")
        }
    }

    fun commentOnSelection() {
        val sel = selection ?: return
        scope.launch {
            val text = view.selectionText(sel).trim()
            val item = MarkupItem(newId(), sel.page, MarkupKind.HIGHLIGHT, com.pdfviewer.app.view.mergeLineRects(sel.rects),
                Settings.toolColor(Tool.HIGHLIGHT.name, 0xFFFFEB3B.toInt()), text)
            onItemAdded(item)
            view.clearSelection()
            dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.MARKUP_NOTE, "", sel.page, 0f, 0f, itemId = item.id)
        }
    }

    // ================================================================= bookmarks

    fun isBookmarked(p: Int) = bookmarks.any { it.page == p }

    fun toggleBookmark(p: Int = page) {
        val u = uri?.toString() ?: return
        bookmarks = if (isBookmarked(p)) bookmarks.filter { it.page != p }
        else (bookmarks + UserBookmark(p, "Page ${p + 1}", System.currentTimeMillis())).sortedBy { it.page }
        Library.setBookmarks(u, bookmarks)
        msg(if (isBookmarked(p)) "Bookmark added" else "Bookmark removed")
    }

    fun renameBookmark(b: UserBookmark, title: String) {
        val u = uri?.toString() ?: return
        bookmarks = bookmarks.map { if (it == b) it.copy(title = title) else it }
        Library.setBookmarks(u, bookmarks)
    }

    // ================================================================= view settings

    fun setColorModeIndex(i: Int) {
        colorMode = i; Settings.colorMode = i
        view.colorMode = PdfView.ColorMode.entries[i]
    }

    fun setHorizontalScroll(h: Boolean) {
        horizontal = h; Settings.horizontalScroll = h
        view.scrollMode = if (h) PdfView.ScrollMode.HORIZONTAL else PdfView.ScrollMode.VERTICAL
    }

    fun setKeepScreenOnPref(v: Boolean) { keepScreenOn = v; Settings.keepScreenOn = v }

    fun setHighlightFields(v: Boolean) { Settings.highlightFields = v; view.highlightFormFields = v }

    fun share() {
        val f = workFile ?: return
        scope.launch {
            if (items.isNotEmpty()) flushItems()
            runCatching { Files.share(activity, f, name) }.onFailure { msg("Couldn't share: ${it.message}") }
        }
    }

    fun print() {
        val f = workFile ?: return
        scope.launch {
            if (items.isNotEmpty()) flushItems()
            runCatching { Printer.print(activity, f, name, pageCount) }.onFailure { msg("Printing unavailable: ${it.message}") }
        }
    }

    fun startReadAloud() {
        tts.readPages(page, pageCount) { pageText(it) }
    }
}
