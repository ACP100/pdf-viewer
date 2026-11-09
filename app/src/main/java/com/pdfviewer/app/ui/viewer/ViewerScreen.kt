package com.pdfviewer.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pdfviewer.app.annot.InkItem
import com.pdfviewer.app.annot.MarkupItem
import com.pdfviewer.app.annot.MarkupKind
import com.pdfviewer.app.annot.NoteItem
import com.pdfviewer.app.annot.StampItem
import com.pdfviewer.app.annot.StampKind
import com.pdfviewer.app.annot.TextItem
import com.pdfviewer.app.annot.Tool
import kotlinx.coroutines.delay

val Palette = listOf(
    0xFFFFEB3B, 0xFF8BC34A, 0xFF4FC3F7, 0xFFF48FB1, 0xFFFFA726,
    0xFFE53935, 0xFF8E24AA, 0xFF1E88E5, 0xFF2E7D32, 0xFF111111, 0xFF757575, 0xFFFFFFFF,
).map { it.toInt() }

@Composable
fun ViewerScreen(c: ViewerController) {
    val showTop = c.chrome || c.mode != ViewerMode.VIEW || c.searchOpen || c.selection != null

    BackHandler(enabled = true) {
        when {
            c.reflow -> c.reflow = false
            c.sheet != null -> c.sheet = null
            c.selection != null -> c.view.clearSelection()
            c.selectedItem != null -> c.view.selectItem(null)
            c.searchOpen -> c.closeSearch()
            c.tool != Tool.NONE -> c.selectTool(Tool.NONE)
            c.mode != ViewerMode.VIEW -> c.exitMode()
            else -> if (c.requestExit()) c.onFinish?.invoke()
        }
    }

    val density = LocalDensity.current
    val sysTop = WindowInsets.statusBars.getTop(density)
    val sysBottom = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(sysTop, sysBottom) {
        with(density) { c.view.setInsets(sysTop + 56.dp.toPx(), sysBottom + 72.dp.toPx()) }
    }
    // Keep the selection actions away from the selected text.
    val selectionInTopHalf = c.selection?.let { sel ->
        sel.rects.isNotEmpty() && c.view.screenRectOf(sel.page, com.pdfviewer.app.annot.union(sel.rects)).centerY() < c.view.height * 0.55f
    } ?: false

    Box(Modifier.fillMaxSize().background(if (c.colorMode == 1) Color(0xFF101010) else Color(0xFFE6E6E6))) {
        AndroidView(factory = { c.view }, modifier = Modifier.fillMaxSize())

        if (c.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        c.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { c.onFinish?.invoke() }) { Text("Close") }
            }
        }

        // ------------------------------------------------------------------ top
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            AnimatedVisibility(showTop, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.statusBarsPadding()) {
                        when {
                            c.searchOpen -> SearchBar(c)
                            c.mode != ViewerMode.VIEW -> EditTopBar(c)
                            else -> ViewTopBar(c)
                        }
                        if (c.searching) LinearProgressIndicator(progress = { c.searchProgress }, modifier = Modifier.fillMaxWidth())
                        if (c.busy != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (c.selection != null && !selectionInTopHalf) SelectionBar(c)
            PageChip(c, visible = showTop)
        }

        // ------------------------------------------------------------------ bottom
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            SnackbarHost(c.snackbar)
            if (c.selection != null && selectionInTopHalf) SelectionBar(c)
            if (c.selectedItem != null) ItemBar(c)
            if (c.tts.active) TtsBar(c)
            AnimatedVisibility(c.mode == ViewerMode.VIEW && c.chrome && c.engine != null && c.selection == null && !c.searchOpen && !c.tts.active,
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                QuickBar(c)
            }
            if (c.mode == ViewerMode.ANNOTATE) AnnotateToolbar(c)
            if (c.mode == ViewerMode.FILL_SIGN) FillSignToolbar(c)
        }

        c.busy?.let { label ->
            Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp)); Text(label)
                }
            }
        }

        ViewerSheets(c)
        ViewerDialogs(c)
        if (c.reflow) ReflowScreen(c)
    }
}

@Composable
private fun PageChip(c: ViewerController, visible: Boolean) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(c.page) { flash = true; delay(1400); flash = false }
    if (c.pageCount > 0 && (visible || flash)) {
        Surface(
            Modifier.padding(start = 12.dp, top = 8.dp).clickable { c.dialog = ViewerDialog.GoToPage },
            shape = RoundedCornerShape(50), color = Color(0xCC303030),
        ) {
            Text("${c.page + 1} / ${c.pageCount}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun ViewTopBar(c: ViewerController) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (c.requestExit()) c.onFinish?.invoke() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (c.hasUnsaved) Text("Unsaved changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { c.openSearch() }, enabled = c.engine != null) { Icon(Icons.Filled.Search, "Search") }
        IconButton(onClick = { c.toggleBookmark() }, enabled = c.engine != null) {
            Icon(if (c.isBookmarked(c.page)) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark page")
        }
        IconButton(onClick = { c.sheet = Sheet.OUTLINE }, enabled = c.engine != null) { Icon(Icons.AutoMirrored.Filled.Toc, "Contents") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            ViewerMenu(c, menu) { menu = false }
        }
    }
}

@Composable
private fun ViewerMenu(c: ViewerController, expanded: Boolean, dismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        fun item(label: String, enabled: Boolean = true, action: () -> Unit): @Composable () -> Unit = {
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { dismiss(); action() })
        }
        val ready = c.engine != null
        item(if (c.horizontal) "✓ Single page (swipe)" else "Single page (swipe)", ready) { c.setHorizontalScroll(true) }()
        item(if (!c.horizontal) "✓ Continuous scroll" else "Continuous scroll", ready) { c.setHorizontalScroll(false) }()
        HorizontalDivider()
        item((if (c.colorMode == 0) "✓ " else "") + "Normal colors") { c.setColorModeIndex(0) }()
        item((if (c.colorMode == 1) "✓ " else "") + "Night mode") { c.setColorModeIndex(1) }()
        item((if (c.colorMode == 2) "✓ " else "") + "Sepia (reading)") { c.setColorModeIndex(2) }()
        item((if (c.keepScreenOn) "✓ " else "") + "Keep screen on") { c.setKeepScreenOnPref(!c.keepScreenOn) }()
        HorizontalDivider()
        item("Go to page…", ready) { c.dialog = ViewerDialog.GoToPage }()
        item("Page thumbnails", ready) { c.sheet = Sheet.THUMBNAILS }()
        item("Bookmarks & contents", ready) { c.sheet = Sheet.OUTLINE }()
        item("Comments list", ready) { c.sheet = Sheet.COMMENTS }()
        if (c.extras?.hasForm == true) item("Form fields", ready) { c.sheet = Sheet.FORM_FIELDS }()
        item("Read aloud", ready) { c.startReadAloud() }()
        item("Reading mode (reflow text)", ready) { c.reflow = true }()
        HorizontalDivider()
        item("Comment & annotate", ready) { c.enterMode(ViewerMode.ANNOTATE) }()
        item("Fill & Sign", ready) { c.enterMode(ViewerMode.FILL_SIGN) }()
        item("Organize pages", ready) { c.organize() }()
        HorizontalDivider()
        item("Save", ready) { c.save() }()
        item("Save a copy…", ready) { c.saveAs() }()
        item("Share", ready) { c.share() }()
        item("Print", ready) { c.print() }()
        item("Document properties", ready) { c.dialog = ViewerDialog.Info }()
        item("Settings") { c.dialog = ViewerDialog.Settings }()
    }
}

@Composable
private fun EditTopBar(c: ViewerController) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.exitMode() }) { Icon(Icons.Filled.Close, "Done") }
        Text(if (c.mode == ViewerMode.ANNOTATE) "Comment" else "Fill & Sign", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { c.undo() }, enabled = c.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
        IconButton(onClick = { c.redo() }, enabled = c.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
        IconButton(onClick = { c.sheet = Sheet.COMMENTS }) { Icon(Icons.AutoMirrored.Filled.Comment, "Comments") }
        IconButton(onClick = { c.save() }, enabled = c.hasUnsaved) { Icon(Icons.Filled.Save, "Save") }
        TextButton(onClick = { c.exitMode() }) { Text("Done") }
    }
}

@Composable
private fun SearchBar(c: ViewerController) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        TextField(
            value = c.query, onValueChange = { c.query = it },
            placeholder = { Text("Find in document") }, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { kb?.hide(); c.runSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (c.hits.isNotEmpty()) Text("${c.hitIndex + 1}/${c.hits.size}", style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { c.prevHit() }, enabled = c.hits.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { c.nextHit() }, enabled = c.hits.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
        Box {
            IconButton(onClick = { options = true }) { Icon(Icons.Filled.Tune, "Search options") }
            DropdownMenu(options, { options = false }) {
                DropdownMenuItem(text = { Text((if (c.matchCase) "✓ " else "") + "Match case") }, onClick = { c.matchCase = !c.matchCase; options = false; c.runSearch() })
                DropdownMenuItem(text = { Text((if (c.wholeWord) "✓ " else "") + "Whole words") }, onClick = { c.wholeWord = !c.wholeWord; options = false; c.runSearch() })
            }
        }
    }
}

@Composable
private fun SelectionBar(c: ViewerController) {
    Surface(Modifier.padding(8.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, tonalElevation = 3.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.ContentCopy, "Copy") { c.copySelection() }
            ActionButton(Icons.Filled.Highlight, "Highlight") { c.markupSelection(MarkupKind.HIGHLIGHT) }
            ActionButton(Icons.Filled.FormatUnderlined, "Underline") { c.markupSelection(MarkupKind.UNDERLINE) }
            ActionButton(Icons.Filled.FormatStrikethrough, "Strike") { c.markupSelection(MarkupKind.STRIKEOUT) }
            ActionButton(Icons.AutoMirrored.Filled.Comment, "Comment") { c.commentOnSelection() }
            ActionButton(Icons.Filled.Share, "Share") { c.shareSelection() }
            ActionButton(Icons.Filled.RecordVoiceOver, "Read") { c.readSelection() }
            ActionButton(Icons.Filled.Search, "Find") { c.searchSelection() }
            ActionButton(Icons.Filled.Language, "Web") { c.webSearchSelection() }
            ActionButton(Icons.Filled.SelectAll, "Page") { c.view.selectAllOnPage(c.selection?.page ?: c.page) }
            ActionButton(Icons.Filled.Close, "Close") { c.view.clearSelection() }
        }
    }
}

@Composable
fun ActionButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.9f else 0.38f))
    }
}

@Composable
private fun QuickBar(c: ViewerController) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 8.dp, tonalElevation = 3.dp) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                ActionButton(Icons.Filled.BorderColor, "Comment") { c.enterMode(ViewerMode.ANNOTATE) }
                ActionButton(Icons.Filled.Draw, "Fill & Sign") { c.enterMode(ViewerMode.FILL_SIGN) }
                ActionButton(Icons.Filled.GridView, "Pages") { c.sheet = Sheet.THUMBNAILS }
                ActionButton(Icons.Filled.ViewAgenda, "Organize") { c.organize() }
                ActionButton(Icons.AutoMirrored.Filled.ChromeReaderMode, "Reflow") { c.reflow = true }
                ActionButton(Icons.Filled.RecordVoiceOver, "Read") { c.startReadAloud() }
                ActionButton(Icons.Filled.Share, "Share") { c.share() }
            }
        }
    }
}

@Composable
private fun ToolButton(c: ViewerController, t: Tool, icon: ImageVector, label: String) {
    ActionButton(icon, label, selected = c.tool == t) { c.selectTool(if (c.tool == t) Tool.NONE else t) }
}

@Composable
private fun AnnotateToolbar(c: ViewerController) {
    Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column {
            if (c.tool != Tool.NONE) StyleRow(c)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
                ToolButton(c, Tool.HIGHLIGHT, Icons.Filled.Highlight, "Highlight")
                ToolButton(c, Tool.UNDERLINE, Icons.Filled.FormatUnderlined, "Underline")
                ToolButton(c, Tool.STRIKEOUT, Icons.Filled.FormatStrikethrough, "Strike")
                ToolButton(c, Tool.SQUIGGLY, Icons.Filled.Gesture, "Squiggly")
                ToolButton(c, Tool.NOTE, Icons.AutoMirrored.Filled.StickyNote2, "Note")
                ToolButton(c, Tool.TEXT, Icons.Filled.TextFields, "Text")
                ToolButton(c, Tool.INK, Icons.Filled.Draw, "Draw")
                ToolButton(c, Tool.RECT, Icons.Filled.CropSquare, "Rectangle")
                ToolButton(c, Tool.OVAL, Icons.Filled.RadioButtonUnchecked, "Oval")
                ToolButton(c, Tool.LINE, Icons.Filled.HorizontalRule, "Line")
                ToolButton(c, Tool.ARROW, Icons.AutoMirrored.Filled.ArrowRightAlt, "Arrow")
                ToolButton(c, Tool.IMAGE, Icons.Filled.Image, "Image")
                ToolButton(c, Tool.ERASER, Icons.Filled.Delete, "Eraser")
            }
            ToolHint(c)
        }
    }
}

@Composable
private fun FillSignToolbar(c: ViewerController) {
    Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column {
            if (c.tool != Tool.NONE && c.tool != Tool.SIGNATURE && c.tool != Tool.INITIALS) StyleRow(c)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
                ToolButton(c, Tool.FILL_TEXT, Icons.Filled.TextFields, "Text")
                ToolButton(c, Tool.DATE, Icons.Filled.Today, "Date")
                ToolButton(c, Tool.CHECK, Icons.Filled.Check, "Check")
                ToolButton(c, Tool.CROSS, Icons.Filled.Close, "Cross")
                ToolButton(c, Tool.DOT, Icons.Filled.FiberManualRecord, "Dot")
                ToolButton(c, Tool.CIRCLE_MARK, Icons.Filled.RadioButtonUnchecked, "Circle")
                ToolButton(c, Tool.LINE_MARK, Icons.Filled.HorizontalRule, "Line")
                ToolButton(c, Tool.SIGNATURE, Icons.Filled.Draw, "Sign")
                ToolButton(c, Tool.INITIALS, Icons.Filled.EditNote, "Initials")
                if (c.extras?.hasForm == true) ActionButton(Icons.Filled.CheckBox, "Form") { c.sheet = Sheet.FORM_FIELDS }
            }
            ToolHint(c)
        }
    }
}

@Composable
private fun ToolHint(c: ViewerController) {
    val hint = when (c.tool.category) {
        Tool.Category.MARKUP -> "Drag across text. Two fingers to scroll or zoom."
        Tool.Category.DRAW -> "Draw with one finger. Two fingers to scroll or zoom."
        Tool.Category.SHAPE -> "Drag to draw the shape."
        Tool.Category.ERASE -> "Swipe over items you added to remove them."
        Tool.Category.PLACE -> when (c.tool) {
            Tool.SIGNATURE, Tool.INITIALS -> if (c.signatureFile(c.tool == Tool.INITIALS).exists()) "Tap where to place it · long-press the button below to redraw" else "Tap where you want to sign"
            else -> "Tap the page to place. Drag to move, use the corner to resize."
        }
        else -> null
    }
    if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp, end = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (c.tool == Tool.SIGNATURE || c.tool == Tool.INITIALS) {
        val initials = c.tool == Tool.INITIALS
        if (c.signatureFile(initials).exists()) {
            TextButton(onClick = {
                val (p, x, y) = c.view.viewportCenterOnPage()
                c.dialog = ViewerDialog.Signature(initials, p, x, y)
            }, modifier = Modifier.padding(start = 4.dp)) { Text(if (initials) "Redraw initials" else "Redraw signature") }
        }
    }
}

@Composable
private fun StyleRow(c: ViewerController) {
    val s = c.style
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Palette.forEach { col ->
                val sel = col == s.color
                Box(
                    Modifier.padding(4.dp).size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(Color(col))
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x55000000), CircleShape)
                        .clickable { c.updateStyle(s.copy(color = col)) }
                )
            }
        }
        val cat = c.tool.category
        if (cat == Tool.Category.DRAW || cat == Tool.Category.SHAPE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Width", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                Slider(value = s.width, onValueChange = { c.updateStyle(s.copy(width = it)) }, valueRange = 0.5f..12f, modifier = Modifier.weight(1f))
                Text("%.1f".format(s.width), fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
        if (cat == Tool.Category.DRAW) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                Slider(value = s.opacity, onValueChange = { c.updateStyle(s.copy(opacity = it)) }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(s.opacity * 100).toInt()}%", fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
        if (c.tool == Tool.TEXT || c.tool == Tool.FILL_TEXT || c.tool == Tool.DATE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                Slider(value = s.fontSize, onValueChange = { c.updateStyle(s.copy(fontSize = it)) }, valueRange = 6f..40f, modifier = Modifier.weight(1f))
                Text("${s.fontSize.toInt()} pt", fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

@Composable
private fun ItemBar(c: ViewerController) {
    val item = c.selectedItem ?: return
    var colors by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, tonalElevation = 3.dp) {
            Column {
                if (colors) Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp)) {
                    Palette.forEach { col ->
                        Box(Modifier.padding(3.dp).size(26.dp).clip(CircleShape).background(Color(col)).border(1.dp, Color(0x55000000), CircleShape)
                            .clickable { c.recolorSelected(col); colors = false })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val editable = item is TextItem || item is NoteItem || item is MarkupItem
                    if (editable) ActionButton(Icons.Filled.Edit, if (item is MarkupItem) "Note" else "Edit") {
                        val initial = when (item) { is TextItem -> item.text; is NoteItem -> item.text; is MarkupItem -> item.note; else -> "" }
                        c.dialog = ViewerDialog.TextInput(ViewerDialog.TextInput.Purpose.EDIT_ITEM, initial, item.page, 0f, 0f, item.id)
                    }
                    val colorable = !(item is StampItem && item.kind == StampKind.IMAGE)
                    if (colorable) ActionButton(Icons.Filled.BorderColor, "Color") { colors = !colors }
                    ActionButton(Icons.Filled.Delete, "Delete") { c.deleteSelected() }
                    ActionButton(Icons.Filled.Check, "Done") { c.view.selectItem(null) }
                }
                val desc = when (item) {
                    is InkItem -> "Drawing"
                    is MarkupItem -> item.text.take(40)
                    else -> ""
                }
                if (desc.isNotEmpty()) Text(desc, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun TtsBar(c: ViewerController) {
    val t = c.tts
    Surface(Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RecordVoiceOver, null, modifier = Modifier.padding(start = 4.dp))
                Text("Reading page ${t.page + 1}", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Medium)
                IconButton(onClick = { t.skip(-1) }) { Icon(Icons.Filled.SkipPrevious, "Previous page") }
                IconButton(onClick = { if (t.speaking) t.pause() else t.resume() }) {
                    Icon(if (t.speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
                }
                IconButton(onClick = { t.skip(1) }) { Icon(Icons.Filled.SkipNext, "Next page") }
                IconButton(onClick = { t.stop() }) { Icon(Icons.Filled.Stop, "Stop") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                    FilterChip(selected = t.rate == r, onClick = { t.changeRate(r) }, label = { Text("${r}x", fontSize = 11.sp) },
                        modifier = Modifier.padding(horizontal = 2.dp))
                }
            }
            t.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }
}

@Composable
fun SmallLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
fun RowSpacer() = Spacer(Modifier.width(8.dp))
