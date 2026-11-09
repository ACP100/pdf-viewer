package com.pdfviewer.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfviewer.app.data.Settings

/** Turns extracted page text into readable paragraphs. */
fun reflowParagraphs(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r", "").split('\n').map { it.trim() }
    val paras = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() { if (sb.isNotBlank()) paras += sb.toString().trim(); sb.clear() }
    for ((i, line) in lines.withIndex()) {
        if (line.isEmpty()) { flush(); continue }
        if (sb.isEmpty()) { sb.append(line); continue }
        val prev = sb.trimEnd()
        val endsSentence = prev.isNotEmpty() && prev.last() in ".!?:;”\"।。"
        val next = lines.getOrNull(i + 1)?.firstOrNull()
        val looksLikeHeading = line.length < 60 && (line.all { !it.isLetter() || it.isUpperCase() } || next?.isDigit() == true && line.length < 12)
        when {
            prev.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> { sb.setLength(sb.length - 1); sb.append(line) }
            endsSentence && line.firstOrNull()?.isUpperCase() == true && prev.length < 70 -> { flush(); sb.append(line) }
            looksLikeHeading -> { flush(); sb.append(line); flush() }
            else -> sb.append(' ').append(line)
        }
    }
    flush()
    return paras
}

@Composable
fun ReflowScreen(c: ViewerController) {
    var size by remember { mutableFloatStateOf(Settings.reflowFontSize) }
    var dark by remember { mutableIntStateOf(c.colorMode) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(c.page) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { if (it != c.page) c.view.goToPage(it) }
    }
    val bg = when (dark) { 1 -> Color(0xFF121212); 2 -> Color(0xFFF4ECD8); else -> Color.White }
    val fg = when (dark) { 1 -> Color(0xFFE0E0E0); 2 -> Color(0xFF3B2F20); else -> Color(0xFF1B1B1B) }
    Surface(Modifier.fillMaxSize(), color = bg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c.reflow = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = fg) }
                Text("Reading mode", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { size = (size - 2).coerceAtLeast(12f); Settings.reflowFontSize = size }) { Icon(Icons.Filled.TextDecrease, "Smaller", tint = fg) }
                IconButton(onClick = { size = (size + 2).coerceAtMost(40f); Settings.reflowFontSize = size }) { Icon(Icons.Filled.TextIncrease, "Larger", tint = fg) }
                IconButton(onClick = { dark = (dark + 1) % 3 }) { Icon(if (dark == 1) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Theme", tint = fg) }
                IconButton(onClick = { c.startReadAloud() }) { Icon(Icons.Filled.RecordVoiceOver, "Read aloud", tint = fg) }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(state = list, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().background(bg)) {
                    items(c.pageCount) { p ->
                        val text by produceState<String?>(null, p) { value = c.pageText(p) }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("Page ${p + 1}", color = fg.copy(alpha = 0.5f), fontSize = 12.sp)
                            val t = text
                            if (t == null) Text("…", color = fg)
                            else {
                                val paras = remember(t) { reflowParagraphs(t) }
                                if (paras.isEmpty()) Text("(No text on this page — it may be a scanned image.)", color = fg.copy(alpha = 0.6f), fontSize = (size * 0.8f).sp)
                                SelectionContainer {
                                    Column {
                                        paras.forEach { para ->
                                            Text(para, color = fg, fontSize = size.sp, lineHeight = (size * 1.5f).sp, fontFamily = FontFamily.Serif,
                                                modifier = Modifier.padding(vertical = 6.dp))
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = fg.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}
