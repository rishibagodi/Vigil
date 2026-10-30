package com.rishi.vigil

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AssistActivity : ComponentActivity() {
    private var status by mutableStateOf("Starting…")
    private var heard by mutableStateOf("")
    private var reply by mutableStateOf("")
    private lateinit var stt: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) {}

        // Vigil is the default assistant, so the "default" recognizer is our empty stub.
        // Use the phone's on-device recognizer instead.
        stt = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)

        stt.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status = "Listening… speak now" }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { heard = it }
            }

            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                heard = text
                status = "Thinking…"
                lifecycleScope.launch {
                    reply = runCatching { withContext(Dispatchers.IO) { askLlm(text) } }
                        .getOrElse { "Error: ${it.message}" }
                    status = "Vigil"
                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "vigil")
                }
            }

            override fun onError(error: Int) {
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap to try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing. Open Vigil app → Allow microphone."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download English in Settings → Speech → On-device recognition."
                    else -> "Error $error. Tap to try again."
                }
            }

            override fun onEndOfSpeech() { status = "Processing…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        listen()

        setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { finish() },                       // tap outside → close
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        Modifier.fillMaxWidth().clickable { listen() }, // tap sheet → listen again
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        tonalElevation = 6.dp
                    ) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(status, style = MaterialTheme.typography.titleLarge)
                            if (heard.isNotEmpty()) Text("You: $heard", style = MaterialTheme.typography.bodyLarge)
                            if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }

    private fun listen() {
        tts.stop()
        stt.cancel()                                                    // reset if already listening
        status = "Starting…"; heard = ""; reply = ""
        stt.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    private fun askLlm(text: String): String {
        val body = JSONObject()
            .put("model", BuildConfig.LLM_MODEL)
            .put("reasoning_effort", "low")                             // faster replies
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system")
                    .put("content", "You are Vigil, a helpful voice assistant. Reply in 1-2 short spoken sentences. No markdown, no emojis."))
                .put(JSONObject().put("role", "user").put("content", text)))

        val conn = URL("${BuildConfig.LLM_BASE_URL}/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val ok = conn.responseCode in 200..299
        val res = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (!ok) error("HTTP ${conn.responseCode}: ${res.take(150)}")

        return JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
    }

    override fun onDestroy() {
        stt.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}