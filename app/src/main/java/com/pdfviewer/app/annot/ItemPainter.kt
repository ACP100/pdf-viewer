package com.pdfviewer.app.annot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Geometry helpers shared by the on-screen painter and the PDF writer. */
object Shapes {
    /** Returns the two arrow-head wing end points (x,y,x,y) for an arrow from 1 to 2. */
    fun arrowWings(x1: Float, y1: Float, x2: Float, y2: Float, width: Float): FloatArray {
        val len = max(8f, width * 4.5f)
        val a = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val spread = Math.toRadians(28.0)
        return floatArrayOf(
            (x2 - len * cos(a - spread)).toFloat(), (y2 - len * sin(a - spread)).toFloat(),
            (x2 - len * cos(a + spread)).toFloat(), (y2 - len * sin(a + spread)).toFloat(),
        )
    }

    /** Check mark polyline inside [r]. */
    fun check(r: RectF) = floatArrayOf(
        r.left + r.width() * 0.08f, r.top + r.height() * 0.55f,
        r.left + r.width() * 0.38f, r.top + r.height() * 0.85f,
        r.left + r.width() * 0.92f, r.top + r.height() * 0.15f,
    )

    fun squiggle(r: RectF, amplitude: Float): FloatArray {
        val pts = ArrayList<Float>()
        val step = max(1.5f, amplitude * 2)
        var x = r.left
        var up = true
        val base = r.bottom - amplitude
        while (x <= r.right) {
            pts += x; pts += if (up) base - amplitude else base + amplitude
            up = !up; x += step
        }
        return pts.toFloatArray()
    }

    fun markupThickness(r: RectF) = max(0.8f, r.height() * 0.07f)
}

class ItemPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private val path = Path()
    private val bitmaps = LruCache<String, Bitmap>(12)

    var night = false
    var filter: ColorFilter? = null
        set(value) {
            field = value
            fill.colorFilter = value; stroke.colorFilter = value; textPaint.colorFilter = value; bitmapPaint.colorFilter = value
        }

    fun bitmap(path: String): Bitmap? {
        bitmaps.get(path)?.let { return it }
        val b = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        bitmaps.put(path, b)
        return b
    }

    fun forget(path: String) { bitmaps.remove(path) }

    /** Draws [item] on a canvas already transformed into page display points. */
    fun draw(c: Canvas, item: Item) {
        when (item) {
            is MarkupItem -> drawMarkup(c, item)
            is InkItem -> drawInk(c, item.strokes, item.color, item.width, item.opacity)
            is ShapeItem -> drawShape(c, item.kind, item.x1, item.y1, item.x2, item.y2, item.color, item.width, item.fill)
            is NoteItem -> drawNote(c, item.x, item.y, item.color)
            is TextItem -> drawText(c, item)
            is StampItem -> drawStamp(c, item)
        }
    }

    fun drawMarkup(c: Canvas, m: MarkupItem) = drawMarkup(c, m.kind, m.rects, m.color)

    fun drawMarkup(c: Canvas, kind: MarkupKind, rects: List<RectF>, color: Int) {
        when (kind) {
            MarkupKind.HIGHLIGHT -> {
                fill.style = Paint.Style.FILL
                if (night) {
                    fill.color = color; fill.alpha = 110; fill.xfermode = null
                } else {
                    fill.color = color or 0xFF000000.toInt(); fill.xfermode = multiply
                }
                for (r in rects) c.drawRect(r, fill)
                fill.xfermode = null; fill.alpha = 255
            }
            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                stroke.color = color; stroke.strokeCap = Paint.Cap.BUTT
                for (r in rects) {
                    val t = Shapes.markupThickness(r)
                    stroke.strokeWidth = t
                    val y = if (kind == MarkupKind.UNDERLINE) r.bottom - t / 2 else r.centerY()
                    c.drawLine(r.left, y, r.right, y, stroke)
                }
                stroke.strokeCap = Paint.Cap.ROUND
            }
            MarkupKind.SQUIGGLY -> {
                stroke.color = color
                for (r in rects) {
                    val t = Shapes.markupThickness(r)
                    stroke.strokeWidth = t
                    val pts = Shapes.squiggle(r, max(1f, r.height() * 0.06f))
                    polyline(c, pts, stroke)
                }
            }
        }
    }

    fun drawInk(c: Canvas, strokes: List<FloatArray>, color: Int, width: Float, opacity: Float) {
        stroke.color = color
        stroke.alpha = (opacity * 255).toInt().coerceIn(10, 255)
        stroke.strokeWidth = width
        for (s in strokes) {
            if (s.size < 2) continue
            if (s.size == 2) {
                fill.color = stroke.color; fill.alpha = stroke.alpha; fill.xfermode = null
                c.drawCircle(s[0], s[1], width / 2, fill)
                fill.alpha = 255
                continue
            }
            path.reset()
            path.moveTo(s[0], s[1])
            var i = 2
            while (i + 3 < s.size) {
                val mx = (s[i] + s[i + 2]) / 2; val my = (s[i + 1] + s[i + 3]) / 2
                path.quadTo(s[i], s[i + 1], mx, my)
                i += 2
            }
            path.lineTo(s[s.size - 2], s[s.size - 1])
            c.drawPath(path, stroke)
        }
        stroke.alpha = 255
    }

    fun drawShape(c: Canvas, kind: ShapeKind, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, fillColor: Int?) {
        stroke.color = color; stroke.strokeWidth = width
        val r = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        when (kind) {
            ShapeKind.RECT -> {
                if (fillColor != null) { fill.color = fillColor; fill.style = Paint.Style.FILL; c.drawRect(r, fill) }
                stroke.strokeJoin = Paint.Join.MITER
                c.drawRect(r, stroke)
                stroke.strokeJoin = Paint.Join.ROUND
            }
            ShapeKind.OVAL -> {
                if (fillColor != null) { fill.color = fillColor; fill.style = Paint.Style.FILL; c.drawOval(r, fill) }
                c.drawOval(r, stroke)
            }
            ShapeKind.LINE -> c.drawLine(x1, y1, x2, y2, stroke)
            ShapeKind.ARROW -> {
                c.drawLine(x1, y1, x2, y2, stroke)
                if (hypot(x2 - x1, y2 - y1) > 1f) {
                    val w = Shapes.arrowWings(x1, y1, x2, y2, width)
                    c.drawLine(x2, y2, w[0], w[1], stroke)
                    c.drawLine(x2, y2, w[2], w[3], stroke)
                }
            }
        }
    }

    fun drawNote(c: Canvas, x: Float, y: Float, color: Int) {
        val s = NoteItem.SIZE
        val r = RectF(x, y, x + s, y + s * 0.82f)
        fill.style = Paint.Style.FILL; fill.color = color; fill.xfermode = null
        c.drawRoundRect(r, 3f, 3f, fill)
        path.reset()
        path.moveTo(x + s * 0.25f, r.bottom - 0.5f); path.lineTo(x + s * 0.25f, y + s); path.lineTo(x + s * 0.5f, r.bottom - 0.5f); path.close()
        c.drawPath(path, fill)
        stroke.color = 0xFF3A3A3A.toInt(); stroke.strokeWidth = 0.8f
        c.drawRoundRect(r, 3f, 3f, stroke)
        stroke.strokeWidth = 1.4f
        for (k in 1..3) {
            val ly = y + s * 0.82f * k / 4f
            c.drawLine(x + s * 0.2f, ly, x + s * 0.8f, ly, stroke)
        }
    }

    fun drawText(c: Canvas, t: TextItem) {
        textPaint.color = t.color
        textPaint.textSize = t.fontSize
        val w = max(1, t.rect.width().toInt())
        val layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length, textPaint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        c.save()
        c.clipRect(t.rect.left - 1, t.rect.top - 1, t.rect.right + 1, max(t.rect.bottom, t.rect.top + layout.height) + 1)
        c.translate(t.rect.left, t.rect.top)
        layout.draw(c)
        c.restore()
        if (t.asAnnotation) {
            stroke.color = t.color; stroke.strokeWidth = 0.6f
            c.drawRect(t.rect, stroke)
        }
    }

    fun drawStamp(c: Canvas, s: StampItem) {
        val r = s.rect
        when (s.kind) {
            StampKind.IMAGE -> {
                val b = s.imagePath?.let { bitmap(it) }
                if (b != null) c.drawBitmap(b, null, r, bitmapPaint)
                else { stroke.color = Color.GRAY; stroke.strokeWidth = 1f; c.drawRect(r, stroke) }
            }
            StampKind.CHECK -> {
                stroke.color = s.color; stroke.strokeWidth = max(1f, min(r.width(), r.height()) * 0.12f)
                polyline(c, Shapes.check(r), stroke)
            }
            StampKind.CROSS -> {
                stroke.color = s.color; stroke.strokeWidth = max(1f, min(r.width(), r.height()) * 0.12f)
                val i = r.width() * 0.12f
                c.drawLine(r.left + i, r.top + i, r.right - i, r.bottom - i, stroke)
                c.drawLine(r.right - i, r.top + i, r.left + i, r.bottom - i, stroke)
            }
            StampKind.DOT -> {
                fill.style = Paint.Style.FILL; fill.color = s.color; fill.xfermode = null
                c.drawCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.35f, fill)
            }
            StampKind.CIRCLE -> {
                stroke.color = s.color; stroke.strokeWidth = max(1f, min(r.width(), r.height()) * 0.08f)
                val i = stroke.strokeWidth / 2
                c.drawOval(RectF(r.left + i, r.top + i, r.right - i, r.bottom - i), stroke)
            }
            StampKind.LINE -> {
                stroke.color = s.color; stroke.strokeWidth = max(1f, r.height() * 0.15f)
                c.drawLine(r.left, r.centerY(), r.right, r.centerY(), stroke)
            }
        }
    }

    private fun polyline(c: Canvas, pts: FloatArray, p: Paint) {
        if (pts.size < 4) return
        path.reset(); path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        c.drawPath(path, p)
    }
}
