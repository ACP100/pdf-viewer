package com.pdfviewer.app.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.pdfviewer.app.annot.ExistingAnnot
import com.pdfviewer.app.annot.FieldType
import com.pdfviewer.app.annot.FormFieldInfo
import com.pdfviewer.app.annot.InkItem
import com.pdfviewer.app.annot.Item
import com.pdfviewer.app.annot.ItemPainter
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.ShapeItem
import com.pdfviewer.app.annot.ShapeKind
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.Tool
import com.pdfviewer.app.annot.ToolStyle
import com.pdfviewer.app.annot.newId
import com.pdfviewer.app.pdf.PageLink
import com.pdfviewer.app.pdf.PdfEngine
import com.pdfviewer.app.pdf.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A continuous / paged PDF canvas with pinch zoom, fling, high resolution patches when zoomed,
 * text selection, and the drawing interactions used by the annotation tools.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context) : View(context) {

    enum class ScrollMode { VERTICAL, HORIZONTAL }
    enum class ColorMode { NORMAL, NIGHT, SEPIA }

    data class TextSelection(val page: Int, val start: Int, val end: Int, val rects: List<RectF>) {
        val count get() = end - start
    }

    interface Listener {
        fun onPageChanged(page: Int) {}
        fun onPagesVisible(range: IntRange) {}
        fun onTap() {}
        fun onLink(link: PageLink) {}
        fun onSelectionChanged(selection: TextSelection?) {}
        fun onItemAdded(item: Item) {}
        fun onItemUpdated(item: Item) {}
        fun onItemsErased(ids: Set<Long>) {}
        fun onItemSelected(item: Item?) {}
        fun onPlace(page: Int, x: Float, y: Float) {}
        fun onExistingAnnotationTap(annot: ExistingAnnot) {}
        fun onFormFieldTap(field: FormFieldInfo) {}
        fun onZoomChanged(zoom: Float) {}
    }

    var listener: Listener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = resources.displayMetrics.density

    // ------------------------------------------------------------------ document & layout
    var engine: PdfEngine? = null
        private set
    private var pageCount = 0
    private var pl = FloatArray(0)
    private var pt = FloatArray(0)
    private var pw = FloatArray(0)
    private var ph = FloatArray(0)
    private var contentW = 0f
    private var contentH = 0f
    @Volatile private var layoutGen = 0
    private val pageGap get() = 8 * density
    /** Screen space (px) covered by overlay toolbars; content can scroll clear of them. */
    private var insetTop = 0f
    private var insetBottom = 0f

    fun setInsets(top: Float, bottom: Float) {
        val t = max(insetTop, top); val b = max(insetBottom, bottom)
        if (t == insetTop && b == insetBottom) return
        val pos = if (laidOut) position() else null
        insetTop = t; insetBottom = b
        if (scrollMode == ScrollMode.HORIZONTAL) relayout()
        if (pos != null) restorePosition(pos.first, pos.second) else { clampOffsets(); invalidate() }
    }
    private val sidePad get() = if (scrollMode == ScrollMode.VERTICAL) 4 * density else 8 * density

    var scrollMode = ScrollMode.VERTICAL
        set(value) {
            if (field == value) return
            val p = currentPage
            field = value
            relayout(); goToPage(p)
        }

    var colorMode = ColorMode.NORMAL
        set(value) {
            field = value
            val f = when (value) {
                ColorMode.NORMAL -> null
                ColorMode.NIGHT -> ColorMatrixColorFilter(
                    ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
                )
                ColorMode.SEPIA -> ColorMatrixColorFilter(
                    ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)).apply {
                        // Soften: blend 55% sepia with 45% original so text stays crisp.
                        val id = ColorMatrix()
                        val a = this.array; val b = id.array
                        for (i in a.indices) a[i] = a[i] * 0.55f + b[i] * 0.45f
                        set(a)
                    }
                )
            }
            bitmapPaint.colorFilter = f
            painter.filter = f
            painter.night = value == ColorMode.NIGHT
            invalidate()
        }

    var zoom = 1f
        private set
    private var offX = 0f
    private var offY = 0f
    private val minZoom get() = if (scrollMode == ScrollMode.VERTICAL) 0.5f else 1f
    private val maxZoom = 10f

    var currentPage = 0
        private set
    private var lastVisible = IntRange.EMPTY

    // ------------------------------------------------------------------ overlays
    var tool: Tool = Tool.NONE
        set(value) {
            field = value
            cancelToolGesture()
            if (value != Tool.NONE) { clearSelection() }
            invalidate()
        }
    var toolStyle = ToolStyle()

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            itemsByPage = value.groupBy { it.page }
            if (selectedItem != null) selectedItem = value.firstOrNull { it.id == selectedItem?.id }
            invalidate()
        }
    private var itemsByPage: Map<Int, List<Item>> = emptyMap()

    var selectedItem: Item? = null
        private set

    var searchHits: List<SearchHit> = emptyList()
        set(value) { field = value; hitsByPage = value.withIndex().groupBy { it.value.page }; invalidate() }
    private var hitsByPage: Map<Int, List<IndexedValue<SearchHit>>> = emptyMap()
    var currentHit = -1
        set(value) { field = value; invalidate() }

    var formFields: Map<Int, List<FormFieldInfo>> = emptyMap()
        set(value) { field = value; invalidate() }
    var existingAnnots: Map<Int, List<ExistingAnnot>> = emptyMap()
    var highlightFormFields = true
        set(value) { field = value; invalidate() }

    var selection: TextSelection? = null
        private set

    // ------------------------------------------------------------------ rendering state
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val painter = ItemPainter()
    private val baseCache: LruCache<Int, Bitmap>
    private val baseRequested = HashSet<Long>()
    @Volatile private var wantedRange = IntRange.EMPTY

    private class Patch(val zoom: Float, val rect: RectF, val bmp: Bitmap, val gen: Int)
    private val patches = HashMap<Int, Patch>()
    private var patchJob: Job? = null
    private val patchRunnable = Runnable { renderPatches() }

    init {
        val maxMem = Runtime.getRuntime().maxMemory()
        val bytes = (maxMem / 4).coerceAtMost(192L * 1024 * 1024).toInt()
        baseCache = object : LruCache<Int, Bitmap>(bytes) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        isFocusable = true
        contentDescription = "PDF document"
    }

    // ------------------------------------------------------------------ paints
    private val bgPaint = Paint()
    private val pageFill = Paint()
    private val shadowPaint = Paint().apply { color = 0x26000000 }
    private val hitPaint = Paint().apply { color = 0x66FFC107 }
    private val curHitPaint = Paint().apply { color = 0x99FF6D00.toInt() }
    private val selPaint = Paint().apply { color = 0x553D7BFD }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2962FF.toInt() }
    private val fieldPaint = Paint().apply { color = 0x2A2196F3 }
    private val fieldStroke = Paint().apply { color = 0x662196F3; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val selItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2962FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt(); textAlign = Paint.Align.CENTER; textSize = 16 * resources.displayMetrics.density
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 12 * resources.displayMetrics.density
        isFakeBoldText = true
    }

    // ------------------------------------------------------------------ public API

    fun setEngine(e: PdfEngine?, page: Int = 0, keepPosition: Boolean = false) {
        val pos = if (keepPosition && engine != null) position() else null
        engine = e
        pageCount = e?.pageCount ?: 0
        clearCaches()
        selection = null
        selectedItem = null
        relayout()
        if (pos != null) restorePosition(pos.first.coerceAtMost(pageCount - 1), pos.second)
        else goToPage(page.coerceIn(0, max(0, pageCount - 1)))
        invalidate()
    }

    fun painterForget(path: String) { painter.forget(path); invalidate() }

    fun release() {
        scope.cancel()
        clearCaches()
    }

    /** True once page rects exist for the current document and view size. */
    private val laidOut get() = pageCount > 0 && pt.size == pageCount && width > 0 && height > 0
    /** Position to apply once the view has been laid out. */
    private var pendingPos: Pair<Int, Float>? = null

    fun goToPage(page: Int) {
        if (pageCount == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        if (!laidOut) { pendingPos = p to 0f; currentPage = p; return }
        if (scrollMode == ScrollMode.VERTICAL) {
            offY = (pt[p] - pageGap / 2) * zoom - insetTop
        } else {
            offX = p * slotW() * zoom
        }
        clampOffsets(); viewportChanged()
    }

    fun nextPage() = goToPage(currentPage + 1)
    fun prevPage() = goToPage(currentPage - 1)

    /** Brings a rect (page display points) into view, centred. */
    fun scrollToRect(page: Int, r: RectF, onlyIfHidden: Boolean = true) {
        if (page !in 0 until pageCount || !laidOut) return
        val s = scaleOf(page) * zoom
        val cx = pl[page] * zoom + r.centerX() * s
        val cy = pt[page] * zoom + r.centerY() * s
        val sx = cx - offX; val sy = cy - offY
        val margin = 48 * density
        val visible = sx in margin..(width - margin) && sy in margin..(height - margin)
        if (onlyIfHidden && visible) return
        if (scrollMode == ScrollMode.HORIZONTAL && zoom <= 1.02f) {
            offX = page * slotW() * zoom
        } else {
            offX = cx - width / 2f
        }
        offY = cy - height / 2f
        clampOffsets(); viewportChanged()
    }

    /** current page + fraction scrolled through it. */
    fun position(): Pair<Int, Float> {
        if (pageCount == 0) return 0 to 0f
        if (!laidOut) return pendingPos ?: (currentPage.coerceIn(0, pageCount - 1) to 0f)
        val p = currentPage.coerceIn(0, pageCount - 1)
        val frac = if (scrollMode == ScrollMode.VERTICAL) (((offY + insetTop) / zoom) - pt[p]) / max(1f, ph[p]) else 0f
        return p to frac
    }

    private fun restorePosition(page: Int, frac: Float) {
        if (pageCount == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        if (!laidOut) { pendingPos = p to frac; currentPage = p; return }
        pendingPos = null
        if (scrollMode == ScrollMode.VERTICAL) offY = (pt[p] + frac * ph[p]) * zoom - insetTop
        else offX = p * slotW() * zoom
        clampOffsets(); viewportChanged()
    }

    fun setZoom(z: Float) {
        animateZoom(z, width / 2f, height / 2f)
    }

    fun fitWidth() = animateZoom(1f, width / 2f, height / 2f)

    fun clearSelection() {
        if (selection != null) {
            selection = null
            listener?.onSelectionChanged(null)
            invalidate()
        }
    }

    fun selectItem(item: Item?) {
        selectedItem = item
        listener?.onItemSelected(item)
        invalidate()
    }

    fun selectAllOnPage(page: Int = currentPage) {
        val e = engine ?: return
        submitQuery(false) {
            val sel = withContext(PdfEngine.dispatcher) {
                val n = e.charCountBlocking(page)
                if (n <= 0) null else TextSelection(page, 0, n, e.rectsBlocking(page, 0, n))
            }
            selection = sel
            listener?.onSelectionChanged(sel)
            invalidate()
        }
    }

    suspend fun selectionText(sel: TextSelection): String {
        val e = engine ?: return ""
        return withContext(PdfEngine.dispatcher) { e.textBlocking(sel.page, sel.start, sel.count) }
    }

    /** Screen rect of a page-space rect, for anchoring popups. */
    fun screenRectOf(page: Int, r: RectF): RectF {
        val pr = pageScreenRect(page)
        val s = scaleOf(page) * zoom
        return RectF(pr.left + r.left * s, pr.top + r.top * s, pr.left + r.right * s, pr.top + r.bottom * s)
    }

    /** Page and display-point position of the centre of the viewport. */
    fun viewportCenterOnPage(): Triple<Int, Float, Float> {
        val p = currentPage
        val pr = pageScreenRect(p)
        val s = scaleOf(p) * zoom
        val sx = (width / 2f).coerceIn(pr.left, pr.right)
        val sy = (height / 2f).coerceIn(pr.top, pr.bottom)
        return Triple(p, (sx - pr.left) / s, (sy - pr.top) / s)
    }

    // ------------------------------------------------------------------ layout

    private fun slotW() = width.toFloat()

    /** pixels (at zoom 1) per display point for page i */
    private fun scaleOf(i: Int): Float {
        val e = engine ?: return 1f
        return pw[i] / e.pageSizes[i].width
    }

    private fun relayout() {
        val e = engine
        val vw = width.toFloat(); val vh = height.toFloat()
        layoutGen++
        clearCaches()
        if (e == null || vw <= 0f || vh <= 0f) { pageCount = e?.pageCount ?: 0; return }
        val n = e.pageCount
        pageCount = n
        pl = FloatArray(n); pt = FloatArray(n); pw = FloatArray(n); ph = FloatArray(n)
        if (scrollMode == ScrollMode.VERTICAL) {
            val avail = vw - 2 * sidePad
            var y = pageGap
            for (i in 0 until n) {
                val s = e.pageSizes[i]
                pl[i] = sidePad; pt[i] = y; pw[i] = avail; ph[i] = avail * s.height / s.width
                y += ph[i] + pageGap
            }
            contentW = vw; contentH = y
        } else {
            val aw = vw - 2 * sidePad; val ah = vh - 2 * sidePad - insetTop - insetBottom
            for (i in 0 until n) {
                val s = e.pageSizes[i]
                val k = min(aw / s.width, ah / s.height)
                pw[i] = s.width * k; ph[i] = s.height * k
                pl[i] = i * vw + (vw - pw[i]) / 2; pt[i] = insetTop + sidePad + (ah - ph[i]) / 2
            }
            contentW = n * vw; contentH = vh
        }
        zoom = zoom.coerceIn(minZoom, maxZoom)
        clampOffsets()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pos = pendingPos ?: if (pt.size == pageCount && pageCount > 0 && oldw > 0 && oldh > 0) position() else (currentPage to 0f)
        relayout()
        restorePosition(pos.first, pos.second)
    }

    private fun clampOffsets() {
        val cw = contentW * zoom; val ch = contentH * zoom
        offX = if (cw <= width) (cw - width) / 2f else offX.coerceIn(0f, cw - width)
        offY = if (scrollMode == ScrollMode.HORIZONTAL) {
            if (ch <= height) (ch - height) / 2f else offY.coerceIn(0f, ch - height)
        } else {
            val avail = height - insetTop - insetBottom
            if (ch <= avail) (ch - avail) / 2f - insetTop else offY.coerceIn(-insetTop, ch - height + insetBottom)
        }
    }

    private fun pageScreenRect(i: Int, out: RectF = RectF()): RectF {
        out.set(pl[i] * zoom - offX, pt[i] * zoom - offY, (pl[i] + pw[i]) * zoom - offX, (pt[i] + ph[i]) * zoom - offY)
        return out
    }

    private fun visibleRange(): IntRange {
        if (pageCount == 0 || pt.size != pageCount) return IntRange.EMPTY
        if (scrollMode == ScrollMode.VERTICAL) {
            val top = offY / zoom; val bottom = (offY + height) / zoom
            var lo = 0; var hi = pageCount - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (pt[mid] + ph[mid] < top) lo = mid + 1 else hi = mid }
            var last = lo
            while (last + 1 < pageCount && pt[last + 1] <= bottom) last++
            return lo..last
        } else {
            val sw = slotW()
            if (sw <= 0) return IntRange.EMPTY
            val first = ((offX / zoom) / sw).toInt().coerceIn(0, pageCount - 1)
            val last = (((offX + width) / zoom) / sw).toInt().coerceIn(0, pageCount - 1)
            return first..last
        }
    }

    private fun computeCurrentPage(): Int {
        if (pageCount == 0) return 0
        return if (scrollMode == ScrollMode.VERTICAL) {
            val c = (offY + insetTop + (height - insetTop) / 3f) / zoom
            var lo = 0; var hi = pageCount - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (pt[mid] + ph[mid] + pageGap / 2 < c) lo = mid + 1 else hi = mid }
            lo
        } else {
            (((offX + width / 2f) / zoom) / slotW()).toInt().coerceIn(0, pageCount - 1)
        }
    }

    /** Page under a screen point and the point in page display points. */
    private fun hitPage(x: Float, y: Float, clamp: Boolean = false): Triple<Int, Float, Float>? {
        val r = RectF()
        for (i in visibleRange()) {
            pageScreenRect(i, r)
            if (r.contains(x, y) || (clamp && i == currentPage)) {
                val s = scaleOf(i) * zoom
                val e = engine ?: return null
                val ps = e.pageSizes[i]
                return Triple(i, ((x - r.left) / s).coerceIn(0f, ps.width.toFloat()), ((y - r.top) / s).coerceIn(0f, ps.height.toFloat()))
            }
        }
        return null
    }

    private fun toPagePoint(page: Int, x: Float, y: Float, clampToPage: Boolean = true): Pair<Float, Float> {
        val r = pageScreenRect(page)
        val s = scaleOf(page) * zoom
        var px = (x - r.left) / s; var py = (y - r.top) / s
        if (clampToPage) {
            val ps = engine!!.pageSizes[page]
            px = px.coerceIn(0f, ps.width.toFloat()); py = py.coerceIn(0f, ps.height.toFloat())
        }
        return px to py
    }

    private fun viewportChanged() {
        if (!laidOut) return
        val p = computeCurrentPage()
        if (p != currentPage) { currentPage = p; listener?.onPageChanged(p) }
        val vr = visibleRange()
        wantedRange = vr
        if (vr != lastVisible) { lastVisible = vr; listener?.onPagesVisible(vr) }
        showThumb()
        schedulePatches()
        invalidate()
    }

    // ------------------------------------------------------------------ bitmaps

    private fun clearCaches() {
        baseCache.evictAll()
        baseRequested.clear()
        patches.clear()
        patchJob?.cancel()
    }

    private fun baseSize(i: Int): Pair<Int, Int> {
        var w = pw[i]; var h = ph[i]
        val maxW = 1800f
        if (w > maxW) { h *= maxW / w; w = maxW }
        val maxArea = 4_500_000f
        if (w * h > maxArea) { val k = sqrt(maxArea / (w * h)); w *= k; h *= k }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    private fun requestBase(i: Int) {
        val e = engine ?: return
        val gen = layoutGen
        val key = (gen.toLong() shl 32) or i.toLong()
        if (!baseRequested.add(key)) return
        val (bw, bh) = baseSize(i)
        scope.launch {
            val bmp = withContext(PdfEngine.dispatcher) {
                val wr = wantedRange
                if (gen != layoutGen || e.closed || wr.isEmpty() || i < wr.first - 2 || i > wr.last + 2) null
                else runCatching {
                    Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        e.renderBlocking(i, it, 0, 0, bw, bh)
                        e.linksBlocking(i)
                    }
                }.getOrNull()
            }
            baseRequested.remove(key)
            if (bmp != null && gen == layoutGen && engine === e) {
                baseCache.put(i, bmp)
                invalidate()
            }
        }
    }

    private fun schedulePatches() {
        removeCallbacks(patchRunnable)
        postDelayed(patchRunnable, 160)
    }

    private fun interacting(): Boolean =
        fingers > 0 || scaleDetector.isInProgress || !scroller.isFinished || (zoomAnim?.isRunning == true)

    private fun renderPatches() {
        val e = engine ?: return
        if (interacting()) { schedulePatches(); return }
        val range = visibleRange()
        patches.keys.retainAll(range.toSet())
        data class Task(val page: Int, val r: RectF, val vis: RectF)
        val tasks = ArrayList<Task>()
        for (i in range) {
            val r = pageScreenRect(i)
            val (bw, _) = baseSize(i)
            if (r.width() <= bw * 1.12f) { patches.remove(i); continue }
            val vis = RectF(r)
            if (!vis.intersect(0f, 0f, width.toFloat(), height.toFloat())) continue
            val existing = patches[i]
            if (existing != null && existing.gen == layoutGen && abs(existing.zoom - zoom) < 0.001f) {
                val want = RectF(vis).apply { offset(offX, offY) }
                if (existing.rect.contains(want)) continue
            }
            tasks += Task(i, r, vis)
        }
        if (tasks.isEmpty()) return
        patchJob?.cancel()
        val z = zoom; val ox = offX; val oy = offY; val gen = layoutGen
        patchJob = scope.launch {
            for (t in tasks) {
                val bmp = withContext(PdfEngine.dispatcher) {
                    ensureActive()
                    if (e.closed) return@withContext null
                    val bw = max(1, t.vis.width().roundToInt()); val bh = max(1, t.vis.height().roundToInt())
                    runCatching {
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            e.renderBlocking(
                                t.page, it,
                                (t.r.left - t.vis.left).roundToInt(), (t.r.top - t.vis.top).roundToInt(),
                                t.r.width().roundToInt(), t.r.height().roundToInt(),
                            )
                        }
                    }.getOrNull()
                } ?: continue
                if (gen != layoutGen || engine !== e) return@launch
                patches[t.page] = Patch(z, RectF(t.vis).apply { offset(ox, oy) }, bmp, gen)
                invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private val tmpRect = RectF()
    private val tmpRect2 = RectF()

    override fun onDraw(canvas: Canvas) {
        val night = colorMode == ColorMode.NIGHT
        bgPaint.color = if (night) 0xFF101010.toInt() else 0xFFE6E6E6.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val e = engine ?: return
        if (pt.size != pageCount) return
        val range = visibleRange()
        wantedRange = range
        pageFill.color = when (colorMode) {
            ColorMode.NORMAL -> Color.WHITE
            ColorMode.NIGHT -> Color.BLACK
            ColorMode.SEPIA -> 0xFFF4ECD8.toInt()
        }
        for (i in range) {
            val r = pageScreenRect(i, tmpRect)
            canvas.drawRect(r.left + 1, r.top + 2, r.right + 2, r.bottom + 3, shadowPaint)
            canvas.drawRect(r, pageFill)
            val base = baseCache.get(i)
            if (base != null) {
                canvas.drawBitmap(base, null, r, bitmapPaint)
            } else {
                requestBase(i)
                canvas.drawText("${i + 1}", r.centerX(), r.centerY(), labelPaint)
            }
            patches[i]?.let { p ->
                if (p.gen == layoutGen) {
                    val k = zoom / p.zoom
                    tmpRect2.set(p.rect.left * k - offX, p.rect.top * k - offY, p.rect.right * k - offX, p.rect.bottom * k - offY)
                    canvas.save()
                    canvas.clipRect(r)
                    canvas.drawBitmap(p.bmp, null, tmpRect2, bitmapPaint)
                    canvas.restore()
                }
            }
            drawPageOverlays(canvas, i, r)
        }
        // prefetch neighbours
        if (!range.isEmpty()) {
            if (range.first > 0 && baseCache.get(range.first - 1) == null) requestBase(range.first - 1)
            if (range.last < pageCount - 1 && baseCache.get(range.last + 1) == null) requestBase(range.last + 1)
        }
        drawSelectionHandles(canvas)
        drawSelectedItemFrame(canvas)
        drawThumb(canvas)
    }

    private fun drawPageOverlays(c: Canvas, i: Int, r: RectF) {
        val s = scaleOf(i) * zoom
        c.save()
        c.clipRect(r)
        c.translate(r.left, r.top)
        c.scale(s, s)
        // form fields
        if (highlightFormFields && colorMode != ColorMode.NIGHT) {
            formFields[i]?.forEach { f ->
                if (!f.readOnly && f.type != FieldType.BUTTON) {
                    c.drawRect(f.rect, fieldPaint)
                    fieldStroke.strokeWidth = 1f / s
                    c.drawRect(f.rect, fieldStroke)
                }
            }
        }
        // committed-to-session items
        val dragging = dragItem
        itemsByPage[i]?.forEach { item ->
            if (dragging != null && dragging.id == item.id) return@forEach
            painter.draw(c, item)
        }
        if (dragging != null && dragging.page == i) painter.draw(c, dragging)
        // in-progress tool gesture
        if (inkPage == i && inkPoints.size >= 2) {
            painter.drawInk(c, listOf(inkPoints.toFloatArray()), toolStyle.color, toolStyle.width, toolStyle.opacity)
        }
        if (shapePage == i) {
            val k = when (tool) { Tool.OVAL -> ShapeKind.OVAL; Tool.LINE -> ShapeKind.LINE; Tool.ARROW -> ShapeKind.ARROW; else -> ShapeKind.RECT }
            painter.drawShape(c, k, shapeX1, shapeY1, shapeX2, shapeY2, toolStyle.color, toolStyle.width, null)
        }
        markupPreview?.let { mp ->
            if (mp.page == i) painter.drawMarkup(c, markupKindFor(tool), mp.rects, toolStyle.color)
        }
        // search hits
        hitsByPage[i]?.forEach { (idx, hit) ->
            val p = if (idx == currentHit) curHitPaint else hitPaint
            for (rr in hit.rects) c.drawRect(rr, p)
        }
        // text selection
        selection?.let { sel -> if (sel.page == i) for (rr in sel.rects) c.drawRect(rr, selPaint) }
        c.restore()
    }

    private fun drawSelectionHandles(c: Canvas) {
        val sel = selection ?: return
        if (sel.rects.isEmpty() || sel.page !in visibleRange()) return
        val (a, b) = handlePoints(sel)
        val rad = 9 * density
        c.drawCircle(a.first, a.second + rad, rad, handlePaint)
        c.drawRect(a.first - 1.5f * density, a.second - 2 * rad, a.first + 1.5f * density, a.second + rad, handlePaint)
        c.drawCircle(b.first, b.second + rad, rad, handlePaint)
        c.drawRect(b.first - 1.5f * density, b.second - 2 * rad, b.first + 1.5f * density, b.second + rad, handlePaint)
    }

    /** Screen positions (x, bottom y) of the start and end handles. */
    private fun handlePoints(sel: TextSelection): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val first = screenRectOf(sel.page, sel.rects.first())
        val last = screenRectOf(sel.page, sel.rects.last())
        return (first.left to first.bottom) to (last.right to last.bottom)
    }

    private fun drawSelectedItemFrame(c: Canvas) {
        val it = dragItem ?: selectedItem ?: return
        if (it.page !in visibleRange()) return
        val r = screenRectOf(it.page, it.bounds())
        r.inset(-4 * density, -4 * density)
        c.drawRect(r, selItemPaint)
        if (it.resizable) {
            c.drawCircle(r.right, r.bottom, 8 * density, handlePaint)
        }
    }

    // ------------------------------------------------------------------ fast-scroll thumb

    private var thumbVisibleUntil = 0L
    private val thumbW get() = 30 * density
    private val thumbH get() = 44 * density
    private var draggingThumb = false

    private fun showThumb() {
        if (scrollMode != ScrollMode.VERTICAL || pageCount < 3) return
        thumbVisibleUntil = System.currentTimeMillis() + 1500
        postInvalidateDelayed(1600)
    }

    private fun thumbRect(): RectF? {
        if (scrollMode != ScrollMode.VERTICAL || pageCount < 3) return null
        val maxOff = contentH * zoom - height
        if (maxOff <= 0) return null
        val f = (offY / maxOff).coerceIn(0f, 1f)
        val top = f * (height - thumbH)
        return RectF(width - thumbW - 2 * density, top, width - 2 * density, top + thumbH)
    }

    private fun drawThumb(c: Canvas) {
        if (!draggingThumb && System.currentTimeMillis() > thumbVisibleUntil) return
        val r = thumbRect() ?: return
        thumbPaint.color = if (draggingThumb) 0xEE2962FF.toInt() else 0xCC424242.toInt()
        c.drawRoundRect(r, 8 * density, 8 * density, thumbPaint)
        val label = "${currentPage + 1}"
        thumbText.textSize = if (label.length > 3) 10 * density else 12 * density
        c.drawText(label, r.centerX(), r.centerY() + thumbText.textSize / 3, thumbText)
    }

    // ------------------------------------------------------------------ gestures

    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var zoomAnim: ValueAnimator? = null
    private var fingers = 0
    private var flung = false

    private enum class Mode { NONE, NAV, PINCH_ONLY, THUMB, HANDLE, ITEM_MOVE, ITEM_RESIZE, TOOL }
    private var mode = Mode.NONE

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        var lastFx = 0f; var lastFy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX; lastFy = d.focusY
            return mode == Mode.NAV || mode == Mode.PINCH_ONLY
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (mode != Mode.NAV && mode != Mode.PINCH_ONLY) return false
            zoomAround(zoom * d.scaleFactor, d.focusX, d.focusY)
            offX -= d.focusX - lastFx; offY -= d.focusY - lastFy
            lastFx = d.focusX; lastFy = d.focusY
            clampOffsets(); viewportChanged()
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            listener?.onZoomChanged(zoom)
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != Mode.NAV || scaleDetector.isInProgress || e2.pointerCount > 1) return false
            offX += dx; offY += dy
            clampOffsets(); viewportChanged()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode != Mode.NAV || scaleDetector.isInProgress) return false
            flung = true
            if (scrollMode == ScrollMode.HORIZONTAL && zoom <= 1.05f) {
                val target = when {
                    vx < -600 -> currentPage + 1
                    vx > 600 -> currentPage - 1
                    else -> currentPage
                }.coerceIn(0, pageCount - 1)
                animateScrollTo(target * slotW() * zoom, offY)
                return true
            }
            val maxX = max(0f, contentW * zoom - width).toInt()
            val maxY = max(0f, contentH * zoom - height).toInt()
            scroller.fling(offX.toInt(), offY.toInt(), (-vx).toInt(), (-vy).toInt(), 0, maxX, 0, maxY)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            handleTap(e.x, e.y); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool.category != Tool.Category.NONE && tool.category != Tool.Category.PLACE) return false
            val target = if (zoom < 1.6f) 2.5f else 1f
            animateZoom(target, e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.NAV || tool != Tool.NONE || scaleDetector.isInProgress) return
            startSelectionAt(e.x, e.y)
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat(); offY = scroller.currY.toFloat()
            clampOffsets(); viewportChanged()
            postInvalidateOnAnimation()
        }
    }

    private fun zoomAround(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val docX = (fx + offX) / zoom; val docY = (fy + offY) / zoom
        zoom = z
        offX = docX * z - fx; offY = docY * z - fy
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val from = zoom
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                zoomAround(from + (target - from) * f, fx, fy)
                clampOffsets(); viewportChanged()
            }
            start()
        }
        listener?.onZoomChanged(target)
    }

    private fun animateScrollTo(x: Float, y: Float) {
        scroller.forceFinished(true)
        scroller.startScroll(offX.toInt(), offY.toInt(), (x - offX).toInt(), (y - offY).toInt(), 260)
        postInvalidateOnAnimation()
    }

    private fun snapIfPaged() {
        if (scrollMode == ScrollMode.HORIZONTAL && zoom <= 1.05f && !flung) {
            val target = currentPage
            animateScrollTo(target * slotW() * zoom, offY)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (engine == null || pageCount == 0) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingers = 1
                flung = false
                scroller.forceFinished(true)
                zoomAnim?.cancel()
                mode = decideMode(ev.x, ev.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = ev.pointerCount
                if (mode == Mode.TOOL) { cancelToolGesture(); mode = Mode.PINCH_ONLY }
                else if (mode == Mode.ITEM_MOVE || mode == Mode.ITEM_RESIZE || mode == Mode.HANDLE) {
                    dragItem = null; mode = Mode.PINCH_ONLY
                }
            }
        }
        scaleDetector.onTouchEvent(ev)
        when (mode) {
            Mode.NAV -> gestureDetector.onTouchEvent(ev)
            Mode.THUMB -> handleThumb(ev)
            Mode.HANDLE -> handleSelectionDrag(ev)
            Mode.ITEM_MOVE, Mode.ITEM_RESIZE -> handleItemDrag(ev)
            Mode.TOOL -> handleTool(ev)
            else -> {}
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingers = 0
                if (mode == Mode.NAV || mode == Mode.PINCH_ONLY) snapIfPaged()
                draggingThumb = false
                mode = Mode.NONE
                schedulePatches()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> fingers = max(1, ev.pointerCount - 1)
        }
        return true
    }

    private fun decideMode(x: Float, y: Float): Mode {
        if (System.currentTimeMillis() <= thumbVisibleUntil) {
            thumbRect()?.let { r ->
                val hit = RectF(r).apply { inset(-12 * density, -8 * density) }
                if (hit.contains(x, y)) { draggingThumb = true; thumbGrab = y - r.top; return Mode.THUMB }
            }
        }
        selection?.let { sel ->
            if (sel.rects.isNotEmpty()) {
                val (a, b) = handlePoints(sel)
                val rad = 9 * density
                val tol = 28 * density
                if (hypot(x - a.first, y - (a.second + rad)) < tol) { draggingStartHandle = true; return Mode.HANDLE }
                if (hypot(x - b.first, y - (b.second + rad)) < tol) { draggingStartHandle = false; return Mode.HANDLE }
            }
        }
        selectedItem?.let { item ->
            val r = screenRectOf(item.page, item.bounds()).apply { inset(-4 * density, -4 * density) }
            if (item.resizable && hypot(x - r.right, y - r.bottom) < 24 * density) {
                dragOrigin = item; dragItem = item; dragStart = toPagePoint(item.page, x, y, false); return Mode.ITEM_RESIZE
            }
            if (RectF(r).apply { inset(-8 * density, -8 * density) }.contains(x, y)) {
                dragOrigin = item; dragItem = item; dragStart = toPagePoint(item.page, x, y, false); return Mode.ITEM_MOVE
            }
        }
        return when (tool.category) {
            Tool.Category.MARKUP, Tool.Category.DRAW, Tool.Category.SHAPE, Tool.Category.ERASE -> Mode.TOOL
            else -> Mode.NAV
        }
    }

    private var thumbGrab = 0f
    private fun handleThumb(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val maxOff = contentH * zoom - height
                val f = ((ev.y - thumbGrab) / (height - thumbH)).coerceIn(0f, 1f)
                offY = f * maxOff
                clampOffsets(); viewportChanged()
            }
        }
    }

    // ------------------------------------------------------------------ tap handling

    private fun handleTap(x: Float, y: Float) {
        val hit = hitPage(x, y)
        if (hit == null) {
            if (selectedItem != null) { selectItem(null); return }
            if (selection != null) { clearSelection(); return }
            listener?.onTap(); return
        }
        val (page, px, py) = hit
        val slop = 6 * density / (scaleOf(page) * zoom)
        itemsByPage[page]?.asReversed()?.firstOrNull { it.bounds().apply { inset(-slop, -slop) }.contains(px, py) }?.let {
            selectItem(it); return
        }
        if (selectedItem != null) { selectItem(null); return }
        if (selection != null) { clearSelection(); return }
        if (tool.category == Tool.Category.PLACE) { listener?.onPlace(page, px, py); return }
        if (tool == Tool.NONE) {
            formFields[page]?.firstOrNull { !it.readOnly && it.rect.contains(px, py) }?.let {
                listener?.onFormFieldTap(it); return
            }
        }
        engine?.cachedLinks(page)?.firstOrNull { it.bounds.contains(px, py) }?.let {
            listener?.onLink(it); return
        }
        if (tool == Tool.NONE) {
            existingAnnots[page]?.asReversed()?.firstOrNull {
                RectF(it.rect).apply { inset(-slop, -slop) }.contains(px, py)
            }?.let { listener?.onExistingAnnotationTap(it); return }
        }
        listener?.onTap()
    }

    // ------------------------------------------------------------------ text queries

    private val queryQueue = ArrayDeque<Pair<Boolean, suspend () -> Unit>>()
    private var queryJob: Job? = null

    private fun submitQuery(conflatable: Boolean, q: suspend () -> Unit) {
        if (conflatable && queryQueue.lastOrNull()?.first == true) queryQueue.removeLast()
        queryQueue.addLast(conflatable to q)
        if (queryJob?.isActive != true) {
            queryJob = scope.launch {
                while (queryQueue.isNotEmpty()) {
                    val next = queryQueue.removeFirst().second
                    runCatching { next() }
                }
            }
        }
    }

    private fun tolerancePt(page: Int, dp: Float) = dp * density / (scaleOf(page) * zoom)

    private fun startSelectionAt(x: Float, y: Float) {
        val e = engine ?: return
        val (page, px, py) = hitPage(x, y) ?: return
        val tol = tolerancePt(page, 10f)
        submitQuery(false) {
            val sel = withContext(PdfEngine.dispatcher) {
                if (e.closed) return@withContext null
                val idx = e.charIndexAtBlocking(page, px, py, tol)
                if (idx < 0) return@withContext null
                val w = e.wordAtBlocking(page, idx)
                if (w.isEmpty()) null
                else TextSelection(page, w.first, w.last + 1, e.rectsBlocking(page, w.first, w.last + 1 - w.first))
            }
            if (sel != null && sel.rects.isNotEmpty()) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                selection = sel
                listener?.onSelectionChanged(sel)
                invalidate()
            }
        }
    }

    private var draggingStartHandle = false

    private fun handleSelectionDrag(ev: MotionEvent) {
        val e = engine ?: return
        val sel = selection ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Aim slightly above the finger so the text under it stays visible.
                val (px, py) = toPagePoint(sel.page, ev.x, ev.y - 22 * density)
                val tol = tolerancePt(sel.page, 18f)
                val startHandle = draggingStartHandle
                submitQuery(true) {
                    val cur = selection ?: return@submitQuery
                    val updated = withContext(PdfEngine.dispatcher) {
                        if (e.closed) return@withContext null
                        val idx = e.charIndexAtBlocking(cur.page, px, py, tol)
                        if (idx < 0) return@withContext null
                        var s = cur.start; var en = cur.end
                        if (startHandle) s = idx else en = idx + 1
                        if (s >= en) { val t = s; s = en - 1; en = t + 1 }
                        s = s.coerceAtLeast(0)
                        if (en <= s) return@withContext null
                        TextSelection(cur.page, s, en, e.rectsBlocking(cur.page, s, en - s))
                    }
                    if (updated != null && updated.rects.isNotEmpty()) { selection = updated; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> submitQuery(false) { listener?.onSelectionChanged(selection) }
        }
    }

    // ------------------------------------------------------------------ item move/resize

    private var dragItem: Item? = null
    private var dragOrigin: Item? = null
    private var dragStart = 0f to 0f

    private fun handleItemDrag(ev: MotionEvent) {
        val orig = dragOrigin ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val (px, py) = toPagePoint(orig.page, ev.x, ev.y, false)
                val dx = px - dragStart.first; val dy = py - dragStart.second
                dragItem = if (mode == Mode.ITEM_MOVE) orig.moved(dx, dy) else {
                    val b = orig.bounds()
                    var nr = RectF(b.left, b.top, max(b.left + 8f, b.right + dx), max(b.top + 8f, b.bottom + dy))
                    if (orig is StampItem && b.width() > 0 && b.height() > 0) {
                        val aspect = b.width() / b.height()
                        val w = max(8f, nr.width()); nr = RectF(b.left, b.top, b.left + w, b.top + w / aspect)
                    }
                    orig.resized(nr)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val d = dragItem
                if (d != null && d != orig) {
                    selectedItem = d
                    listener?.onItemUpdated(d)
                }
                dragItem = null; dragOrigin = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragItem = null; dragOrigin = null; invalidate() }
        }
    }

    // ------------------------------------------------------------------ tool gestures

    private var inkPage = -1
    private val inkPoints = ArrayList<Float>()
    private var shapePage = -1
    private var shapeX1 = 0f; private var shapeY1 = 0f; private var shapeX2 = 0f; private var shapeY2 = 0f
    private data class MarkupPreview(val page: Int, val anchor: Int, val start: Int, val end: Int, val rects: List<RectF>)
    private var markupPreview: MarkupPreview? = null
    private var markupPage = -1
    private var markupAnchor = -1

    private fun markupKindFor(t: Tool) = when (t) {
        Tool.UNDERLINE -> MarkupKind.UNDERLINE
        Tool.STRIKEOUT -> MarkupKind.STRIKEOUT
        Tool.SQUIGGLY -> MarkupKind.SQUIGGLY
        else -> MarkupKind.HIGHLIGHT
    }

    private fun cancelToolGesture() {
        inkPage = -1; inkPoints.clear(); shapePage = -1; markupPreview = null; markupPage = -1; markupAnchor = -1
        invalidate()
    }

    private fun handleTool(ev: MotionEvent) {
        when (tool.category) {
            Tool.Category.DRAW -> handleInk(ev)
            Tool.Category.SHAPE -> handleShape(ev)
            Tool.Category.MARKUP -> handleMarkup(ev)
            Tool.Category.ERASE -> handleEraser(ev)
            else -> {}
        }
    }

    private fun handleInk(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitPage(ev.x, ev.y) ?: return
                inkPage = hit.first; inkPoints.clear(); inkPoints += hit.second; inkPoints += hit.third
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (inkPage < 0) return
                val minDist = 1.2f * density / (scaleOf(inkPage) * zoom)
                for (h in 0 until ev.historySize) addInkPoint(ev.getHistoricalX(h), ev.getHistoricalY(h), minDist)
                addInkPoint(ev.x, ev.y, minDist)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (inkPage >= 0 && inkPoints.size >= 2) {
                    listener?.onItemAdded(InkItem(newId(), inkPage, listOf(inkPoints.toFloatArray()), toolStyle.color, toolStyle.width, toolStyle.opacity))
                }
                inkPage = -1; inkPoints.clear(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelToolGesture()
        }
    }

    private fun addInkPoint(x: Float, y: Float, minDist: Float) {
        val (px, py) = toPagePoint(inkPage, x, y)
        val n = inkPoints.size
        if (n >= 2 && hypot(px - inkPoints[n - 2], py - inkPoints[n - 1]) < minDist) return
        inkPoints += px; inkPoints += py
    }

    private fun handleShape(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitPage(ev.x, ev.y) ?: return
                shapePage = hit.first; shapeX1 = hit.second; shapeY1 = hit.third; shapeX2 = shapeX1; shapeY2 = shapeY1
            }
            MotionEvent.ACTION_MOVE -> {
                if (shapePage < 0) return
                val (px, py) = toPagePoint(shapePage, ev.x, ev.y)
                shapeX2 = px; shapeY2 = py; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (shapePage >= 0 && hypot(shapeX2 - shapeX1, shapeY2 - shapeY1) > 3f) {
                    val kind = when (tool) { Tool.OVAL -> ShapeKind.OVAL; Tool.LINE -> ShapeKind.LINE; Tool.ARROW -> ShapeKind.ARROW; else -> ShapeKind.RECT }
                    listener?.onItemAdded(ShapeItem(newId(), shapePage, kind, shapeX1, shapeY1, shapeX2, shapeY2, toolStyle.color, toolStyle.width))
                }
                shapePage = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelToolGesture()
        }
    }

    private fun handleMarkup(ev: MotionEvent) {
        val e = engine ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (page, px, py) = hitPage(ev.x, ev.y) ?: return
                markupPage = page
                val tol = tolerancePt(page, 12f)
                submitQuery(false) {
                    val idx = withContext(PdfEngine.dispatcher) { if (e.closed) -1 else e.charIndexAtBlocking(page, px, py, tol) }
                    markupAnchor = idx
                    if (idx >= 0) {
                        val rects = withContext(PdfEngine.dispatcher) { e.rectsBlocking(page, idx, 1) }
                        markupPreview = MarkupPreview(page, idx, idx, idx + 1, rects); invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val page = markupPage
                if (page < 0) return
                val (px, py) = toPagePoint(page, ev.x, ev.y)
                val tol = tolerancePt(page, 16f)
                submitQuery(true) {
                    val anchor = markupAnchor
                    if (anchor < 0 || markupPage != page) {
                        // Finger started off-text: anchor on the first char we reach.
                        val idx = withContext(PdfEngine.dispatcher) { if (e.closed) -1 else e.charIndexAtBlocking(page, px, py, tol) }
                        if (idx >= 0 && markupPage == page) markupAnchor = idx
                        return@submitQuery
                    }
                    val res = withContext(PdfEngine.dispatcher) {
                        if (e.closed) return@withContext null
                        val idx = e.charIndexAtBlocking(page, px, py, tol)
                        if (idx < 0) return@withContext null
                        val s = min(anchor, idx); val en = max(anchor, idx) + 1
                        MarkupPreview(page, anchor, s, en, e.rectsBlocking(page, s, en - s))
                    }
                    if (res != null && markupPage == page) { markupPreview = res; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                val kind = markupKindFor(tool)
                val color = toolStyle.color
                submitQuery(false) {
                    val mp = markupPreview
                    if (mp != null && mp.rects.isNotEmpty()) {
                        val text = withContext(PdfEngine.dispatcher) { if (e.closed) "" else e.textBlocking(mp.page, mp.start, mp.end - mp.start) }
                        listener?.onItemAdded(MarkupItem(newId(), mp.page, kind, mergeLineRects(mp.rects), color, text.trim()))
                    }
                    markupPreview = null; markupPage = -1; markupAnchor = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelToolGesture()
        }
    }

    private fun handleEraser(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val (page, px, py) = hitPage(ev.x, ev.y) ?: return
                val rad = tolerancePt(page, 12f)
                val hits = itemsByPage[page]?.filter { hitsItem(it, px, py, rad) }?.map { it.id }?.toSet().orEmpty()
                if (hits.isNotEmpty()) listener?.onItemsErased(hits)
            }
        }
    }

    private fun hitsItem(item: Item, x: Float, y: Float, rad: Float): Boolean {
        if (item is InkItem) {
            val lim = rad + item.width / 2
            for (s in item.strokes) {
                var i = 0
                if (s.size == 2 && hypot(s[0] - x, s[1] - y) < lim) return true
                while (i + 3 < s.size) {
                    if (distToSegment(x, y, s[i], s[i + 1], s[i + 2], s[i + 3]) < lim) return true
                    i += 2
                }
            }
            return false
        }
        if (item is ShapeItem && (item.kind == ShapeKind.LINE || item.kind == ShapeKind.ARROW)) {
            return distToSegment(x, y, item.x1, item.y1, item.x2, item.y2) < rad + item.width
        }
        return item.bounds().apply { inset(-rad, -rad) }.contains(x, y)
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(patchRunnable)
    }
}

/** pdfium returns one rect per text run; merge runs that sit on the same line. */
fun mergeLineRects(rects: List<RectF>): List<RectF> {
    if (rects.size < 2) return rects.map { RectF(it) }
    val out = ArrayList<RectF>()
    for (r in rects) {
        val last = out.lastOrNull()
        if (last != null) {
            val overlapV = min(last.bottom, r.bottom) - max(last.top, r.top)
            val minH = min(last.height(), r.height())
            if (minH > 0 && overlapV > minH * 0.6f && r.left - last.right < minH * 2.5f && r.left >= last.left - 1f) {
                last.union(r); continue
            }
        }
        out += RectF(r)
    }
    return out
}
