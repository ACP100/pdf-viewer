package com.pdfviewer.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.FindFlags
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** Size of a page in "display points": rotation applied, origin top-left, y pointing down. */
data class PageSize(val width: Int, val height: Int)

/** Maps between PDF user space (y up) and display points (y down, rotation applied). */
class PageGeometry(val userToDisplay: Matrix) {
    val displayToUser: Matrix = Matrix().also { userToDisplay.invert(it) }

    fun toDisplay(r: RectF): RectF = RectF(r).also { userToDisplay.mapRect(it) }
    fun toUser(r: RectF): RectF = RectF(r).also { displayToUser.mapRect(it) }
    fun toUser(x: Float, y: Float): PointF {
        val p = floatArrayOf(x, y); displayToUser.mapPoints(p); return PointF(p[0], p[1])
    }
    fun toDisplay(x: Float, y: Float): PointF {
        val p = floatArrayOf(x, y); userToDisplay.mapPoints(p); return PointF(p[0], p[1])
    }
}

data class OutlineItem(val title: String, val page: Int, val level: Int, val children: List<OutlineItem>)
data class PageLink(val bounds: RectF /* display points */, val page: Int?, val uri: String?)
data class SearchHit(val page: Int, val start: Int, val count: Int, val rects: List<RectF> /* display points */)
data class DocMeta(
    val title: String?, val author: String?, val subject: String?, val keywords: String?,
    val creator: String?, val producer: String?, val created: String?, val modified: String?,
)

class PasswordRequiredException(val wrongPassword: Boolean) : Exception()

/**
 * Thin, thread-confined wrapper around pdfium. Every call that touches native state must run on
 * [dispatcher]; the suspend functions here do that for callers.
 */
class PdfEngine private constructor(
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor,
    val file: File,
    val password: String?,
    val pageSizes: List<PageSize>,
) : Closeable {

    val pageCount: Int get() = pageSizes.size
    @Volatile var closed = false
        private set

    private val pages = object : LinkedHashMap<Int, PdfPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>?): Boolean {
            if (size > 24 && eldest != null) {
                textPages.remove(eldest.key)?.let { runCatching { it.close() } }
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }
    private val textPages = HashMap<Int, PdfTextPage>()
    private val geometries = ConcurrentHashMap<Int, PageGeometry>()
    private val linkCache = ConcurrentHashMap<Int, List<PageLink>>()

    companion object {
        /** Single thread: pdfium is not thread safe. */
        val dispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdfium").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        @Volatile private var core: PdfiumCore? = null

        fun core(context: Context): PdfiumCore =
            core ?: synchronized(this) { core ?: PdfiumCore(context.applicationContext).also { core = it } }

        suspend fun open(context: Context, file: File, password: String?): PdfEngine =
            withContext(dispatcher) {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val doc = try {
                    if (password.isNullOrEmpty()) core(context).newDocument(pfd)
                    else core(context).newDocument(pfd, password)
                } catch (e: PdfPasswordException) {
                    runCatching { pfd.close() }
                    throw PasswordRequiredException(!password.isNullOrEmpty())
                } catch (e: Exception) {
                    runCatching { pfd.close() }
                    if (e.message?.contains("password", ignoreCase = true) == true) {
                        throw PasswordRequiredException(!password.isNullOrEmpty())
                    }
                    throw e
                }
                try {
                    val count = doc.getPageCount()
                    val sizes = ArrayList<PageSize>(count)
                    val emptyMap = HashMap<Int, PdfDocument.PageCount>()
                    for (i in 0 until count) {
                        // PdfPage.getPageSize() only reads the page box by index (FPDF_GetPageSizeByIndex)
                        // and never touches the page pointer, so a handle-less PdfPage avoids
                        // parsing every page's content up front. Never close this placeholder.
                        val s = runCatching { PdfPage(doc, i, 0L, emptyMap).getPageSize(72) }
                            .getOrElse { doc.openPage(i).use { p -> p.getPageSize(72) } }
                        sizes.add(PageSize(max(1, s.width), max(1, s.height)))
                    }
                    PdfEngine(doc, pfd, file, password, sizes)
                } catch (e: Exception) {
                    runCatching { doc.close() }
                    runCatching { pfd.close() }
                    throw e
                }
            }
    }

    private fun page(index: Int): PdfPage {
        check(!closed) { "closed" }
        return pages.getOrPut(index) { doc.openPage(index) }
    }

    private fun textPage(index: Int): PdfTextPage =
        textPages.getOrPut(index) { page(index).openTextPage() }

    // ---------------------------------------------------------------- geometry

    /** Must be called on [dispatcher]. */
    fun geometryBlocking(index: Int): PageGeometry = geometries.getOrPut(index) {
        val p = page(index)
        val ps = pageSizes[index]
        val k = 10 // extra precision: pdfium returns integer device coordinates
        val w = ps.width * k
        val h = ps.height * k
        val o = p.mapPageCoordsToDevice(0, 0, w, h, 0, 0.0, 0.0)
        val ax = p.mapPageCoordsToDevice(0, 0, w, h, 0, 1000.0, 0.0)
        val ay = p.mapPageCoordsToDevice(0, 0, w, h, 0, 0.0, 1000.0)
        val m = Matrix()
        m.setValues(
            floatArrayOf(
                (ax.x - o.x) / 1000f / k, (ay.x - o.x) / 1000f / k, o.x.toFloat() / k,
                (ax.y - o.y) / 1000f / k, (ay.y - o.y) / 1000f / k, o.y.toFloat() / k,
                0f, 0f, 1f,
            )
        )
        PageGeometry(m)
    }

    fun cachedGeometry(index: Int): PageGeometry? = geometries[index]

    suspend fun geometry(index: Int): PageGeometry = withContext(dispatcher) { geometryBlocking(index) }

    // ---------------------------------------------------------------- rendering

    /**
     * Renders [index] into [bitmap]. The page is drawn at [pageW] x [pageH] pixels with its
     * top-left corner at ([offX], [offY]) inside the bitmap (offsets may be negative so only a
     * region of the page is produced).
     */
    fun renderBlocking(index: Int, bitmap: Bitmap, offX: Int, offY: Int, pageW: Int, pageH: Int, annotations: Boolean = true) {
        if (closed) return
        val p = page(index)
        geometryBlocking(index)
        p.renderPageBitmap(bitmap, offX, offY, pageW, pageH, annotations, false, -1, -1)
    }

    suspend fun renderThumbnail(index: Int, width: Int): Bitmap? = withContext(dispatcher) {
        if (closed) return@withContext null
        val ps = pageSizes[index]
        val h = max(1, (width.toFloat() * ps.height / ps.width).toInt())
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        runCatching { renderBlocking(index, bmp, 0, 0, width, h) }.onFailure { return@withContext null }
        bmp
    }

    // ---------------------------------------------------------------- text

    fun charCountBlocking(index: Int): Int = textPage(index).textPageCountChars()

    fun textBlocking(index: Int, start: Int = 0, count: Int = -1): String {
        val tp = textPage(index)
        val n = tp.textPageCountChars()
        if (n <= 0) return ""
        val s = start.coerceIn(0, n)
        val c = if (count < 0) n - s else min(count, n - s)
        if (c <= 0) return ""
        return (tp.textPageGetText(s, c) ?: "").replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n')
    }

    suspend fun pageText(index: Int): String = withContext(dispatcher) {
        if (closed) "" else runCatching { textBlocking(index) }.getOrDefault("")
    }

    fun unicodeBlocking(index: Int, charIndex: Int): Char = textPage(index).textPageGetUnicode(charIndex)

    /** Index of the character at a display-point position, or -1. */
    fun charIndexAtBlocking(index: Int, dx: Float, dy: Float, tolerance: Float): Int {
        val g = geometryBlocking(index)
        val u = g.toUser(dx, dy)
        return textPage(index).textPageGetCharIndexAtPos(u.x.toDouble(), u.y.toDouble(), tolerance.toDouble(), tolerance.toDouble())
    }

    /** Rectangles (display points) covering [count] chars starting at [start]. */
    fun rectsBlocking(index: Int, start: Int, count: Int): List<RectF> {
        if (count <= 0) return emptyList()
        val tp = textPage(index)
        val g = geometryBlocking(index)
        val n = tp.textPageCountRects(start, count)
        val out = ArrayList<RectF>(max(0, n))
        for (i in 0 until n) {
            val r = tp.textPageGetRect(i) ?: continue
            out.add(g.toDisplay(r.normalizedPdf()))
        }
        return out
    }

    fun charBoxBlocking(index: Int, charIndex: Int): RectF? {
        val r = textPage(index).textPageGetCharBox(charIndex) ?: return null
        return geometryBlocking(index).toDisplay(r.normalizedPdf())
    }

    /** Expand a char index to word boundaries. Returns [start, endExclusive). */
    fun wordAtBlocking(index: Int, charIndex: Int): IntRange {
        val tp = textPage(index)
        val n = tp.textPageCountChars()
        if (charIndex !in 0 until n) return IntRange.EMPTY
        fun isWord(i: Int): Boolean {
            val c = tp.textPageGetUnicode(i)
            return c.isLetterOrDigit() || c == '\'' || c == '-' || c == '_'
        }
        if (!isWord(charIndex)) return charIndex until charIndex + 1
        var s = charIndex
        while (s > 0 && isWord(s - 1)) s--
        var e = charIndex
        while (e < n - 1 && isWord(e + 1)) e++
        return s until e + 1
    }

    // ---------------------------------------------------------------- search

    suspend fun search(
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean,
        startPage: Int,
        onHit: (SearchHit) -> Unit,
        onProgress: (Int) -> Unit,
    ) = withContext(dispatcher) {
        if (query.isBlank() || closed) return@withContext
        val flags = buildSet {
            if (matchCase) add(FindFlags.MatchCase)
            if (wholeWord) add(FindFlags.MatchWholeWord)
        }
        for (k in 0 until pageCount) {
            coroutineContext.ensureActive()
            if (closed) return@withContext
            val i = (startPage + k) % pageCount
            runCatching {
                val tp = textPage(i)
                val fr = tp.findStart(query, flags, 0) ?: return@runCatching
                try {
                    while (fr.findNext()) {
                        val s = fr.getSchResultIndex()
                        val c = fr.getSchCount()
                        if (c <= 0) continue
                        onHit(SearchHit(i, s, c, rectsBlocking(i, s, c)))
                    }
                } finally {
                    runCatching { fr.closeFind() }
                }
            }
            onProgress(k + 1)
        }
    }

    // ---------------------------------------------------------------- links / outline / meta

    fun linksBlocking(index: Int): List<PageLink> = linkCache.getOrPut(index) {
        val g = geometryBlocking(index)
        runCatching {
            page(index).getPageLinks().map { l ->
                PageLink(g.toDisplay(l.bounds.normalizedPdf()), l.destPageIdx, l.uri)
            }
        }.getOrDefault(emptyList())
    }

    fun cachedLinks(index: Int): List<PageLink>? = linkCache[index]

    suspend fun outline(): List<OutlineItem> = withContext(dispatcher) {
        fun conv(b: PdfDocument.Bookmark, level: Int): OutlineItem =
            OutlineItem(b.title ?: "", b.pageIdx.toInt(), level, b.children.map { conv(it, level + 1) })
        runCatching { doc.getTableOfContents().map { conv(it, 0) } }.getOrDefault(emptyList())
    }

    suspend fun meta(): DocMeta = withContext(dispatcher) {
        val m = doc.getDocumentMeta()
        DocMeta(m.title, m.author, m.subject, m.keywords, m.creator, m.producer, m.creationDate, m.modDate)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Close on the pdfium thread to avoid racing in-flight renders.
        dispatcher.executor.execute {
            textPages.values.forEach { runCatching { it.close() } }
            textPages.clear()
            pages.values.forEach { runCatching { it.close() } }
            pages.clear()
            runCatching { doc.close() }
            runCatching { pfd.close() }
        }
    }
}

/** pdfium returns rectangles in PDF space where "top" may be numerically larger than "bottom". */
fun RectF.normalizedPdf(): RectF = RectF(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
