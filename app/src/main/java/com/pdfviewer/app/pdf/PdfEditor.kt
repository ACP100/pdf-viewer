package com.pdfviewer.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.pdfviewer.app.annot.ExistingAnnot
import com.pdfviewer.app.annot.FieldType
import com.pdfviewer.app.annot.FormFieldInfo
import com.pdfviewer.app.annot.InkItem
import com.pdfviewer.app.annot.Item
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.NoteItem
import com.pdfviewer.app.annot.ShapeItem
import com.pdfviewer.app.annot.ShapeKind
import com.pdfviewer.app.annot.Shapes
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.StampKind
import com.pdfviewer.app.annot.TextItem
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix

/** Geometry of a PdfBox page, matching pdfium's display mapping (see CPDF_Page::GetDisplayMatrix). */
object BoxGeometry {
    fun of(page: PDPage, intW: Int? = null, intH: Int? = null): PageGeometry {
        val crop = page.cropBox
        val x0 = crop.lowerLeftX; val y0 = crop.lowerLeftY; val x1 = crop.upperRightX; val y1 = crop.upperRightY
        val rot = ((page.rotation % 360) + 360) % 360
        val m = Matrix()
        // values: [sx, kx, tx, ky, sy, ty] with x' = sx*x + kx*y + tx ; y' = ky*x + sy*y + ty
        val v = when (rot) {
            90 -> floatArrayOf(0f, 1f, -y0, 1f, 0f, -x0)
            180 -> floatArrayOf(-1f, 0f, x1, 0f, 1f, -y0)
            270 -> floatArrayOf(0f, -1f, y1, -1f, 0f, x1)
            else -> floatArrayOf(1f, 0f, -x0, 0f, -1f, y1)
        }
        m.setValues(floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5], 0f, 0f, 1f))
        val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
        val dispH = if (rot == 90 || rot == 270) crop.width else crop.height
        if (intW != null && intH != null && dispW > 0 && dispH > 0) {
            m.postScale(intW / dispW, intH / dispH)
        } else if (dispW > 0 && dispH > 0) {
            m.postScale(max(1, dispW.toInt()) / dispW, max(1, dispH.toInt()) / dispH)
        }
        return PageGeometry(m)
    }
}

data class Extras(
    val annots: Map<Int, List<ExistingAnnot>>,
    val fields: Map<Int, List<FormFieldInfo>>,
    val hasForm: Boolean,
)

/** One page of an organize/rearrange result. */
data class PageSpec(val source: Int, val rotate: Int = 0, val blank: Boolean = false, val insertFile: File? = null, val insertIndex: Int = 0)

object PdfEditor {

    private val memory: MemoryUsageSetting get() = MemoryUsageSetting.setupMixed(64L * 1024 * 1024)

    private val passwords = java.util.Collections.synchronizedMap(java.util.WeakHashMap<PDDocument, String?>())

    fun load(file: File, password: String?): PDDocument =
        PDDocument.load(file, password ?: "", memory).also { passwords[it] = password }

    /**
     * PdfBox refuses to save an encrypted document without a protection policy. Keep files that
     * needed a password protected with that password (and the same permissions); documents that
     * only carried owner restrictions are saved without them.
     */
    private fun prepareSecurity(doc: PDDocument) {
        if (!doc.isEncrypted || doc.isAllSecurityToBeRemoved) return
        val handler = runCatching { doc.encryption?.securityHandler }.getOrNull()
        if (handler?.hasProtectionPolicy() == true) return
        val pw = passwords[doc]
        if (pw.isNullOrEmpty()) { doc.setAllSecurityToBeRemoved(true); return }
        val perms = runCatching { com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(doc.currentAccessPermission.permissionBytes) }
            .getOrElse { com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission() }
        val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(pw, pw, perms)
        policy.encryptionKeyLength = 128
        policy.setPreferAES(true)
        doc.protect(policy)
    }

    private fun saveAtomically(doc: PDDocument, out: File) {
        prepareSecurity(doc)
        val tmp = File(out.parentFile, out.name + ".tmp")
        doc.save(tmp)
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    private fun pdColor(c: Int) = PDColor(floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f), PDDeviceRGB.INSTANCE)
    private fun rgb(c: Int) = Triple(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun now(): Calendar = Calendar.getInstance()

    // ================================================================= reading

    fun readExtras(file: File, password: String?, sizes: List<PageSize>): Extras = load(file, password).use { doc ->
        val annots = HashMap<Int, MutableList<ExistingAnnot>>()
        val fields = HashMap<Int, MutableList<FormFieldInfo>>()
        val annotPage = HashMap<COSDictionary, Int>()
        val geos = HashMap<Int, PageGeometry>()
        fun geo(i: Int) = geos.getOrPut(i) {
            val s = sizes.getOrNull(i)
            BoxGeometry.of(doc.getPage(i), s?.width, s?.height)
        }
        val n = doc.numberOfPages
        for (i in 0 until n) {
            val page = doc.getPage(i)
            val list = runCatching { page.annotations }.getOrNull() ?: continue
            list.forEachIndexed { idx, a ->
                annotPage[a.cosObject] = i
                val sub = a.subtype ?: return@forEachIndexed
                if (sub == "Link" || sub == "Widget" || sub == "Popup") return@forEachIndexed
                val r = a.rectangle ?: return@forEachIndexed
                val disp = geo(i).toDisplay(RectF(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY))
                val author = (a as? PDAnnotationMarkup)?.titlePopup
                val color = runCatching { a.color?.toRGB() }.getOrNull()?.let { 0xFF000000.toInt() or it }
                annots.getOrPut(i) { ArrayList() } += ExistingAnnot(i, idx, sub, disp, a.contents, author, a.modifiedDate, color)
            }
        }
        val form = doc.documentCatalog.acroForm
        if (form != null) {
            for (f in form.fieldTree) {
                if (f !is PDTerminalField) continue
                val type = when (f) {
                    is PDTextField -> FieldType.TEXT
                    is PDCheckBox -> FieldType.CHECKBOX
                    is PDRadioButton -> FieldType.RADIO
                    is PDComboBox -> FieldType.COMBO
                    is PDChoice -> FieldType.LIST
                    is PDSignatureField -> FieldType.SIGNATURE
                    is PDPushButton -> FieldType.BUTTON
                    else -> continue
                }
                val name = f.fullyQualifiedName ?: continue
                val label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: name
                val value = runCatching { f.valueAsString }.getOrNull().orEmpty()
                val options = (f as? PDChoice)?.let { runCatching { it.optionsDisplayValues }.getOrNull() }.orEmpty()
                val exports = (f as? PDChoice)?.let { runCatching { it.optionsExportValues }.getOrNull() }.orEmpty()
                for (w in f.widgets) {
                    val pageIdx = annotPage[w.cosObject] ?: w.page?.let { doc.pages.indexOf(it) } ?: -1
                    if (pageIdx < 0) continue
                    val r = w.rectangle ?: continue
                    val disp = geo(pageIdx).toDisplay(RectF(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY))
                    val onValue = when (f) {
                        is PDButton -> widgetOnValue(w) ?: (f as? PDCheckBox)?.let { runCatching { it.onValue }.getOrNull() }
                        else -> null
                    }
                    fields.getOrPut(pageIdx) { ArrayList() } += FormFieldInfo(
                        name = name, label = label, type = type, page = pageIdx, rect = disp, value = value,
                        options = options, exportValues = exports, onValue = onValue,
                        readOnly = f.isReadOnly, multiline = (f as? PDTextField)?.isMultiline == true,
                        maxLen = (f as? PDTextField)?.maxLen ?: -1,
                    )
                }
            }
        }
        Extras(annots, fields, form != null && fields.isNotEmpty())
    }

    private fun widgetOnValue(w: PDAnnotationWidget): String? {
        val entry = w.appearance?.normalAppearance ?: return null
        if (!entry.isSubDictionary) return null
        return entry.subDictionary.keys.map { it.name }.firstOrNull { it != "Off" }
    }

    // ================================================================= form filling

    /** values: field full name -> new value ("Yes"/on-value for buttons, "Off" to clear). */
    fun setFormValues(src: File, password: String?, values: Map<String, String>, out: File) = load(src, password).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return@use
        for ((name, v) in values) {
            val f = form.getField(name) ?: continue
            try {
                when (f) {
                    is PDCheckBox -> if (v == "Off" || v.isEmpty()) f.unCheck() else f.check()
                    is PDRadioButton -> f.setValue(v)
                    is PDChoice -> f.setValue(v)
                    is PDTextField -> f.setValue(v)
                    else -> f.setValue(v)
                }
            } catch (e: Exception) {
                // Appearance generation can fail with exotic fonts; store the value and ask viewers to regenerate.
                f.cosObject.setString(COSName.V, v)
                form.setNeedAppearances(true)
            }
        }
        saveAtomically(doc, out)
    }

    fun flattenForm(src: File, password: String?, out: File) = load(src, password).use { doc ->
        doc.documentCatalog.acroForm?.let { runCatching { it.refreshAppearances() }; it.flatten() }
        saveAtomically(doc, out)
    }

    // ================================================================= existing annotations

    fun deleteAnnotation(src: File, password: String?, page: Int, index: Int, out: File) = load(src, password).use { doc ->
        val p = doc.getPage(page)
        val list = p.annotations.toMutableList()
        if (index in list.indices) {
            val removed = list.removeAt(index)
            val popup = removed.cosObject.getDictionaryObject(COSName.getPDFName("Popup"))
            list.removeAll { it.cosObject === popup || it.cosObject.getDictionaryObject(COSName.PARENT) === removed.cosObject }
            p.setAnnotations(list)
        }
        saveAtomically(doc, out)
    }

    fun editAnnotationContents(src: File, password: String?, page: Int, index: Int, text: String, out: File) = load(src, password).use { doc ->
        val a = doc.getPage(page).annotations.getOrNull(index)
        if (a != null) { a.contents = text; a.setModifiedDate(now()) }
        saveAtomically(doc, out)
    }

    // ================================================================= writing session items

    fun writeItems(src: File, password: String?, items: List<Item>, author: String, sizes: List<PageSize>, out: File) = load(src, password).use { doc ->
        val byPage = items.groupBy { it.page }
        for ((pageIndex, list) in byPage) {
            if (pageIndex !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(pageIndex)
            val s = sizes.getOrNull(pageIndex)
            val g = BoxGeometry.of(page, s?.width, s?.height)
            val annots = page.annotations.toMutableList()
            var flat: PDPageContentStream? = null
            fun flatStream(): PDPageContentStream =
                flat ?: PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).also { flat = it }
            try {
                for (item in list) {
                    when (item) {
                        is MarkupItem -> annots += markupAnnotation(doc, page, g, item, author)
                        is InkItem -> annots += inkAnnotation(doc, page, g, item, author)
                        is ShapeItem -> annots += shapeAnnotation(doc, page, g, item, author)
                        is NoteItem -> annots += noteAnnotation(doc, page, g, item, author)
                        is TextItem -> if (item.asAnnotation) annots += freeTextAnnotation(doc, page, g, item, author)
                        else drawTextFlat(doc, flatStream(), g, item)
                        is StampItem -> if (item.kind == StampKind.IMAGE && item.imagePath?.contains("/stamps/") == true) {
                            annots += imageStampAnnotation(doc, page, g, item, author)
                        } else drawStampFlat(doc, flatStream(), g, item)
                    }
                }
            } finally {
                flat?.close()
            }
            page.setAnnotations(annots)
        }
        saveAtomically(doc, out)
    }

    // ---- helpers for mapping display-space geometry into user space

    private fun userPts(g: PageGeometry, pts: FloatArray): FloatArray = pts.copyOf().also { g.displayToUser.mapPoints(it) }

    private fun userRect(g: PageGeometry, r: RectF, inflate: Float = 0f): PDRectangle {
        val u = g.toUser(RectF(r).apply { inset(-inflate, -inflate) })
        return PDRectangle(u.left, u.top, u.width(), u.height())
    }

    private fun bboxOf(pts: FloatArray, inflate: Float): PDRectangle {
        var l = Float.MAX_VALUE; var b = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) { l = min(l, pts[i]); r = max(r, pts[i]); b = min(b, pts[i + 1]); t = max(t, pts[i + 1]); i += 2 }
        return PDRectangle(l - inflate, b - inflate, (r - l) + 2 * inflate, (t - b) + 2 * inflate)
    }

    /** Matrix mapping a local frame (origin at display (left, top), x right, y up) into user space. */
    private fun localFrame(g: PageGeometry, left: Float, top: Float): PdfMatrix {
        val t = Matrix()
        t.setValues(floatArrayOf(1f, 0f, left, 0f, -1f, top, 0f, 0f, 1f))
        val m = Matrix(g.displayToUser)
        m.preConcat(t)
        val v = FloatArray(9); m.getValues(v)
        // android: x' = v0*x + v1*y + v2 ; y' = v3*x + v4*y + v5  -> PDF [a b c d e f] = [v0 v3 v1 v4 v2 v5]
        return PdfMatrix(v[0], v[3], v[1], v[4], v[2], v[5])
    }

    private fun newAppearance(doc: PDDocument, annot: PDAnnotation, bbox: PDRectangle): PDAppearanceStream {
        val ap = PDAppearanceStream(doc)
        ap.setBBox(bbox)
        ap.setResources(PDResources())
        val dict = PDAppearanceDictionary()
        dict.setNormalAppearance(ap)
        annot.setAppearance(dict)
        return ap
    }

    private fun common(a: PDAnnotationMarkup, page: PDPage, color: Int, author: String) {
        a.setColor(pdColor(color))
        a.setTitlePopup(author)
        a.setCreationDate(now())
        a.setModifiedDate(now())
        a.setPrinted(true)
        a.setPage(page)
        a.setAnnotationName("pv-" + System.nanoTime().toString(16))
    }

    private fun alphaState(stroke: Float?, fill: Float?, blend: BlendMode? = null) = PDExtendedGraphicsState().apply {
        stroke?.let { setStrokingAlphaConstant(it) }
        fill?.let { setNonStrokingAlphaConstant(it) }
        blend?.let { setBlendMode(it) }
    }

    private fun PDPageContentStream.polygon(pts: FloatArray) {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        closePath()
    }

    private fun PDPageContentStream.polyline(pts: FloatArray) {
        if (pts.size < 4) return
        moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    }

    // ---- annotations

    private fun markupAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, m: MarkupItem, author: String): PDAnnotation {
        val sub = when (m.kind) {
            MarkupKind.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
            MarkupKind.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
            MarkupKind.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
            MarkupKind.SQUIGGLY -> PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
        }
        val a = PDAnnotationTextMarkup(sub)
        common(a, page, m.color, author)
        val quads = ArrayList<Float>()
        val all = ArrayList<Float>()
        for (r in m.rects) {
            val q = userPts(g, floatArrayOf(r.left, r.top, r.right, r.top, r.left, r.bottom, r.right, r.bottom))
            quads.addAll(q.toList()); all.addAll(q.toList())
        }
        val bbox = bboxOf(all.toFloatArray(), 1f)
        a.setQuadPoints(quads.toFloatArray())
        a.setRectangle(bbox)
        if (m.note.isNotBlank()) a.contents = m.note
        a.setSubject(m.text.take(200))
        val ap = newAppearance(doc, a, bbox)
        val (r, gg, b) = rgb(m.color)
        PDPageContentStream(doc, ap).use { cs ->
            when (m.kind) {
                MarkupKind.HIGHLIGHT -> {
                    cs.setGraphicsStateParameters(alphaState(null, 1f, BlendMode.MULTIPLY))
                    cs.setNonStrokingColor(r, gg, b)
                    for (rect in m.rects) {
                        cs.polygon(userPts(g, floatArrayOf(rect.left, rect.top, rect.right, rect.top, rect.right, rect.bottom, rect.left, rect.bottom)))
                    }
                    cs.fill()
                }
                MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                    cs.setStrokingColor(r, gg, b)
                    for (rect in m.rects) {
                        val t = Shapes.markupThickness(rect)
                        val y = if (m.kind == MarkupKind.UNDERLINE) rect.bottom - t / 2 else rect.centerY()
                        cs.setLineWidth(t)
                        val p = userPts(g, floatArrayOf(rect.left, y, rect.right, y))
                        cs.moveTo(p[0], p[1]); cs.lineTo(p[2], p[3]); cs.stroke()
                    }
                }
                MarkupKind.SQUIGGLY -> {
                    cs.setStrokingColor(r, gg, b)
                    for (rect in m.rects) {
                        cs.setLineWidth(Shapes.markupThickness(rect))
                        cs.polyline(userPts(g, Shapes.squiggle(rect, max(1f, rect.height() * 0.06f))))
                        cs.stroke()
                    }
                }
            }
        }
        return a
    }

    private fun inkAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, ink: InkItem, author: String): PDAnnotation {
        val a = PDAnnotationMarkup()
        a.cosObject.setName(COSName.SUBTYPE, "Ink")
        common(a, page, ink.color, author)
        val inkList = COSArray()
        val all = ArrayList<Float>()
        val userStrokes = ink.strokes.map { userPts(g, it) }
        for (s in userStrokes) {
            val arr = COSArray()
            s.forEach { arr.add(COSFloat(it)) }
            inkList.add(arr)
            all.addAll(s.toList())
        }
        a.cosObject.setItem(COSName.getPDFName("InkList"), inkList)
        val bs = COSDictionary(); bs.setFloat("W", ink.width); bs.setName("S", "S")
        a.cosObject.setItem(COSName.getPDFName("BS"), bs)
        if (ink.opacity < 1f) a.cosObject.setFloat(COSName.getPDFName("CA"), ink.opacity)
        val bbox = bboxOf(all.toFloatArray(), ink.width + 1f)
        a.setRectangle(bbox)
        val ap = newAppearance(doc, a, bbox)
        val (r, gg, b) = rgb(ink.color)
        PDPageContentStream(doc, ap).use { cs ->
            if (ink.opacity < 1f) cs.setGraphicsStateParameters(alphaState(ink.opacity, ink.opacity))
            cs.setStrokingColor(r, gg, b)
            cs.setNonStrokingColor(r, gg, b)
            cs.setLineWidth(ink.width)
            cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
            for (s in userStrokes) {
                if (s.size == 2) { cs.moveTo(s[0], s[1]); cs.lineTo(s[0] + 0.01f, s[1]); cs.stroke(); continue }
                // Quadratic smoothing like the on-screen painter, expressed as cubic Béziers.
                cs.moveTo(s[0], s[1])
                var px = s[0]; var py = s[1]
                var i = 2
                while (i + 3 < s.size) {
                    val cx = s[i]; val cy = s[i + 1]
                    val mx = (s[i] + s[i + 2]) / 2; val my = (s[i + 1] + s[i + 3]) / 2
                    cs.curveTo(px + 2f / 3f * (cx - px), py + 2f / 3f * (cy - py), mx + 2f / 3f * (cx - mx), my + 2f / 3f * (cy - my), mx, my)
                    px = mx; py = my
                    i += 2
                }
                cs.lineTo(s[s.size - 2], s[s.size - 1])
                cs.stroke()
            }
        }
        return a
    }

    /** Ellipse inscribed in a display rect, as 4 cubic Béziers, mapped to user space: start + 4*(c1,c2,end). */
    private fun ellipsePts(g: PageGeometry, r: RectF): FloatArray {
        val k = 0.5522848f
        val cx = r.centerX(); val cy = r.centerY(); val rx = r.width() / 2; val ry = r.height() / 2
        val p = floatArrayOf(
            cx + rx, cy,
            cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry,
            cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy,
            cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry,
            cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy,
        )
        return userPts(g, p)
    }

    private fun PDPageContentStream.ellipse(p: FloatArray) {
        moveTo(p[0], p[1])
        var i = 2
        while (i + 5 < p.size) { curveTo(p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5]); i += 6 }
        closePath()
    }

    private fun shapeAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, s: ShapeItem, author: String): PDAnnotation {
        val (r, gg, b) = rgb(s.color)
        val dispRect = RectF(min(s.x1, s.x2), min(s.y1, s.y2), max(s.x1, s.x2), max(s.y1, s.y2))
        val a: PDAnnotationMarkup
        val bbox: PDRectangle
        when (s.kind) {
            ShapeKind.RECT, ShapeKind.OVAL -> {
                val sc = PDAnnotationSquareCircle(if (s.kind == ShapeKind.RECT) PDAnnotationSquareCircle.SUB_TYPE_SQUARE else PDAnnotationSquareCircle.SUB_TYPE_CIRCLE)
                a = sc
                bbox = userRect(g, dispRect, s.width / 2 + 1f)
                s.fill?.let { sc.setInteriorColor(pdColor(it)) }
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val ln = PDAnnotationLine()
                a = ln
                val p = userPts(g, floatArrayOf(s.x1, s.y1, s.x2, s.y2))
                ln.setLine(p)
                if (s.kind == ShapeKind.ARROW) ln.setEndPointEndingStyle(PDAnnotationLine.LE_OPEN_ARROW)
                val w = Shapes.arrowWings(s.x1, s.y1, s.x2, s.y2, s.width)
                val all = userPts(g, floatArrayOf(s.x1, s.y1, s.x2, s.y2, w[0], w[1], w[2], w[3]))
                bbox = bboxOf(all, s.width + 1f)
            }
        }
        common(a, page, s.color, author)
        val bs = COSDictionary(); bs.setFloat("W", s.width); bs.setName("S", "S")
        a.cosObject.setItem(COSName.getPDFName("BS"), bs)
        a.setRectangle(bbox)
        val ap = newAppearance(doc, a, bbox)
        PDPageContentStream(doc, ap).use { cs ->
            cs.setStrokingColor(r, gg, b)
            cs.setLineWidth(s.width)
            cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
            when (s.kind) {
                ShapeKind.RECT -> {
                    cs.polygon(userPts(g, floatArrayOf(dispRect.left, dispRect.top, dispRect.right, dispRect.top, dispRect.right, dispRect.bottom, dispRect.left, dispRect.bottom)))
                    if (s.fill != null) { val (fr, fg, fb) = rgb(s.fill); cs.setNonStrokingColor(fr, fg, fb); cs.fillAndStroke() } else cs.stroke()
                }
                ShapeKind.OVAL -> {
                    cs.ellipse(ellipsePts(g, dispRect))
                    if (s.fill != null) { val (fr, fg, fb) = rgb(s.fill); cs.setNonStrokingColor(fr, fg, fb); cs.fillAndStroke() } else cs.stroke()
                }
                ShapeKind.LINE, ShapeKind.ARROW -> {
                    val p = userPts(g, floatArrayOf(s.x1, s.y1, s.x2, s.y2))
                    cs.moveTo(p[0], p[1]); cs.lineTo(p[2], p[3]); cs.stroke()
                    if (s.kind == ShapeKind.ARROW) {
                        val w = userPts(g, Shapes.arrowWings(s.x1, s.y1, s.x2, s.y2, s.width))
                        cs.moveTo(w[0], w[1]); cs.lineTo(p[2], p[3]); cs.lineTo(w[2], w[3]); cs.stroke()
                    }
                }
            }
        }
        return a
    }

    private fun noteAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, n: NoteItem, author: String): PDAnnotation {
        val a = PDAnnotationText()
        common(a, page, n.color, author)
        a.setName(PDAnnotationText.NAME_COMMENT)
        a.setOpen(false)
        a.contents = n.text
        val d = RectF(n.x, n.y, n.x + NoteItem.SIZE, n.y + NoteItem.SIZE)
        val bbox = userRect(g, d)
        a.setRectangle(bbox)
        val ap = newAppearance(doc, a, bbox)
        val (r, gg, b) = rgb(n.color)
        val s = NoteItem.SIZE
        PDPageContentStream(doc, ap).use { cs ->
            cs.setNonStrokingColor(r, gg, b)
            cs.setStrokingColor(0.23f, 0.23f, 0.23f)
            cs.setLineWidth(0.8f)
            cs.polygon(userPts(g, floatArrayOf(n.x, n.y, n.x + s, n.y, n.x + s, n.y + s * 0.82f, n.x + s * 0.5f, n.y + s * 0.82f, n.x + s * 0.25f, n.y + s, n.x + s * 0.25f, n.y + s * 0.82f, n.x, n.y + s * 0.82f)))
            cs.fillAndStroke()
            cs.setLineWidth(1.4f)
            for (k in 1..3) {
                val ly = n.y + s * 0.82f * k / 4f
                val p = userPts(g, floatArrayOf(n.x + s * 0.2f, ly, n.x + s * 0.8f, ly))
                cs.moveTo(p[0], p[1]); cs.lineTo(p[2], p[3])
            }
            cs.stroke()
        }
        return a
    }

    // ---- text

    private val textFont: PDFont get() = PDType1Font.HELVETICA

    private fun encodable(font: PDFont, s: String): Boolean = try { font.encode(s); true } catch (e: Exception) { false }

    /** Greedy word wrap using the PDF font's metrics. */
    private fun wrap(font: PDFont, size: Float, text: String, width: Float): List<String> {
        val lines = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { lines += ""; continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (font.getStringWidth(candidate) / 1000f * size <= width || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    lines += line.toString(); line = StringBuilder(word)
                }
            }
            lines += line.toString()
        }
        return lines
    }

    private fun drawTextInFrame(doc: PDDocument, cs: PDPageContentStream, g: PageGeometry, t: TextItem) {
        val font = textFont
        val text = t.text.replace("\t", "    ")
        if (text.split('\n').all { encodable(font, it) }) {
            val (r, gg, b) = rgb(t.color)
            val size = t.fontSize
            val lines = wrap(font, size, text, max(t.rect.width(), size))
            val ascent = (font.fontDescriptor?.ascent ?: 718f) / 1000f * size
            val leading = size * 1.17f
            cs.saveGraphicsState()
            cs.transform(localFrame(g, t.rect.left, t.rect.top))
            cs.setNonStrokingColor(r, gg, b)
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(0f, -ascent)
            lines.forEachIndexed { i, line ->
                if (i > 0) cs.newLineAtOffset(0f, -leading)
                if (line.isNotEmpty()) cs.showText(line)
            }
            cs.endText()
            cs.restoreGraphicsState()
        } else {
            // Scripts outside WinAnsi (e.g. Devanagari, CJK): rasterise with Android's text stack.
            val bmp = renderTextBitmap(t)
            val img = LosslessFactory.createFromImage(doc, bmp)
            val hDisp = bmp.height / TEXT_RASTER_SCALE
            val wDisp = bmp.width / TEXT_RASTER_SCALE
            cs.saveGraphicsState()
            cs.transform(localFrame(g, t.rect.left, t.rect.top))
            cs.drawImage(img, PdfMatrix(wDisp, 0f, 0f, hDisp, 0f, -hDisp))
            cs.restoreGraphicsState()
        }
    }

    private const val TEXT_RASTER_SCALE = 4f

    private fun renderTextBitmap(t: TextItem): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = t.color; textSize = t.fontSize * TEXT_RASTER_SCALE }
        val w = max(1, (t.rect.width() * TEXT_RASTER_SCALE).roundToInt())
        val layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        val bmp = Bitmap.createBitmap(w, max(1, layout.height), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        return bmp
    }

    private fun textHeight(t: TextItem): Float {
        val font = textFont
        return if (t.text.split('\n').all { encodable(font, it) }) {
            wrap(font, t.fontSize, t.text, max(t.rect.width(), t.fontSize)).size * t.fontSize * 1.17f
        } else renderTextBitmap(t).height / TEXT_RASTER_SCALE
    }

    private fun drawTextFlat(doc: PDDocument, cs: PDPageContentStream, g: PageGeometry, t: TextItem) {
        drawTextInFrame(doc, cs, g, t)
    }

    private fun freeTextAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, t: TextItem, author: String): PDAnnotation {
        val a = PDAnnotationMarkup()
        a.cosObject.setName(COSName.SUBTYPE, "FreeText")
        common(a, page, t.color, author)
        val (r, gg, b) = rgb(t.color)
        a.cosObject.setString(COSName.DA, "/Helv ${t.fontSize} Tf $r $gg $b rg")
        a.contents = t.text
        val h = max(t.rect.height(), textHeight(t) + 2f)
        val disp = RectF(t.rect.left, t.rect.top, t.rect.right, t.rect.top + h)
        val bbox = userRect(g, disp, 1f)
        a.setRectangle(bbox)
        val bs = COSDictionary(); bs.setFloat("W", 0f)
        a.cosObject.setItem(COSName.getPDFName("BS"), bs)
        val ap = newAppearance(doc, a, bbox)
        PDPageContentStream(doc, ap).use { cs -> drawTextInFrame(doc, cs, g, t.copy(rect = disp)) }
        return a
    }

    // ---- stamps / signatures / symbols

    private fun drawStampFlat(doc: PDDocument, cs: PDPageContentStream, g: PageGeometry, s: StampItem) {
        val r = s.rect
        val (cr, cg, cb) = rgb(s.color)
        cs.saveGraphicsState()
        cs.setStrokingColor(cr, cg, cb); cs.setNonStrokingColor(cr, cg, cb)
        cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
        when (s.kind) {
            StampKind.IMAGE -> {
                val img = s.imagePath?.let { loadImage(doc, it) }
                if (img != null) {
                    cs.transform(localFrame(g, r.left, r.top))
                    cs.drawImage(img, PdfMatrix(r.width(), 0f, 0f, r.height(), 0f, -r.height()))
                }
            }
            StampKind.CHECK -> {
                cs.setLineWidth(max(1f, min(r.width(), r.height()) * 0.12f))
                cs.polyline(userPts(g, Shapes.check(r))); cs.stroke()
            }
            StampKind.CROSS -> {
                cs.setLineWidth(max(1f, min(r.width(), r.height()) * 0.12f))
                val i = r.width() * 0.12f
                val p = userPts(g, floatArrayOf(r.left + i, r.top + i, r.right - i, r.bottom - i, r.right - i, r.top + i, r.left + i, r.bottom - i))
                cs.moveTo(p[0], p[1]); cs.lineTo(p[2], p[3]); cs.moveTo(p[4], p[5]); cs.lineTo(p[6], p[7]); cs.stroke()
            }
            StampKind.DOT -> {
                val d = min(r.width(), r.height()) * 0.35f
                cs.ellipse(ellipsePts(g, RectF(r.centerX() - d, r.centerY() - d, r.centerX() + d, r.centerY() + d))); cs.fill()
            }
            StampKind.CIRCLE -> {
                val w = max(1f, min(r.width(), r.height()) * 0.08f)
                cs.setLineWidth(w)
                cs.ellipse(ellipsePts(g, RectF(r.left + w / 2, r.top + w / 2, r.right - w / 2, r.bottom - w / 2))); cs.stroke()
            }
            StampKind.LINE -> {
                cs.setLineWidth(max(1f, r.height() * 0.15f))
                val p = userPts(g, floatArrayOf(r.left, r.centerY(), r.right, r.centerY()))
                cs.moveTo(p[0], p[1]); cs.lineTo(p[2], p[3]); cs.stroke()
            }
        }
        cs.restoreGraphicsState()
    }

    private fun imageStampAnnotation(doc: PDDocument, page: PDPage, g: PageGeometry, s: StampItem, author: String): PDAnnotation {
        val a = PDAnnotationRubberStamp()
        common(a, page, 0xFF000000.toInt(), author)
        val bbox = userRect(g, s.rect)
        a.setRectangle(bbox)
        val ap = newAppearance(doc, a, bbox)
        val img = s.imagePath?.let { loadImage(doc, it) }
        if (img != null) {
            PDPageContentStream(doc, ap).use { cs ->
                cs.transform(localFrame(g, s.rect.left, s.rect.top))
                cs.drawImage(img, PdfMatrix(s.rect.width(), 0f, 0f, s.rect.height(), 0f, -s.rect.height()))
            }
        }
        return a
    }

    private fun loadImage(doc: PDDocument, path: String): PDImageXObject? {
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        return if (bmp.hasAlpha() || path.endsWith(".png", true)) LosslessFactory.createFromImage(doc, bmp)
        else JPEGFactory.createFromImage(doc, bmp, 0.9f)
    }

    // ================================================================= page organisation

    /**
     * Rebuilds the page tree in [specs] order. Pages keep their identity (links/outlines stay
     * valid); duplicates get a shallow copy; inserted pages come from other files.
     */
    fun rearrange(src: File, password: String?, specs: List<PageSpec>, out: File) {
        val doc = load(src, password)
        val others = HashMap<String, PDDocument>()
        try {
            val original = (0 until doc.numberOfPages).map { doc.getPage(it) }
            // Materialise inherited attributes before detaching pages from intermediate nodes.
            for (p in original) {
                p.setResources(p.resources)
                p.setMediaBox(p.mediaBox)
                p.setCropBox(p.cropBox)
                p.setRotation(p.rotation)
            }
            val used = HashSet<Int>()
            val result = ArrayList<COSDictionary>()
            for (s in specs) {
                val dict: COSDictionary = when {
                    s.blank -> {
                        val ref = original.firstOrNull()?.mediaBox ?: PDRectangle.A4
                        PDPage(PDRectangle(ref.width, ref.height)).cosObject
                    }
                    s.insertFile != null -> {
                        val od = others.getOrPut(s.insertFile.absolutePath) { load(s.insertFile, null) }
                        val op = od.getPage(s.insertIndex)
                        val copy = COSDictionary(op.cosObject)
                        copy.removeItem(COSName.ANNOTS)
                        val np = PDPage(copy)
                        np.setResources(op.resources); np.setMediaBox(op.mediaBox); np.setCropBox(op.cropBox); np.setRotation(op.rotation)
                        copy
                    }
                    else -> {
                        val p = original[s.source]
                        if (used.add(s.source)) p.cosObject else COSDictionary(p.cosObject).also { it.removeItem(COSName.ANNOTS) }
                    }
                }
                if (s.rotate != 0) {
                    val pg = PDPage(dict)
                    pg.setRotation(((pg.rotation + s.rotate) % 360 + 360) % 360)
                }
                result += dict
            }
            val root = doc.documentCatalog.cosObject.getCOSDictionary(COSName.PAGES)
                ?: throw IllegalStateException("No page tree")
            val kids = COSArray()
            for (d in result) { d.setItem(COSName.PARENT, root); kids.add(d) }
            root.setItem(COSName.KIDS, kids)
            root.setInt(COSName.COUNT, kids.size())
            // Drop page-level inheritable attributes on root that would now apply to new pages.
            saveAtomically(doc, out)
        } finally {
            others.values.forEach { runCatching { it.close() } }
            doc.close()
        }
    }

    // ================================================================= tools

    fun merge(files: List<File>, out: File) {
        val m = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    /** Splits into chunks of [every] pages, returns produced files. */
    fun splitEvery(src: File, password: String?, every: Int, outDir: File, baseName: String): List<File> {
        val n = load(src, password).use { it.numberOfPages }
        val files = ArrayList<File>()
        var start = 0
        var part = 1
        while (start < n) {
            val end = min(n, start + every)
            val f = File(outDir, "${baseName}_part$part.pdf")
            rearrange(src, password, (start until end).map { PageSpec(it) }, f)
            files += f
            start = end; part++
        }
        return files
    }

    data class CompressResult(val before: Long, val after: Long, val images: Int)

    fun compress(src: File, password: String?, maxDim: Int, quality: Float, out: File): CompressResult = load(src, password).use { doc ->
        var count = 0
        val seen = HashSet<COSDictionary>()
        fun process(res: PDResources?) {
            if (res == null) return
            for (name in res.xObjectNames.toList()) {
                val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                if (xo is PDFormXObject) { if (seen.add(xo.cosObject)) process(xo.resources); continue }
                if (xo !is PDImageXObject) continue
                val cos = xo.cosObject
                if (!seen.add(cos)) continue
                if (cos.containsKey(COSName.MASK)) continue
                val softMask = cos.getDictionaryObject(COSName.SMASK)
                if (xo.bitsPerComponent == 1 || xo.isStencil) continue
                val w = xo.width; val h = xo.height
                val alreadyJpeg = xo.suffix == "jpg"
                if (alreadyJpeg && max(w, h) <= maxDim) continue
                if (w * h < 40_000) continue
                val bmp = runCatching { xo.image }.getOrNull() ?: continue
                val scale = min(1f, maxDim.toFloat() / max(w, h))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), true) else bmp
                val opaque = if (scaled.hasAlpha()) {
                    val o = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                    Canvas(o).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                    o
                } else scaled
                val replacement = JPEGFactory.createFromImage(doc, opaque, quality)
                // Keep transparency: a soft mask may have its own resolution.
                if (softMask != null) replacement.cosObject.setItem(COSName.SMASK, softMask)
                res.put(name, replacement)
                count++
            }
        }
        for (p in doc.pages) process(p.resources)
        doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
        saveAtomically(doc, out)
        CompressResult(src.length(), out.length(), count)
    }

    fun protect(src: File, password: String?, userPw: String, ownerPw: String, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean, out: File) =
        load(src, password).use { doc ->
            val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
            ap.setCanPrint(allowPrint)
            ap.setCanExtractContent(allowCopy)
            ap.setCanModify(allowEdit)
            ap.setCanModifyAnnotations(allowEdit)
            val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(ownerPw.ifEmpty { userPw }, userPw, ap)
            policy.encryptionKeyLength = 128
            policy.setPreferAES(true)
            doc.protect(policy)
            saveAtomically(doc, out)
        }

    fun removeSecurity(src: File, password: String?, out: File) = load(src, password).use { doc ->
        doc.setAllSecurityToBeRemoved(true)
        saveAtomically(doc, out)
    }

    enum class PageFormat(val w: Float, val h: Float) { FIT(0f, 0f), A4(595f, 842f), LETTER(612f, 792f) }

    fun imagesToPdf(bitmaps: Sequence<Bitmap>, format: PageFormat, out: File) = PDDocument().use { doc ->
        for (bmp in bitmaps) {
            val img = JPEGFactory.createFromImage(doc, bmp, 0.88f)
            val page: PDPage
            val dx: Float; val dy: Float; val dw: Float; val dh: Float
            if (format == PageFormat.FIT) {
                // 150 dpi-ish physical size, capped to a sensible page size
                val k = min(1f, 1400f / max(bmp.width, bmp.height)) * 72f / 100f * 1.39f
                val pw = max(72f, bmp.width * k); val ph = max(72f, bmp.height * k)
                page = PDPage(PDRectangle(pw, ph)); dx = 0f; dy = 0f; dw = pw; dh = ph
            } else {
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) format.h else format.w
                val ph = if (landscape) format.w else format.h
                page = PDPage(PDRectangle(pw, ph))
                val margin = 24f
                val k = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                dw = bmp.width * k; dh = bmp.height * k; dx = (pw - dw) / 2; dy = (ph - dh) / 2
            }
            doc.addPage(page)
            PDPageContentStream(doc, page).use { it.drawImage(img, dx, dy, dw, dh) }
        }
        saveAtomically(doc, out)
    }

    fun watermark(src: File, password: String?, text: String, opacity: Float, sizePt: Float, color: Int, out: File) = load(src, password).use { doc ->
        val font = PDType1Font.HELVETICA_BOLD
        val safe = text.filter { encodable(font, it.toString()) }.ifBlank { "WATERMARK" }
        val (r, g, b) = rgb(color)
        for (page in doc.pages) {
            val geo = BoxGeometry.of(page)
            val crop = page.cropBox
            val rot = ((page.rotation % 360) + 360) % 360
            val dw = if (rot == 90 || rot == 270) crop.height else crop.width
            val dh = if (rot == 90 || rot == 270) crop.width else crop.height
            val size = min(sizePt, dw / max(1, safe.length) * 1.6f)
            val tw = font.getStringWidth(safe) / 1000f * size
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.setGraphicsStateParameters(alphaState(opacity, opacity))
                cs.setNonStrokingColor(r, g, b)
                cs.transform(localFrame(geo, dw / 2, dh / 2))
                cs.transform(PdfMatrix.getRotateInstance(Math.toRadians(35.0), 0f, 0f))
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(-tw / 2, -size / 3)
                cs.showText(safe)
                cs.endText()
            }
        }
        saveAtomically(doc, out)
    }

    fun pageNumbers(src: File, password: String?, format: String, sizePt: Float, out: File) = load(src, password).use { doc ->
        val font = PDType1Font.HELVETICA
        val n = doc.numberOfPages
        doc.pages.forEachIndexed { i, page ->
            val geo = BoxGeometry.of(page)
            val crop = page.cropBox
            val rot = ((page.rotation % 360) + 360) % 360
            val dw = if (rot == 90 || rot == 270) crop.height else crop.width
            val dh = if (rot == 90 || rot == 270) crop.width else crop.height
            val label = format.replace("{n}", "${i + 1}").replace("{total}", "$n")
            val tw = font.getStringWidth(label) / 1000f * sizePt
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.setNonStrokingColor(0.25f, 0.25f, 0.25f)
                cs.transform(localFrame(geo, (dw - tw) / 2, dh - 18f))
                cs.beginText(); cs.setFont(font, sizePt); cs.newLineAtOffset(0f, 0f); cs.showText(label); cs.endText()
            }
        }
        saveAtomically(doc, out)
    }

    fun setMetadata(src: File, password: String?, title: String, author: String, subject: String, keywords: String, out: File) =
        load(src, password).use { doc ->
            val info = doc.documentInformation
            info.title = title; info.author = author; info.subject = subject; info.keywords = keywords
            info.modificationDate = now()
            saveAtomically(doc, out)
        }

    fun isEncrypted(src: File): Boolean = runCatching { PDDocument.load(src, "", memory).use { it.isEncrypted } }.getOrDefault(true)
}
