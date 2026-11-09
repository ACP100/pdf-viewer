package com.pdfviewer.app.annot

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Edits the user made in this session that are not yet written into the PDF. All geometry is in
 * page "display points": origin at the top-left of the page as shown, y pointing down, page
 * rotation already applied. [com.pdfviewer.app.pdf.PageGeometry] converts to PDF user space when
 * saving.
 */
sealed interface Item {
    val id: Long
    val page: Int
    fun bounds(): RectF
    fun moved(dx: Float, dy: Float): Item
    /** Whether the item can be resized via its bounding box handle. */
    val resizable: Boolean get() = false
    fun resized(r: RectF): Item = this
}

private val ids = AtomicLong(1)
fun newId(): Long = ids.getAndIncrement()

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }
enum class ShapeKind { RECT, OVAL, LINE, ARROW }
enum class StampKind { IMAGE, CHECK, CROSS, DOT, CIRCLE, LINE }

data class MarkupItem(
    override val id: Long, override val page: Int, val kind: MarkupKind,
    val rects: List<RectF>, val color: Int, val text: String, val note: String = "",
) : Item {
    override fun bounds(): RectF = union(rects)
    override fun moved(dx: Float, dy: Float) = this // markup is bound to text
}

data class InkItem(
    override val id: Long, override val page: Int,
    /** Each stroke is x0,y0,x1,y1,... */
    val strokes: List<FloatArray>, val color: Int, val width: Float, val opacity: Float,
) : Item {
    override fun bounds(): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.size) {
                l = min(l, s[i]); r = max(r, s[i]); t = min(t, s[i + 1]); b = max(b, s[i + 1]); i += 2
            }
        }
        if (l > r) return RectF()
        val h = width / 2
        return RectF(l - h, t - h, r + h, b + h)
    }
    override fun moved(dx: Float, dy: Float) = copy(strokes = strokes.map { s ->
        FloatArray(s.size) { i -> if (i % 2 == 0) s[i] + dx else s[i] + dy }
    })
}

data class ShapeItem(
    override val id: Long, override val page: Int, val kind: ShapeKind,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Int, val width: Float, val fill: Int? = null,
) : Item {
    override fun bounds(): RectF {
        val h = width / 2 + if (kind == ShapeKind.ARROW) width * 4 else 0f
        return RectF(min(x1, x2) - h, min(y1, y2) - h, max(x1, x2) + h, max(y1, y2) + h)
    }
    override fun moved(dx: Float, dy: Float) = copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    override val resizable get() = kind == ShapeKind.RECT || kind == ShapeKind.OVAL
    override fun resized(r: RectF) = copy(x1 = r.left, y1 = r.top, x2 = r.right, y2 = r.bottom)
}

data class NoteItem(
    override val id: Long, override val page: Int, val x: Float, val y: Float,
    val text: String, val color: Int,
) : Item {
    override fun bounds() = RectF(x, y, x + SIZE, y + SIZE)
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    companion object { const val SIZE = 22f }
}

/**
 * Text placed on the page. [asAnnotation] = true creates an editable FreeText comment; false
 * writes it into the page content like "Fill & Sign" text.
 */
data class TextItem(
    override val id: Long, override val page: Int, val rect: RectF,
    val text: String, val color: Int, val fontSize: Float, val asAnnotation: Boolean,
) : Item {
    override fun bounds() = RectF(rect)
    override fun moved(dx: Float, dy: Float) = copy(rect = RectF(rect).apply { offset(dx, dy) })
    override val resizable get() = true
    override fun resized(r: RectF) = copy(rect = RectF(r))
}

data class StampItem(
    override val id: Long, override val page: Int, val rect: RectF,
    val kind: StampKind, val imagePath: String?, val color: Int,
) : Item {
    override fun bounds() = RectF(rect)
    override fun moved(dx: Float, dy: Float) = copy(rect = RectF(rect).apply { offset(dx, dy) })
    override val resizable get() = true
    override fun resized(r: RectF) = copy(rect = RectF(r))
}

fun union(rects: List<RectF>): RectF {
    if (rects.isEmpty()) return RectF()
    val u = RectF(rects[0])
    for (i in 1 until rects.size) u.union(rects[i])
    return u
}

/** Tools offered by the annotate / fill & sign toolbars. */
enum class Tool(val category: Category) {
    NONE(Category.NONE),
    HIGHLIGHT(Category.MARKUP), UNDERLINE(Category.MARKUP), STRIKEOUT(Category.MARKUP), SQUIGGLY(Category.MARKUP),
    INK(Category.DRAW),
    RECT(Category.SHAPE), OVAL(Category.SHAPE), LINE(Category.SHAPE), ARROW(Category.SHAPE),
    NOTE(Category.PLACE), TEXT(Category.PLACE),
    FILL_TEXT(Category.PLACE), DATE(Category.PLACE),
    CHECK(Category.PLACE), CROSS(Category.PLACE), DOT(Category.PLACE), CIRCLE_MARK(Category.PLACE), LINE_MARK(Category.PLACE),
    SIGNATURE(Category.PLACE), INITIALS(Category.PLACE), IMAGE(Category.PLACE),
    ERASER(Category.ERASE);

    enum class Category { NONE, MARKUP, DRAW, SHAPE, PLACE, ERASE }
}

data class ToolStyle(
    val color: Int = 0xFFFFEB3B.toInt(),
    val width: Float = 2f,
    val opacity: Float = 1f,
    val fontSize: Float = 14f,
)

/** An annotation that already exists in the PDF file (read with PdfBox). */
data class ExistingAnnot(
    val page: Int,
    /** Index in the page's /Annots array at load time. */
    val index: Int,
    val subtype: String,
    val rect: RectF, // display points
    val contents: String?,
    val author: String?,
    val modified: String?,
    val color: Int?,
)

enum class FieldType { TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON }

data class FormFieldInfo(
    val name: String,
    val label: String,
    val type: FieldType,
    val page: Int,
    val rect: RectF, // display points
    val value: String,
    val options: List<String>,
    val exportValues: List<String>,
    val onValue: String?,
    val readOnly: Boolean,
    val multiline: Boolean,
    val maxLen: Int,
)
