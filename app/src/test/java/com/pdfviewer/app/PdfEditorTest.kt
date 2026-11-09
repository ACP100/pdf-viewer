package com.pdfviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.pdfviewer.app.annot.InkItem
import com.pdfviewer.app.annot.Item
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.NoteItem
import com.pdfviewer.app.annot.ShapeItem
import com.pdfviewer.app.annot.ShapeKind
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.StampKind
import com.pdfviewer.app.annot.TextItem
import com.pdfviewer.app.annot.newId
import com.pdfviewer.app.pdf.BoxGeometry
import com.pdfviewer.app.pdf.PageSize
import com.pdfviewer.app.pdf.PageSpec
import com.pdfviewer.app.pdf.PdfEditor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PdfEditorTest {
    private val out = File(System.getProperty("user.dir"), "build/test-out").apply { mkdirs() }

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun sample(): File {
        val f = File(out, "sample.pdf")
        PDDocument().use { doc ->
            for (i in 0 until 4) {
                val page = PDPage(PDRectangle.LETTER)
                if (i == 1) page.rotation = 90
                if (i == 3) page.rotation = 270
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 28f); cs.newLineAtOffset(72f, 680f)
                    cs.showText("Page ${i + 1} rotation ${page.rotation}"); cs.endText()
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(72f, 640f)
                    cs.showText("The quick brown fox jumps over the lazy dog."); cs.endText()
                    if (i == 2) {
                        val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp); c.drawColor(Color.rgb(30, 120, 200))
                        val p = Paint().apply { color = Color.YELLOW }
                        for (k in 0 until 40) c.drawCircle((k * 53 % 1600).toFloat(), (k * 97 % 1200).toFloat(), 60f, p)
                        val img = LosslessFactory.createFromImage(doc, bmp)
                        cs.drawImage(img, 72f, 200f, 400f, 300f)
                    }
                }
            }
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val res = PDResources(); res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            form.defaultResources = res
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            val tf = PDTextField(form)
            tf.partialName = "fullName"
            tf.defaultAppearance = "/Helv 12 Tf 0 0 0 rg"
            form.fields.add(tf)
            val w = tf.widgets[0]
            w.rectangle = PDRectangle(72f, 560f, 250f, 24f)
            w.page = doc.getPage(0)
            w.isPrinted = true
            doc.getPage(0).annotations.add(w)
            tf.value = "initial"
            doc.save(f)
        }
        return f
    }

    private fun sizes(f: File): List<PageSize> = PDDocument.load(f).use { d ->
        d.pages.map { p ->
            val c = p.cropBox
            val rot = p.rotation
            if (rot == 90 || rot == 270) PageSize(c.height.toInt(), c.width.toInt()) else PageSize(c.width.toInt(), c.height.toInt())
        }
    }

    @Test fun geometryMatchesRotation() {
        val f = sample()
        PDDocument.load(f).use { d ->
            val g0 = BoxGeometry.of(d.getPage(0))
            val p = g0.toDisplay(72f, 680f)
            assertEquals(72f, p.x, 0.6f); assertEquals(792f - 680f, p.y, 0.6f)
            val g1 = BoxGeometry.of(d.getPage(1)) // rotate 90: dx = uy - y0 ; dy = ux - x0
            val q = g1.toDisplay(72f, 680f)
            assertEquals(680f, q.x, 0.6f); assertEquals(72f, q.y, 0.6f)
            val back = g1.toUser(q.x, q.y)
            assertEquals(72f, back.x, 0.6f); assertEquals(680f, back.y, 0.6f)
        }
    }

    @Test fun writeAllItemTypes() {
        val f = sample()
        val sz = sizes(f)
        val sig = File(out, "sig.png")
        val bmp = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawLine(10f, 80f, 290f, 20f, Paint().apply { color = Color.BLUE; strokeWidth = 8f })
        sig.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val items = ArrayList<Item>()
        for (page in 0 until 4) {
            // Display coordinates: the second line of text sits at display y ~= 792-640 on portrait pages.
            val w = sz[page].width.toFloat()
            items += MarkupItem(newId(), page, MarkupKind.HIGHLIGHT, listOf(RectF(72f, 140f, 300f, 156f)), 0xFFFFEB3B.toInt(), "quick brown")
            items += MarkupItem(newId(), page, MarkupKind.UNDERLINE, listOf(RectF(72f, 160f, 300f, 176f)), 0xFF2E7D32.toInt(), "u")
            items += MarkupItem(newId(), page, MarkupKind.STRIKEOUT, listOf(RectF(72f, 180f, 300f, 196f)), 0xFFE53935.toInt(), "s")
            items += MarkupItem(newId(), page, MarkupKind.SQUIGGLY, listOf(RectF(72f, 200f, 300f, 216f)), 0xFF8E24AA.toInt(), "q")
            items += InkItem(newId(), page, listOf(floatArrayOf(80f, 260f, 120f, 240f, 160f, 280f, 200f, 250f, 240f, 270f)), 0xFF1E88E5.toInt(), 3f, 0.8f)
            items += ShapeItem(newId(), page, ShapeKind.RECT, 300f, 240f, 380f, 300f, 0xFFE53935.toInt(), 2f)
            items += ShapeItem(newId(), page, ShapeKind.OVAL, 400f, 240f, 480f, 300f, 0xFF2E7D32.toInt(), 2f)
            items += ShapeItem(newId(), page, ShapeKind.ARROW, 80f, 330f, 220f, 380f, 0xFF111111.toInt(), 2f)
            items += NoteItem(newId(), page, w - 60f, 40f, "A sticky note on page ${page + 1}", 0xFFFFD54F.toInt())
            items += TextItem(newId(), page, RectF(72f, 400f, 360f, 440f), "Comment text box\nsecond line", 0xFFD32F2F.toInt(), 12f, true)
            items += TextItem(newId(), page, RectF(72f, 460f, 360f, 480f), "Fill & Sign text: John Doe", 0xFF111111.toInt(), 14f, false)
            items += TextItem(newId(), page, RectF(72f, 490f, 360f, 510f), "नमस्ते दुनिया — 你好", 0xFF111111.toInt(), 14f, false)
            items += StampItem(newId(), page, RectF(72f, 520f, 86f, 534f), StampKind.CHECK, null, 0xFF111111.toInt())
            items += StampItem(newId(), page, RectF(92f, 520f, 106f, 534f), StampKind.CROSS, null, 0xFF111111.toInt())
            items += StampItem(newId(), page, RectF(112f, 520f, 126f, 534f), StampKind.DOT, null, 0xFF111111.toInt())
            items += StampItem(newId(), page, RectF(132f, 520f, 146f, 534f), StampKind.CIRCLE, null, 0xFF111111.toInt())
            items += StampItem(newId(), page, RectF(160f, 524f, 208f, 530f), StampKind.LINE, null, 0xFF111111.toInt())
            items += StampItem(newId(), page, RectF(300f, 540f, 450f, 590f), StampKind.IMAGE, sig.absolutePath, 0xFF000000.toInt())
        }
        val dst = File(out, "annotated.pdf")
        PdfEditor.writeItems(f, null, items, "Tester", sz, dst)
        PDDocument.load(dst).use { d ->
            val annots = d.getPage(1).annotations.map { it.subtype }
            assertTrue(annots.toString(), annots.containsAll(listOf("Highlight", "Underline", "StrikeOut", "Squiggly", "Ink", "Square", "Circle", "Line", "Text", "FreeText")))
        }
        val ex = PdfEditor.readExtras(dst, null, sz)
        assertTrue(ex.hasForm)
        assertEquals(1, ex.fields.values.flatten().size)
        assertTrue(ex.annots[0]!!.any { it.subtype == "Highlight" })
        // edit + delete an annotation
        val hl = ex.annots[0]!!.first { it.subtype == "Text" }
        val edited = File(out, "edited.pdf")
        PdfEditor.editAnnotationContents(dst, null, 0, hl.index, "edited note", edited)
        val deleted = File(out, "deleted.pdf")
        PdfEditor.deleteAnnotation(edited, null, 0, hl.index, deleted)
        val after = PdfEditor.readExtras(deleted, null, sz)
        assertEquals(ex.annots[0]!!.size - 1, after.annots[0]!!.size)
    }

    @Test fun formsOrganizeAndTools() {
        val f = sample()
        val sz = sizes(f)
        val filled = File(out, "filled.pdf")
        PdfEditor.setFormValues(f, null, mapOf("fullName" to "Jane Q. Public"), filled)
        assertEquals("Jane Q. Public", PdfEditor.readExtras(filled, null, sz).fields[0]!!.first().value)
        PdfEditor.flattenForm(filled, null, File(out, "flattened.pdf"))

        val org = File(out, "organized.pdf")
        PdfEditor.rearrange(f, null, listOf(PageSpec(2, rotate = 90), PageSpec(0), PageSpec(-1, blank = true), PageSpec(0), PageSpec(3, rotate = 180)), org)
        PDDocument.load(org).use { d ->
            assertEquals(5, d.numberOfPages)
            assertEquals(90, d.getPage(0).rotation)
            assertEquals(90, d.getPage(4).rotation) // 270 + 180
        }
        val merged = File(out, "merged.pdf")
        PdfEditor.merge(listOf(f, org), merged)
        PDDocument.load(merged).use { assertEquals(9, it.numberOfPages) }

        val parts = PdfEditor.splitEvery(f, null, 3, out, "split")
        assertEquals(2, parts.size)

        val comp = File(out, "compressed.pdf")
        val r = PdfEditor.compress(f, null, 800, 0.6f, comp)
        println("compress: ${r.before} -> ${r.after}, images=${r.images}")
        assertTrue(r.images >= 1)
        assertTrue(r.after < r.before)

        val prot = File(out, "protected.pdf")
        PdfEditor.protect(f, null, "secret", "owner-pw", true, true, false, prot)
        assertTrue(PdfEditor.isEncrypted(prot))
        val unl = File(out, "unlocked.pdf")
        PdfEditor.removeSecurity(prot, "secret", unl)
        PDDocument.load(unl).use { assertTrue(!it.isEncrypted) }

        // Editing a password-protected file keeps it protected with the same password.
        val protEdited = File(out, "protected_edited.pdf")
        PdfEditor.writeItems(prot, "secret", listOf(NoteItem(newId(), 0, 100f, 100f, "note", 0xFFFFD54F.toInt())), "T", sz, protEdited)
        assertTrue(PdfEditor.isEncrypted(protEdited))
        PDDocument.load(protEdited, "secret").use { assertEquals(1, it.getPage(0).annotations.count { a -> a.subtype == "Text" }) }
        PdfEditor.rearrange(prot, "secret", listOf(PageSpec(1), PageSpec(0)), File(out, "protected_reordered.pdf"))

        PdfEditor.watermark(f, null, "CONFIDENTIAL", 0.25f, 72f, 0xFF9E9E9E.toInt(), File(out, "watermark.pdf"))
        PdfEditor.pageNumbers(f, null, "Page {n} of {total}", 10f, File(out, "numbered.pdf"))
        PdfEditor.setMetadata(f, null, "T", "A", "S", "K", File(out, "meta.pdf"))

        val imgs = (0 until 2).map { k ->
            Bitmap.createBitmap(if (k == 0) 800 else 600, if (k == 0) 600 else 900, Bitmap.Config.ARGB_8888).apply { eraseColor(if (k == 0) Color.RED else Color.GREEN) }
        }
        PdfEditor.imagesToPdf(imgs.asSequence(), PdfEditor.PageFormat.A4, File(out, "images.pdf"))
        PDDocument.load(File(out, "images.pdf")).use { assertEquals(2, it.numberOfPages) }
    }
}
