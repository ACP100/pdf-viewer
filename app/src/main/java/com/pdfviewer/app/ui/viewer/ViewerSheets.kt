package com.pdfviewer.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfviewer.app.annot.ExistingAnnot
import com.pdfviewer.app.annot.FieldType
import com.pdfviewer.app.annot.FormFieldInfo
import com.pdfviewer.app.annot.InkItem
import com.pdfviewer.app.annot.Item
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.NoteItem
import com.pdfviewer.app.annot.ShapeItem
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.TextItem
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.data.UserBookmark
import com.pdfviewer.app.pdf.OutlineItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerSheets(c: ViewerController) {
    val sheet = c.sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == Sheet.THUMBNAILS || sheet == Sheet.FORM_FIELDS)
    ModalBottomSheet(onDismissRequest = { c.sheet = null }, sheetState = state) {
        when (sheet) {
            Sheet.OUTLINE -> OutlineSheet(c)
            Sheet.THUMBNAILS -> ThumbnailSheet(c)
            Sheet.COMMENTS -> CommentsSheet(c)
            Sheet.FORM_FIELDS -> FormSheet(c)
        }
    }
}

// --------------------------------------------------------------------- outline & bookmarks

@Composable
private fun OutlineSheet(c: ViewerController) {
    var tab by remember { mutableStateOf(if (c.outline.isEmpty() && c.bookmarks.isNotEmpty()) 1 else 0) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bookmarks (${c.bookmarks.size})") })
        }
        if (tab == 0) {
            if (c.outline.isEmpty()) Empty("This document has no table of contents.")
            else {
                val expanded = remember { mutableStateMapOf<OutlineItem, Boolean>() }
                val rows = remember(c.outline, expanded.toMap()) {
                    val out = ArrayList<OutlineItem>()
                    fun walk(list: List<OutlineItem>) { list.forEach { out += it; if (expanded[it] == true) walk(it.children) } }
                    walk(c.outline); out
                }
                LazyColumn {
                    items(rows) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { c.view.goToPage(item.page); c.sheet = null }
                                .padding(start = (8 + item.level * 16).dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.children.isNotEmpty()) {
                                IconButton(onClick = { expanded[item] = !(expanded[item] ?: false) }) {
                                    Icon(if (expanded[item] == true) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, "Expand")
                                }
                            } else Spacer(Modifier.width(48.dp))
                            Text(item.title.ifBlank { "(untitled)" }, Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (item.level == 0) FontWeight.Medium else FontWeight.Normal)
                            Text("${item.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            var renaming by remember { mutableStateOf<UserBookmark?>(null) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmark pages to jump back quickly.", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { c.toggleBookmark() }) {
                    Icon(Icons.Filled.BookmarkAdd, null); Spacer(Modifier.width(6.dp))
                    Text(if (c.isBookmarked(c.page)) "Remove p.${c.page + 1}" else "Add p.${c.page + 1}")
                }
            }
            if (c.bookmarks.isEmpty()) Empty("No bookmarks yet.")
            LazyColumn {
                items(c.bookmarks) { b ->
                    Row(Modifier.fillMaxWidth().clickable { c.view.goToPage(b.page); c.sheet = null }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(b.title); Text("Page ${b.page + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { renaming = b }) { Icon(Icons.Filled.Edit, "Rename") }
                        IconButton(onClick = { c.toggleBookmark(b.page) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
            renaming?.let { b ->
                var text by remember(b) { mutableStateOf(b.title) }
                AlertDialog(onDismissRequest = { renaming = null },
                    title = { Text("Rename bookmark") },
                    text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                    confirmButton = { TextButton(onClick = { c.renameBookmark(b, text); renaming = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --------------------------------------------------------------------- thumbnails

@Composable
fun PageThumb(c: ViewerController, index: Int, modifier: Modifier = Modifier) {
    val e = c.engine
    val ratio = e?.pageSizes?.getOrNull(index)?.let { it.width.toFloat() / it.height } ?: 0.707f
    val bmp by produceState<Bitmap?>(null, index, e) { value = c.thumbnail(index) }
    Box(modifier.aspectRatio(ratio).background(Color.White).border(1.dp, Color(0x22000000))) {
        bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun ThumbnailSheet(c: ViewerController) {
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) { grid.scrollToItem((c.page - 3).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { c.sheet = null; c.organize() }) { Text("Organize") }
        }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), state = grid, modifier = Modifier.padding(horizontal = 8.dp)) {
            itemsIndexed(List(c.pageCount) { it }) { _, i ->
                val current = i == c.page
                Column(
                    Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .border(if (current) 3.dp else 0.dp, if (current) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { c.view.goToPage(i); c.sheet = null }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PageThumb(c, i, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.isBookmarked(i)) Icon(Icons.Filled.Bookmark, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("${i + 1}", fontSize = 12.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- comments

private fun iconFor(sub: String): ImageVector = when (sub) {
    "Highlight" -> Icons.Filled.Highlight
    "Underline" -> Icons.Filled.FormatUnderlined
    "StrikeOut", "Squiggly" -> Icons.Filled.FormatStrikethrough
    "Text" -> Icons.AutoMirrored.Filled.StickyNote2
    "FreeText" -> Icons.Filled.TextFields
    "Ink" -> Icons.Filled.Draw
    "Stamp" -> Icons.Filled.Image
    else -> Icons.Filled.Interests
}

private fun describe(i: Item): Pair<String, String> = when (i) {
    is MarkupItem -> (when (i.kind) { MarkupKind.HIGHLIGHT -> "Highlight"; MarkupKind.UNDERLINE -> "Underline"; MarkupKind.STRIKEOUT -> "StrikeOut"; MarkupKind.SQUIGGLY -> "Squiggly" }) to
        (if (i.note.isNotBlank()) "${i.note}\n“${i.text.take(80)}”" else "“${i.text.take(120)}”")
    is InkItem -> "Ink" to "Drawing"
    is ShapeItem -> "Shape" to i.kind.name.lowercase().replaceFirstChar { it.uppercase() }
    is NoteItem -> "Text" to i.text
    is TextItem -> "FreeText" to i.text
    is StampItem -> "Stamp" to i.kind.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun CommentsSheet(c: ViewerController) {
    val existing = c.extras?.annots?.values?.flatten().orEmpty().sortedWith(compareBy({ it.page }, { it.rect.top }))
    val pending = c.items.sortedWith(compareBy({ it.page }, { it.bounds().top }))
    var editing by remember { mutableStateOf<ExistingAnnot?>(null) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Text("Comments (${existing.size + pending.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (existing.isEmpty() && pending.isEmpty()) Empty("No comments or markups yet. Use Comment to add some.")
        LazyColumn {
            if (pending.isNotEmpty()) {
                item { SmallLabel("Not yet saved") }
                items(pending, key = { "p" + it.id }) { i ->
                    val (sub, text) = describe(i)
                    CommentRow(iconFor(sub), sub, text, "Page ${i.page + 1}", onClick = {
                        c.sheet = null; c.view.scrollToRect(i.page, i.bounds(), onlyIfHidden = false); c.view.selectItem(i)
                    }, onDelete = { c.onItemsErased(setOf(i.id)) }, onEdit = null)
                }
                item {
                    Button(onClick = { c.sheet = null; c.save() }, modifier = Modifier.padding(16.dp)) { Text("Save all to file") }
                }
            }
            if (existing.isNotEmpty()) {
                item { SmallLabel("In this file") }
                items(existing, key = { "e${it.page}_${it.index}" }) { a ->
                    val meta = buildString {
                        append("Page ${a.page + 1}")
                        a.author?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        Files.pdfDate(a.modified)?.let { append(" · ").append(it) }
                    }
                    CommentRow(iconFor(a.subtype), a.subtype, a.contents?.takeIf { it.isNotBlank() } ?: "(no text)", meta,
                        onClick = { c.sheet = null; c.view.scrollToRect(a.page, a.rect, onlyIfHidden = false) },
                        onDelete = { c.deleteExisting(a) }, onEdit = { editing = a })
                }
            }
        }
    }
    editing?.let { a ->
        var text by remember(a) { mutableStateOf(a.contents.orEmpty()) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit comment") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { c.editExisting(a, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun CommentRow(icon: ImageVector, title: String, text: String, meta: String, onClick: () -> Unit, onDelete: () -> Unit, onEdit: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
    }
    HorizontalDivider()
}

// --------------------------------------------------------------------- form fields

@Composable
private fun FormSheet(c: ViewerController) {
    val fields = c.extras?.fields?.values?.flatten().orEmpty().sortedWith(compareBy({ it.page }, { it.rect.top }, { it.rect.left }))
    // One entry per field name (radio groups have several widgets).
    val byName = LinkedHashMap<String, MutableList<FormFieldInfo>>()
    fields.forEach { byName.getOrPut(it.name) { ArrayList() } += it }
    val edits = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fill form", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { c.sheet = null; c.flattenForm() }) { Text("Flatten") }
            Button(onClick = { c.setFieldValues(edits.toMap()); c.sheet = null }, enabled = edits.isNotEmpty()) { Text("Apply") }
        }
        Text("Tip: you can also tap fields directly on the page.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(byName.entries.toList(), key = { it.key }) { (name, widgets) ->
                val f = widgets.first()
                val value = edits[name] ?: f.value
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("p.${f.page + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { c.view.scrollToRect(f.page, f.rect, onlyIfHidden = false) })
                    }
                    when (f.type) {
                        FieldType.TEXT -> OutlinedTextField(value, { edits[name] = it }, Modifier.fillMaxWidth(), enabled = !f.readOnly,
                            singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1)
                        FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                            val on = value.isNotEmpty() && value != "Off"
                            Checkbox(on, { edits[name] = if (it) (f.onValue ?: "Yes") else "Off" }, enabled = !f.readOnly)
                            Text(if (on) "Checked" else "Not checked")
                        }
                        FieldType.RADIO -> Column {
                            widgets.mapNotNull { it.onValue }.distinct().forEach { opt ->
                                Row(Modifier.clickable(enabled = !f.readOnly) { edits[name] = opt }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(value == opt, { edits[name] = opt }, enabled = !f.readOnly); Text(opt)
                                }
                            }
                        }
                        FieldType.COMBO, FieldType.LIST -> ChoiceField(f, value) { edits[name] = it }
                        FieldType.SIGNATURE -> OutlinedButton(onClick = {
                            c.sheet = null; c.onFormFieldTap(f)
                        }) { Text("Sign here") }
                        FieldType.BUTTON -> Text("(button)", fontSize = 12.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
fun ChoiceField(f: FormFieldInfo, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val opts = f.options
    val exports = if (f.exportValues.size == opts.size) f.exportValues else opts
    val shown = opts.getOrNull(exports.indexOf(value)) ?: value
    Box {
        OutlinedButton(onClick = { open = true }, enabled = !f.readOnly, modifier = Modifier.fillMaxWidth()) {
            Text(shown.ifEmpty { "Choose…" }, Modifier.weight(1f)); Icon(Icons.Filled.ExpandMore, null)
        }
        DropdownMenu(open, { open = false }) {
            opts.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(exports.getOrElse(i) { o }); open = false }) }
        }
    }
}
