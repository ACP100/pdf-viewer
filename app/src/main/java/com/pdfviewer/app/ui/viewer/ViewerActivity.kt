package com.pdfviewer.app.ui.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pdfviewer.app.data.Images
import com.pdfviewer.app.ui.organize.OrganizeActivity
import com.pdfviewer.app.ui.theme.AppTheme
import com.pdfviewer.app.view.PdfView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerActivity : ComponentActivity() {
    companion object {
        /** Optional: "organize", "fillsign" or "annotate" to jump straight into a tool. */
        const val EXTRA_ACTION = "action"
    }

    private lateinit var c: ViewerController

    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { c.onSaveAsTarget(it) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = c.pendingImageFor
        c.pendingImageFor = null
        if (uri == null || target == null) return@registerForActivityResult
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { Images.importStamp(this@ViewerActivity, uri) }.getOrNull() }
            if (f == null) c.msg("Couldn't read that image") else c.placeImage(target.first, target.second, target.third, f.absolutePath, 180f)
        }
    }

    private val organizeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringExtra(OrganizeActivity.EXTRA_OUT)?.let { c.onOrganized(File(it)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val view = PdfView(this)
        c = ViewerController(this, view)
        c.onFinish = { finish() }
        c.requestSaveAs = { name -> runCatching { saveAsLauncher.launch(name) }.onFailure { c.msg("No file picker available") } }
        c.requestPickImage = { runCatching { pickImage.launch("image/*") } }
        c.requestOrganize = { file, pw ->
            organizeLauncher.launch(Intent(this, OrganizeActivity::class.java)
                .putExtra(OrganizeActivity.EXTRA_FILE, file.absolutePath)
                .putExtra(OrganizeActivity.EXTRA_PASSWORD, pw)
                .putExtra(OrganizeActivity.EXTRA_NAME, c.name))
        }

        val uri = documentUri(intent)
        if (uri == null) { finish(); return }
        c.pendingAction = intent.getStringExtra(EXTRA_ACTION)
        c.open(uri)

        setContent {
            AppTheme {
                LaunchedEffect(c.keepScreenOn) {
                    if (c.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val immersive = !c.chrome && c.mode == ViewerMode.VIEW && !c.searchOpen && c.selection == null
                LaunchedEffect(immersive) {
                    val ctl = WindowCompat.getInsetsController(window, window.decorView)
                    ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (immersive) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
                }
                ViewerScreen(c)
            }
        }
    }

    private fun documentUri(i: Intent?): Uri? {
        if (i == null) return null
        i.data?.let { return it }
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
            return u
        }
        return null
    }

    override fun onPause() {
        super.onPause()
        if (::c.isInitialized) c.persistPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::c.isInitialized) c.release()
    }
}
