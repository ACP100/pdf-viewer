package com.pdfviewer.app.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfviewer.app.ui.viewer.ViewerDialog.TextInput.Purpose as P
import com.pdfviewer.app.annot.FieldType
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.data.Settings
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.graphics.Path as ComposePath

@Composable
fun ViewerDialogs(c: ViewerController) {
    c.passwordPrompt?.let { wrong -> PasswordDialog(wrong, onCancel = { c.passwordPrompt = null; c.onFinish?.invoke() }, onOk = { c.submitPassword(it) }) }
    val d = c.dialog ?: return
    val close = { c.dialog = null }
    when (d) {
        ViewerDialog.GoToPage -> GoToPageDialog(c.page, c.pageCount, close) { c.view.goToPage(it); close() }
        ViewerDialog.Info -> InfoDialog(c, close)
        ViewerDialog.Settings -> SettingsDialog(c, close)
        ViewerDialog.SaveOnExit -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Save changes?") },
            text = { Text("You have changes that haven't been saved to \"${c.name}\".") },
            confirmButton = { TextButton(onClick = { close(); c.save(thenFinish = true) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { close(); c.discardAndFinish() }) { Text("Discard") }
                    TextButton(onClick = close) { Text("Cancel") }
                }
            },
        )
        is ViewerDialog.Link -> {
            val ctx = LocalContext.current
            AlertDialog(onDismissRequest = close, title = { Text("Open link?") }, text = { Text(d.uri) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.uri))) }.onFailure { c.msg("No app can open this link") }
                    }) { Text("Open") }
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
        }
        is ViewerDialog.Annot -> {
            val a = d.annot
            AlertDialog(onDismissRequest = close,
                title = { Text(a.subtype) },
                text = {
                    Column {
                        a.author?.takeIf { it.isNotBlank() }?.let { Text("By $it", style = MaterialTheme.typography.labelMedium) }
                        Files.pdfDate(a.modified)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        Spacer(Modifier.height(8.dp))
                        Text(a.contents?.takeIf { it.isNotBlank() } ?: "(no comment text)")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        c.dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.EDIT_ANNOT, a.contents.orEmpty(), a.page, 0f, 0f, annot = a)
                    }) { Text("Edit") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { close(); c.deleteExisting(a) }) { Text("Delete") }
                        TextButton(onClick = close) { Text("Close") }
                    }
                })
        }
        is ViewerDialog.Field -> FieldDialog(c, d, close)
        is ViewerDialog.TextInput -> TextInputDialog(c, d, close)
        is ViewerDialog.Signature -> SignatureDialog(
            initials = d.initials, onCancel = close,
            onSave = { bmp ->
                val f = c.signatureFile(d.initials)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                close()
                c.onSignatureSaved(d.initials, d.page, d.x, d.y)
            },
        )
    }
}

@Composable
fun PasswordDialog(wrong: Boolean, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text("Password protected") },
        text = {
            Column {
                Text(if (wrong) "Incorrect password. Try again." else "Enter the password to open this document.",
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.focusRequester(focus))
            }
        },
        confirmButton = { TextButton(onClick = { onOk(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

@Composable
private fun GoToPageDialog(page: Int, count: Int, close: () -> Unit, go: (Int) -> Unit) {
    var text by remember { mutableStateOf("${page + 1}") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..count
    AlertDialog(onDismissRequest = close, title = { Text("Go to page") },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true,
                label = { Text("Page (1–$count)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = text.isNotEmpty() && !valid)
        },
        confirmButton = { TextButton(onClick = { go(n!! - 1) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun InfoDialog(c: ViewerController, close: () -> Unit) {
    val m = c.meta
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(m?.title.orEmpty()) }
    var author by remember { mutableStateOf(m?.author.orEmpty()) }
    var subject by remember { mutableStateOf(m?.subject.orEmpty()) }
    var keywords by remember { mutableStateOf(m?.keywords.orEmpty()) }
    val size = c.engine?.pageSizes?.getOrNull(c.page)
    AlertDialog(onDismissRequest = close, title = { Text("Document properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (editing) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true)
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true)
                    OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords") }, singleLine = true)
                } else {
                    Prop("File", c.name)
                    Prop("Size", Files.humanSize(c.fileSize))
                    Prop("Pages", "${c.pageCount}")
                    size?.let {
                        Prop("Page size", "%.0f × %.0f pt  (%.1f × %.1f cm)".format(it.width.toFloat(), it.height.toFloat(), it.width / 72f * 2.54f, it.height / 72f * 2.54f))
                    }
                    Prop("Title", m?.title); Prop("Author", m?.author); Prop("Subject", m?.subject); Prop("Keywords", m?.keywords)
                    Prop("Creator", m?.creator); Prop("Producer", m?.producer)
                    Prop("Created", Files.pdfDate(m?.created)); Prop("Modified", Files.pdfDate(m?.modified))
                    Prop("Security", if (c.password != null) "Password protected" else "None")
                    Prop("Form", if (c.extras?.hasForm == true) "Has fillable fields" else "No")
                    Prop("Location", c.uri?.toString())
                }
            }
        },
        confirmButton = {
            if (editing) TextButton(onClick = { c.updateMetadata(title, author, subject, keywords); close() }) { Text("Save") }
            else TextButton(onClick = close) { Text("Close") }
        },
        dismissButton = { if (!editing) TextButton(onClick = { editing = true }) { Text("Edit") } else TextButton(onClick = { editing = false }) { Text("Cancel") } })
}

@Composable
private fun Prop(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsDialog(c: ViewerController, close: () -> Unit) {
    var author by remember { mutableStateOf(Settings.author) }
    var fields by remember { mutableStateOf(Settings.highlightFields) }
    AlertDialog(onDismissRequest = close, title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(author, { author = it }, label = { Text("Author name for comments") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { fields = !fields }) {
                    Checkbox(fields, { fields = it }); Text("Highlight form fields")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Settings.author = author; c.setHighlightFields(fields); close() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun FieldDialog(c: ViewerController, d: ViewerDialog.Field, close: () -> Unit) {
    val f = d.field
    var value by remember { mutableStateOf(f.value) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (f.type == FieldType.TEXT) runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = close, title = { Text(f.label) },
        text = {
            when (f.type) {
                FieldType.TEXT -> OutlinedTextField(value, { value = if (f.maxLen > 0) it.take(f.maxLen) else it },
                    singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, modifier = Modifier.focusRequester(focus),
                    supportingText = if (f.maxLen > 0) ({ Text("${value.length}/${f.maxLen}") }) else null)
                FieldType.COMBO, FieldType.LIST -> Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val exports = if (f.exportValues.size == f.options.size) f.exportValues else f.options
                    f.options.forEachIndexed { i, o ->
                        val ev = exports[i]
                        Row(Modifier.fillMaxWidth().clickable { value = ev }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(value == ev, { value = ev }); Text(o)
                        }
                    }
                    if (f.type == FieldType.COMBO) OutlinedTextField(value, { value = it }, label = { Text("Or type a value") }, singleLine = true)
                }
                else -> Text(value)
            }
        },
        confirmButton = { TextButton(onClick = { close(); if (value != f.value) c.setFieldValue(f, value) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (value.isNotEmpty()) TextButton(onClick = { close(); c.setFieldValue(f, "") }) { Text("Clear") }
                TextButton(onClick = close) { Text("Cancel") }
            }
        })
}

@Composable
private fun TextInputDialog(c: ViewerController, d: ViewerDialog.TextInput, close: () -> Unit) {
    var text by remember { mutableStateOf(d.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val title = when (d.purpose) {
        P.NOTE -> "Add note"; P.COMMENT_TEXT -> "Add text"; P.FILL_TEXT -> "Add text"
        P.EDIT_ITEM -> "Edit"; P.EDIT_ANNOT -> "Edit comment"; P.MARKUP_NOTE -> "Add a comment"
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
        confirmButton = {
            TextButton(onClick = {
                close()
                when (d.purpose) {
                    P.NOTE -> if (text.isNotBlank()) c.addNote(d.page, d.x, d.y, text)
                    P.COMMENT_TEXT -> c.addText(d.page, d.x, d.y, text, asAnnotation = true)
                    P.FILL_TEXT -> c.addText(d.page, d.x, d.y, text, asAnnotation = false)
                    P.EDIT_ITEM, P.MARKUP_NOTE -> c.editItemText(d.itemId, text)
                    P.EDIT_ANNOT -> d.annot?.let { c.editExisting(it, text) }
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

// --------------------------------------------------------------------- signature pad

@Composable
fun SignatureDialog(initials: Boolean, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var color by remember { mutableStateOf(0xFF0D1B4C.toInt()) }
    var typed by remember { mutableStateOf("") }
    var typeMode by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(if (initials) "Your initials" else "Your signature") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { typeMode = false }) { Text("Draw", fontWeight = if (!typeMode) FontWeight.Bold else FontWeight.Normal) }
                    TextButton(onClick = { typeMode = true }) { Text("Type", fontWeight = if (typeMode) FontWeight.Bold else FontWeight.Normal) }
                    Spacer(Modifier.weight(1f))
                    listOf(0xFF0D1B4C.toInt(), 0xFF111111.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt()).forEach { col ->
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(col))
                            .border(if (col == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { color = col })
                    }
                }
                if (typeMode) {
                    OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(if (initials) "Initials" else "Full name") })
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Color.White).border(1.dp, Color.LightGray), contentAlignment = Alignment.Center) {
                        Text(typed, color = Color(color), fontSize = 34.sp, fontWeight = FontWeight.Light,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive)
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(if (initials) 140.dp else 180.dp).background(Color.White).border(1.dp, Color.LightGray)
                            .onSizeChanged { size = it }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { current = listOf(it) },
                                    onDrag = { ch, _ -> current = current + ch.position },
                                    onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                                    onDragCancel = { current = emptyList() },
                                )
                            }
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            val w = if (initials) 4.dp.toPx() else 3.dp.toPx()
                            (strokes + listOf(current)).forEach { s ->
                                if (s.size < 2) return@forEach
                                val p = ComposePath().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                                drawPath(p, Color(color), style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            drawLine(Color(0x33000000), Offset(16f, this.size.height * 0.78f), Offset(this.size.width - 16f, this.size.height * 0.78f), 2f)
                        }
                        if (strokes.isEmpty() && current.isEmpty()) Text("Sign here", Modifier.align(Alignment.Center), color = Color.LightGray)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = if (typeMode) typed.isNotBlank() else strokes.isNotEmpty(), onClick = {
                val bmp = if (typeMode) renderTyped(typed, color) else renderStrokes(strokes, size, color, if (initials) 4f else 3f)
                if (bmp != null) onSave(bmp)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear(); typed = "" }) { Text("Clear") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        })
}

/** Renders strokes cropped to their bounds at 2x for crisp output. */
private fun renderStrokes(strokes: List<List<Offset>>, size: IntSize, color: Int, widthDp: Float): Bitmap? {
    if (strokes.isEmpty() || size.width == 0) return null
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = 0f; var b = 0f
    strokes.flatten().forEach { l = min(l, it.x); t = min(t, it.y); r = max(r, it.x); b = max(b, it.y) }
    val pad = 12f
    l -= pad; t -= pad; r += pad; b += pad
    val k = 2f
    val w = max(2, ((r - l) * k).toInt()); val h = max(2, ((b - t) * k).toInt())
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = widthDp * 2.75f * k
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { s ->
        val p = Path()
        p.moveTo((s[0].x - l) * k, (s[0].y - t) * k)
        for (i in 1 until s.size) p.lineTo((s[i].x - l) * k, (s[i].y - t) * k)
        cv.drawPath(p, paint)
    }
    return bmp
}

private fun renderTyped(text: String, color: Int): Bitmap? {
    if (text.isBlank()) return null
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = 120f
        typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.ITALIC)
    }
    val w = paint.measureText(text).toInt() + 40
    val fm = paint.fontMetrics
    val h = (fm.descent - fm.ascent).toInt() + 30
    val bmp = Bitmap.createBitmap(max(2, w), max(2, h), Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawText(text, 20f, -fm.ascent + 15f, paint)
    return bmp
}

