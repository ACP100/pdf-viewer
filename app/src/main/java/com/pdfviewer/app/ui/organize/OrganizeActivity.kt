package com.pdfviewer.app.ui.organize

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.pdf.PageSpec
import com.pdfviewer.app.pdf.PdfEditor
import com.pdfviewer.app.pdf.PdfEngine
import com.pdfviewer.app.ui.theme.AppTheme
import com.pdfviewer.app.ui.viewer.ActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class OrganizeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_NAME = "name"
        const val EXTRA_OUT = "out"
        private val ids = AtomicLong(1)
    }

    data class Entry(
        val id: Long = ids.getAndIncrement(),
        val source: Int,
        val rotate: Int = 0,
        val blank: Boolean = false,
        val file: File? = null,
        val fileIndex: Int = 0,
    )

    private lateinit var src: File
    private var password: String? = null
    private var name = "document.pdf"
    private val engines = HashMap<String, PdfEngine>()
    private val entries = ArrayList<Entry>()
    private val selected = HashSet<Long>()
    private val thumbs = LruCache<String, Bitmap>(32 * 1024 * 1024)
    private lateinit var adapter: PageAdapter
    private var originalCount = 0

    private var busy by mutableStateOf(false)
    private var selCount by mutableIntStateOf(0)
    private var changed by mutableStateOf(false)
    private var confirmExit by mutableStateOf(false)
    private var countState by mutableIntStateOf(0)
    private val snackbar = SnackbarHostState()

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            busy = true
            try {
                val f = File(Files.tempDir(this@OrganizeActivity), "insert_${System.nanoTime()}.pdf")
                withContext(Dispatchers.IO) { Files.copyFromUri(this@OrganizeActivity, uri, f) }
                val e = PdfEngine.open(this@OrganizeActivity, f, null)
                engines[f.absolutePath] = e
                val at = insertionPoint()
                val newEntries = (0 until e.pageCount).map { Entry(source = -1, file = f, fileIndex = it) }
                entries.addAll(at, newEntries)
                adapter.notifyItemRangeInserted(at, newEntries.size)
                markChanged()
                snackbar.showSnackbar("Inserted ${newEntries.size} page(s)")
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't insert: ${e.message ?: "file is protected or damaged"}")
            } finally { busy = false }
        }
    }

    private var extractOut: File? = null
    private val saveExtract = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = extractOut ?: return@registerForActivityResult
        extractOut = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Files.writeBack(this@OrganizeActivity, f, uri) }
            snackbar.showSnackbar(if (ok) "Extracted pages saved" else "Couldn't save")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        password = intent.getStringExtra(EXTRA_PASSWORD)
        name = intent.getStringExtra(EXTRA_NAME) ?: name
        adapter = PageAdapter()

        lifecycleScope.launch {
            busy = true
            try {
                val e = PdfEngine.open(this@OrganizeActivity, src, password)
                engines[src.absolutePath] = e
                originalCount = e.pageCount
                entries.addAll((0 until e.pageCount).map { Entry(source = it) })
                adapter.notifyDataSetChanged()
                countState = entries.size
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't open document"); finish()
            } finally { busy = false }
        }

        setContent {
            AppTheme {
                BackHandler { if (changed) confirmExit = true else finish() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (changed) confirmExit = true else finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Column(Modifier.weight(1f)) {
                                Text("Organize pages", style = MaterialTheme.typography.titleMedium)
                                Text(if (selCount > 0) "$selCount selected · $countState pages" else "$countState pages · long-press and drag to reorder",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { apply() }, enabled = changed && !busy) { Icon(Icons.Filled.Check, null); Text("Apply") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(factory = { ctx -> buildRecycler(ctx) }, modifier = Modifier.fillMaxSize())
                            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp)) {
                                val any = selCount > 0
                                ActionButton(Icons.AutoMirrored.Filled.RotateLeft, "Rotate L", enabled = !busy) { rotate(-90) }
                                ActionButton(Icons.AutoMirrored.Filled.RotateRight, "Rotate R", enabled = !busy) { rotate(90) }
                                ActionButton(Icons.Filled.Delete, "Delete", enabled = any && !busy) { delete() }
                                ActionButton(Icons.Filled.ContentCopy, "Duplicate", enabled = any && !busy) { duplicate() }
                                ActionButton(Icons.Filled.AddBox, "Blank page", enabled = !busy) { insertBlank() }
                                ActionButton(Icons.Filled.FileOpen, "Insert PDF", enabled = !busy) { pickPdf.launch(arrayOf("application/pdf")) }
                                ActionButton(Icons.Filled.UploadFile, "Extract", enabled = any && !busy) { extract() }
                                ActionButton(Icons.Filled.SelectAll, if (selected.size == entries.size && entries.isNotEmpty()) "None" else "All", enabled = !busy) { toggleAll() }
                            }
                        }
                    }
                }
                if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
                    title = { Text("Discard page changes?") },
                    confirmButton = { TextButton(onClick = { confirmExit = false; apply() }) { Text("Apply") } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Discard") } })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engines.values.forEach { it.close() }
    }

    private fun markChanged() { changed = true; countState = entries.size; selCount = selected.size }

    private fun insertionPoint(): Int {
        val idx = entries.indexOfLast { it.id in selected }
        return if (idx >= 0) idx + 1 else entries.size
    }

    private fun targets(): List<Int> {
        val sel = entries.indices.filter { entries[it].id in selected }
        return sel.ifEmpty { entries.indices.toList() }
    }

    private fun rotate(delta: Int) {
        for (i in targets()) {
            val e = entries[i]
            entries[i] = e.copy(rotate = ((e.rotate + delta) % 360 + 360) % 360)
            adapter.notifyItemChanged(i)
        }
        markChanged()
    }

    private fun delete() {
        if (selected.size >= entries.size) {
            lifecycleScope.launch { snackbar.showSnackbar("A PDF needs at least one page") }; return
        }
        entries.removeAll { it.id in selected }
        selected.clear()
        adapter.notifyDataSetChanged()
        markChanged()
    }

    private fun duplicate() {
        val sel = entries.indices.filter { entries[it].id in selected }.reversed()
        for (i in sel) {
            val e = entries[i]
            entries.add(i + 1, e.copy(id = ids.getAndIncrement()))
            adapter.notifyItemInserted(i + 1)
        }
        markChanged()
    }

    private fun insertBlank() {
        val at = insertionPoint()
        entries.add(at, Entry(source = -1, blank = true))
        adapter.notifyItemInserted(at)
        markChanged()
    }

    private fun toggleAll() {
        if (selected.size == entries.size) selected.clear() else { selected.clear(); entries.forEach { selected += it.id } }
        adapter.notifyDataSetChanged()
        selCount = selected.size
    }

    private fun specs(list: List<Entry>) = list.map {
        PageSpec(source = it.source, rotate = it.rotate, blank = it.blank, insertFile = it.file, insertIndex = it.fileIndex)
    }

    private fun apply() {
        lifecycleScope.launch {
            busy = true
            try {
                val out = File(Files.tempDir(this@OrganizeActivity), "organized_${System.nanoTime()}.pdf")
                withContext(Dispatchers.IO) { PdfEditor.rearrange(src, password, specs(entries), out) }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
                finish()
            } catch (e: Exception) {
                snackbar.showSnackbar("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    private fun extract() {
        val list = entries.filter { it.id in selected }
        if (list.isEmpty()) return
        lifecycleScope.launch {
            busy = true
            try {
                val out = File(Files.tempDir(this@OrganizeActivity), "extract_${System.nanoTime()}.pdf")
                withContext(Dispatchers.IO) { PdfEditor.rearrange(src, password, specs(list), out) }
                extractOut = out
                saveExtract.launch(Files.baseName(name) + " (pages).pdf")
            } catch (e: Exception) {
                snackbar.showSnackbar("Failed: ${e.message}")
            } finally { busy = false }
        }
    }

    // ------------------------------------------------------------------ RecyclerView grid

    private fun buildRecycler(ctx: android.content.Context): RecyclerView {
        val rv = RecyclerView(ctx)
        val density = ctx.resources.displayMetrics.density
        val span = max(2, (ctx.resources.displayMetrics.widthPixels / (124 * density)).toInt())
        rv.layoutManager = GridLayoutManager(ctx, span)
        rv.adapter = adapter
        rv.setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        rv.clipToPadding = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                if (from < to) for (i in from until to) Collections.swap(entries, i, i + 1)
                else for (i in from downTo to + 1) Collections.swap(entries, i, i - 1)
                adapter.notifyItemMoved(from, to)
                markChanged()
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.notifyDataSetChanged() // refresh page numbers
            }
        }).attachToRecyclerView(rv)
        return rv
    }

    private inner class Holder(val root: FrameLayout, val image: ImageView, val label: TextView, val check: TextView) : RecyclerView.ViewHolder(root) {
        var job: Job? = null
    }

    private inner class PageAdapter : RecyclerView.Adapter<Holder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = entries[position].id
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val root = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (168 * d).toInt())
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            val label = TextView(ctx).apply { gravity = Gravity.CENTER; textSize = 12f }
            col.addView(image); col.addView(label)
            root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val check = TextView(ctx).apply {
                text = "✓"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#C62828")) }
            }
            root.addView(check, FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), Gravity.TOP or Gravity.END))
            return Holder(root, image, label, check)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = entries[position]
            val sel = e.id in selected
            h.check.visibility = if (sel) android.view.View.VISIBLE else android.view.View.GONE
            h.root.foreground = if (sel) GradientDrawable().apply { setStroke((3 * h.root.resources.displayMetrics.density).toInt(), Color.parseColor("#C62828")) } else null
            val origin = when {
                e.blank -> " (blank)"
                e.file != null -> " (inserted)"
                e.source >= 0 && e.source != position -> " (was ${e.source + 1})"
                else -> ""
            }
            h.label.text = "${position + 1}$origin" + if (e.rotate != 0) " ↻${e.rotate}°" else ""
            h.root.setOnClickListener {
                if (!selected.add(e.id)) selected.remove(e.id)
                selCount = selected.size
                notifyItemChanged(h.bindingAdapterPosition)
            }
            h.job?.cancel()
            h.image.setImageBitmap(null)
            if (e.blank) { h.image.setImageBitmap(blankBitmap(e.rotate)); return }
            val file = e.file ?: src
            val index = if (e.file != null) e.fileIndex else e.source
            val key = "${file.absolutePath}:$index"
            val cached = thumbs.get(key)
            if (cached != null) { h.image.setImageBitmap(rotated(cached, e.rotate)); return }
            h.job = lifecycleScope.launch {
                val engine = engines[file.absolutePath] ?: return@launch
                val bmp = engine.renderThumbnail(index, 200) ?: return@launch
                thumbs.put(key, bmp)
                h.image.setImageBitmap(rotated(bmp, e.rotate))
            }
        }
    }

    private fun rotated(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun blankBitmap(rot: Int): Bitmap {
        val w = if (rot % 180 == 0) 141 else 200
        val h = if (rot % 180 == 0) 200 else 141
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    }
}
