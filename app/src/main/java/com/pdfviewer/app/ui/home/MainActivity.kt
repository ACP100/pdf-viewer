package com.pdfviewer.app.ui.home

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pdfviewer.app.BuildConfig
import com.pdfviewer.app.data.Files
import com.pdfviewer.app.data.Library
import com.pdfviewer.app.data.RecentDoc
import com.pdfviewer.app.data.Settings
import com.pdfviewer.app.ui.theme.AppTheme
import com.pdfviewer.app.ui.viewer.ViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val snackbar = SnackbarHostState()
    private lateinit var tools: ToolRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tools = ToolRunner(this, snackbar)
        setContent {
            var theme by remember { mutableIntStateOf(Settings.appTheme) }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            AppTheme(dark = dark) {
                HomeScreen(this, tools, snackbar, theme) { theme = it; Settings.appTheme = it }
                ToolDialogs(tools)
            }
        }
    }

    fun openDocument(uri: Uri) {
        // Same app, same uid: no URI grant needed (and granting a MediaStore URI we read via
        // storage permission would throw).
        startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
    }

    fun shareDocument(uri: Uri, name: String) {
        val direct = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(direct, "Share"))
        } catch (e: Exception) {
            // Not grantable: share a private copy instead.
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    runCatching { Files.copyFromUri(this@MainActivity, uri, java.io.File(Files.tempDir(this@MainActivity), "share_src.pdf")) }.getOrNull()
                }
                if (f != null) runCatching { Files.share(this@MainActivity, f, name) }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Recent", Icons.Filled.History), FILES("Files", Icons.Filled.Folder),
    STARRED("Starred", Icons.Filled.Star), TOOLS("Tools", Icons.Filled.HomeRepairService)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(activity: MainActivity, tools: ToolRunner, snackbar: SnackbarHostState, theme: Int, setTheme: (Int) -> Unit) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val recents by Library.recents.collectAsState()
    val ctx = LocalContext.current

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            activity.openDocument(uri)
        }
    }
    val openPdf = { runCatching { openLauncher.launch(arrayOf("application/pdf")) }; Unit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(query, { query = it }, placeholder = { Text("Search files") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp)); Text("PDF Viewer", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = { if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                actions = {
                    if (!searching && tab != Tab.TOOLS) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Appearance") }, onClick = { menu = false; dialog = "theme" })
                            DropdownMenuItem(text = { Text("Your name (for comments)") }, onClick = { menu = false; dialog = "author" })
                            DropdownMenuItem(text = { Text("Clear recent files") }, onClick = { menu = false; Library.clearRecents() })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; dialog = "about" })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.TOOLS) ExtendedFloatingActionButton(onClick = openPdf, icon = { Icon(Icons.Filled.FileOpen, null) }, text = { Text("Open PDF") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val q = query.trim()
            when (tab) {
                Tab.HOME -> RecentList(activity, recents.filter { q.isEmpty() || it.name.contains(q, true) }, empty = {
                    Welcome(openPdf) { tab = Tab.TOOLS }
                })
                Tab.STARRED -> RecentList(activity, recents.filter { it.starred && (q.isEmpty() || it.name.contains(q, true)) }, empty = {
                    EmptyState(Icons.Filled.StarBorder, "No starred files", "Tap the star on a recent file to keep it here.")
                })
                Tab.FILES -> FilesTab(activity, q, openPdf)
                Tab.TOOLS -> ToolsGrid(tools)
            }
            tools.progress?.let { label ->
                Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, tonalElevation = 6.dp) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(16.dp)); Text(label)
                    }
                }
            }
        }
    }

    when (dialog) {
        "theme" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Appearance") },
            text = {
                Column {
                    listOf("System default", "Light", "Dark").forEachIndexed { i, l ->
                        Row(Modifier.fillMaxWidth().clickable { setTheme(i) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(theme == i, { setTheme(i) }); Text(l)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
        "author" -> {
            var name by remember { mutableStateOf(Settings.author) }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Your name") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Shown as the author of comments") }) },
                confirmButton = { TextButton(onClick = { Settings.author = name; dialog = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("PDF Viewer ${BuildConfig.VERSION_NAME}") },
            text = {
                Text("A fast PDF reader and editor: view, search, annotate, fill & sign, organize pages, merge, split, compress and protect PDFs.\n\n" +
                    "Rendering by PDFium (BSD) via PdfiumAndroid (Apache 2.0). Editing by PdfBox-Android (Apache 2.0).", fontSize = 14.sp)
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
    }
}

@Composable
private fun Welcome(openPdf: () -> Unit, showTools: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Open a PDF to get started", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Read, search, highlight, comment, fill forms and sign. Files you open appear here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = openPdf) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Open PDF") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = showTools) { Text("Explore tools") }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocThumb(uri: String) {
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(Library.thumbFile(uri).absolutePath) }.getOrNull() }
    }
    Box(Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).border(1.dp, Color(0x22000000), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.PictureAsPdf, null, tint = Color(0xFFC62828))
    }
}

@Composable
private fun RecentList(activity: MainActivity, docs: List<RecentDoc>, empty: @Composable () -> Unit) {
    if (docs.isEmpty()) { empty(); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(docs, key = { it.uri }) { d ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { activity.openDocument(Uri.parse(d.uri)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DocThumb(d.uri)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    val details = listOfNotNull(
                        if (d.pageCount > 0) "Page ${d.lastPage + 1} of ${d.pageCount}" else null,
                        Files.humanSize(d.size).ifEmpty { null },
                        Files.humanDate(d.lastOpened),
                    ).joinToString(" · ")
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.pageCount > 1) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { (d.lastPage + 1f) / d.pageCount }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    }
                }
                IconButton(onClick = { Library.setStarred(d.uri, !d.starred) }) {
                    Icon(if (d.starred) Icons.Filled.Star else Icons.Filled.StarBorder, "Star", tint = if (d.starred) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; activity.openDocument(Uri.parse(d.uri)) })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; activity.shareDocument(Uri.parse(d.uri), d.name) })
                        DropdownMenuItem(text = { Text(if (d.starred) "Remove star" else "Star") }, onClick = { menu = false; Library.setStarred(d.uri, !d.starred) })
                        DropdownMenuItem(text = { Text("Remove from recent") }, onClick = { menu = false; Library.remove(d.uri) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesTab(activity: MainActivity, query: String, browse: () -> Unit) {
    var access by remember { mutableStateOf(DeviceFiles.hasAccess(activity)) }
    var files by remember { mutableStateOf<List<DeviceFile>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { access = DeviceFiles.hasAccess(activity) }
    }
    LaunchedEffect(access, refresh) {
        if (access) files = withContext(Dispatchers.IO) { DeviceFiles.scan(activity) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }
    if (!access) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("See all PDFs on this device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Allow file access to list every PDF in Downloads, Documents and other folders. You can also browse without it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName))
                    runCatching { activity.startActivity(i) }.onFailure { runCatching { activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                } else permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) { Text("Allow access") }
            TextButton(onClick = browse) { Text("Browse with file picker") }
        }
        return
    }
    val list = files
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val shown = list.filter { query.isEmpty() || it.name.contains(query, true) }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} PDF files", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { refresh++ } }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (shown.isEmpty()) EmptyState(Icons.Filled.PictureAsPdf, "No PDFs found", "Try the file picker to open from cloud storage.")
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(shown, key = { it.uri.toString() }) { f ->
                Row(Modifier.fillMaxWidth().clickable { activity.openDocument(f.uri) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PictureAsPdf, null, tint = Color(0xFFC62828), modifier = Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(listOf(f.folder, Files.humanSize(f.size), Files.humanDate(f.modified)).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun iconFor(t: ToolId): ImageVector = when (t) {
    ToolId.ORGANIZE -> Icons.Filled.ViewAgenda
    ToolId.FILL_SIGN -> Icons.Filled.Draw
    ToolId.MERGE -> Icons.AutoMirrored.Filled.MergeType
    ToolId.SPLIT -> Icons.AutoMirrored.Filled.CallSplit
    ToolId.COMPRESS -> Icons.Filled.Compress
    ToolId.PROTECT -> Icons.Filled.Lock
    ToolId.UNLOCK -> Icons.Filled.LockOpen
    ToolId.IMAGES -> Icons.Filled.Collections
    ToolId.SCAN -> Icons.Filled.DocumentScanner
    ToolId.EXPORT_IMAGES -> Icons.Filled.Image
    ToolId.EXPORT_TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    ToolId.WATERMARK -> Icons.AutoMirrored.Filled.BrandingWatermark
    ToolId.PAGE_NUMBERS -> Icons.Filled.FormatListNumbered
}

@Composable
private fun ToolsGrid(tools: ToolRunner) {
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp)) {
        items(ToolId.entries) { t ->
            Card(Modifier.padding(6.dp).fillMaxWidth().clickable { tools.run(t) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                Column(Modifier.padding(16.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(iconFor(t), null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(t.title, fontWeight = FontWeight.SemiBold)
                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

