package com.pdfviewer.app.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pdfviewer.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Read-aloud: speaks page text sentence by sentence and follows along page by page. */
class TtsController(context: Context, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initialised = false

    var active by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var page by mutableIntStateOf(0)
        private set
    var rate by mutableFloatStateOf(Settings.ttsRate)
        private set
    var error by mutableStateOf<String?>(null)

    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var pageCount = 0
    private var provider: (suspend (Int) -> String)? = null
    private var singleText = false
    var onPageChange: ((Int) -> Unit)? = null
    private var pendingStart: (() -> Unit)? = null
    private val appContext = context.applicationContext

    private fun ensure(then: () -> Unit) {
        if (initialised) { then(); return }
        pendingStart = then
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    initialised = true
                    val loc = Locale.getDefault()
                    runCatching { tts?.language = loc }
                    tts?.setSpeechRate(rate)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post { onChunkDone(utteranceId) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { main.post { onChunkDone(utteranceId) } }
                    })
                    pendingStart?.invoke(); pendingStart = null
                } else {
                    error = "Text-to-speech is not available on this device"
                    active = false
                }
            }
        }
    }

    fun readPages(from: Int, count: Int, textOf: suspend (Int) -> String) {
        provider = textOf; pageCount = count; singleText = false
        active = true
        ensure { loadPage(from) }
    }

    fun readText(text: String) {
        singleText = true; provider = null
        active = true
        ensure {
            chunks = chunk(text); chunkIndex = 0
            speakCurrent()
        }
    }

    private fun loadPage(p: Int) {
        val prov = provider ?: return
        if (p >= pageCount) { stop(); return }
        page = p
        onPageChange?.invoke(p)
        scope.launch {
            val text = prov(p)
            chunks = chunk(text); chunkIndex = 0
            if (!active) return@launch
            if (chunks.isEmpty()) loadPage(p + 1) else speakCurrent()
        }
    }

    private fun speakCurrent() {
        val t = tts ?: return
        val c = chunks.getOrNull(chunkIndex) ?: run { onChunkDone(null); return }
        speaking = true
        t.speak(c, TextToSpeech.QUEUE_FLUSH, null, "c$chunkIndex")
    }

    private fun onChunkDone(id: String?) {
        if (!active || !speaking) return
        if (id != null && id != "c$chunkIndex") return
        chunkIndex++
        if (chunkIndex < chunks.size) speakCurrent()
        else if (!singleText) loadPage(page + 1)
        else stop()
    }

    fun pause() {
        speaking = false
        tts?.stop()
    }

    fun resume() {
        if (!active) return
        speakCurrent()
    }

    fun skip(delta: Int) {
        if (singleText) return
        tts?.stop(); speaking = false
        loadPage((page + delta).coerceIn(0, pageCount - 1))
    }

    fun changeRate(r: Float) {
        rate = r; Settings.ttsRate = r
        tts?.setSpeechRate(r)
        if (speaking) { tts?.stop(); speakCurrent() }
    }

    fun stop() {
        active = false; speaking = false
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown(); tts = null; initialised = false
    }

    companion object {
        fun clean(text: String): String = text
            .replace(Regex("-\\n(?=\\p{Ll})"), "")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun chunk(text: String): List<String> {
            val clean = clean(text)
            if (clean.isEmpty()) return emptyList()
            val sentences = clean.split(Regex("(?<=[.!?।。])\\s+"))
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (s in sentences) {
                if (sb.length + s.length > 600 && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                if (s.length > 1500) {
                    s.chunked(1500).forEach { out += it }
                } else {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(s)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}
