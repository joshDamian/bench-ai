package ai.bench.app.ui.session

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Dictation into the message box as you speak: words appear while you talk, rather than
 * after a system dialog closes. [onText] gets everything heard so far in this take.
 */
class LiveDictation(private val context: Context, private val onText: (String, Boolean) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    /** 0..1, how loud it is right now, for the mic's pulse. */
    var level by mutableStateOf(0f)
        private set
    private var recognizer: SpeechRecognizer? = null

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { listening = false; level = 0f }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, true) }
                listening = false; level = 0f
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { onText(it, false) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
        listening = true
    }

    fun stop() { recognizer?.stopListening(); listening = false; level = 0f }

    fun release() { recognizer?.destroy(); recognizer = null }
}

@Composable
fun rememberLiveDictation(onText: (String, Boolean) -> Unit): LiveDictation {
    val context = LocalContext.current
    val d = remember { LiveDictation(context, onText) }
    DisposableEffect(d) { onDispose { d.release() } }
    return d
}

/** Drafts kept per session, so leaving a half-written message doesn't lose it. */
object Drafts {
    private val prefs get() = ai.bench.app.BenchApp.instance.getSharedPreferences("drafts", 0)
    fun get(session: String): String = prefs.getString(session, "") ?: ""
    fun put(session: String, text: String) {
        prefs.edit().apply { if (text.isBlank()) remove(session) else putString(session, text) }.apply()
    }
}
