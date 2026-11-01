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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AssistActivity : ComponentActivity() {
    private var status by mutableStateOf("Starting…")
    private var heard by mutableStateOf("")
    private var reply by mutableStateOf("")
    private lateinit var stt: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val history = JSONArray()                // conversation for this session
    private var closeAfter = false                    // true when we handed off to another app

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
                    reply = runCatching { ask(text) }.getOrElse { "Error: ${it.message}" }
                    if (closeAfter) { finish(); return@launch }      // get out of the way of the call/app
                    status = "Vigil"
                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "vigil")
                }
            }

            override fun onError(error: Int) {
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap to try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing. Open Vigil app → Allow permissions."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download English (India) in Settings → Speech → On-device recognition."
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
        stt.cancel()
        status = "Starting…"; heard = ""; reply = ""
        stt.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    // Conversation loop: LLM may call tools, we run them, then it gives the final spoken reply
    private suspend fun ask(text: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))

        repeat(3) {                                                    // max 3 tool rounds
            val msg = withContext(Dispatchers.IO) { chat() }
            val calls = msg.optJSONArray("tool_calls")

            if (calls == null || calls.length() == 0) {                // final answer
                val answer = msg.optString("content")
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                history.put(JSONObject().put("role", "assistant").put("content", answer))
                return answer
            }

            history.put(JSONObject()                                   // record the tool request
                .put("role", "assistant")
                .put("content", msg.optString("content", ""))
                .put("tool_calls", calls))

            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val result = Tools.run(this, fn.getString("name"),
                    JSONObject(fn.optString("arguments").ifBlank { "{}" }))

                // Opened an app or started a call → Vigil should close
                if (result.startsWith("Opened") || result.startsWith("Calling")) closeAfter = true

                history.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.getString("id"))
                    .put("content", result))
            }

            if (closeAfter) return "Done."
        }
        return "Done."
    }

    // One request to the LLM with system prompt + history + tools
    private fun chat(): JSONObject {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy, h:mm a"))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "You are Vigil, a voice assistant on the user's Android phone. " +
                    "Use the tools to act on the phone when asked. After a tool runs, confirm briefly. " +
                    "Reply in 1-2 short spoken sentences. No markdown, no emojis. Current date and time: $now."))
        for (i in 0 until history.length()) messages.put(history.get(i))

        val body = JSONObject()
            .put("model", BuildConfig.LLM_MODEL)
            .put("reasoning_effort", "low")
            .put("messages", messages)
            .put("tools", Tools.schema)

        val conn = URL("${BuildConfig.LLM_BASE_URL}/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val ok = conn.responseCode in 200..299
        val res = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (!ok) error("HTTP ${conn.responseCode}: ${res.take(150)}")

        return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }

    override fun onDestroy() {
        stt.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}