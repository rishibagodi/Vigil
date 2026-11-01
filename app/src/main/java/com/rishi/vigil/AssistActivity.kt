package com.rishi.vigil

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var followUp = false                      // true when the mic reopened by itself

    // Whole-utterance phrases that mean "I'm done"
    private val stopWords = Regex("^(stop|cancel|thanks|thank you|that's all|that is all|bye|goodbye|nothing|no|no thanks|ok bye)[.!]?$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) {}

        // When Vigil finishes speaking, reopen the mic for a follow-up
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                runOnUiThread { if (!isFinishing) listen(auto = true) }
            }
            override fun onStart(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })

        // Vigil is the default assistant, so the "default" recognizer is our empty stub.
        // Use the phone's on-device recognizer instead.
        stt = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)

        stt.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                status = if (followUp) "Anything else? (say \"stop\" to close)" else "Listening… speak now"
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { heard = it }
            }

            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (stopWords.matches(text.lowercase().trim())) { finish(); return }   // natural stop

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
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (silent && followUp) { finish(); return }          // no follow-up → close

                status = when {
                    silent -> "I didn't hear anything. Tap to try again."
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing. Open Vigil app → Allow permissions."
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download English (India) in Settings → Speech → On-device recognition."
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

    // auto = true when the mic reopens by itself after Vigil spoke
    private fun listen(auto: Boolean = false) {
        followUp = auto
        tts.stop()
        stt.cancel()
        status = "Starting…"; heard = ""
        if (!auto) reply = ""                           // keep last reply visible during follow-up
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

        repeat(5) {                                                    // max 5 tool rounds (multi-step)
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
                val name = fn.getString("name")
                val args = JSONObject(fn.optString("arguments").ifBlank { "{}" })
                val ctx = this@AssistActivity
                val result = if (name in Tools.network)
                    withContext(Dispatchers.IO) { Tools.run(ctx, name, args) }   // internet tools off main thread
                else Tools.run(ctx, name, args)

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

    // One request to the LLM with system prompt + saved memory + history + tools
    private fun chat(): JSONObject {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy, h:mm a"))
        val facts = Memory.all(this)
        val memoryText = if (facts.isEmpty()) "Nothing saved yet."
        else facts.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "You are Vigil, a voice assistant on the user's Android phone. " +
                    "Use the tools to act on the phone when asked. If the user asks for several things, " +
                    "call every tool needed, then confirm all of them in one short reply. " +
                    "Use earlier messages in this conversation to understand follow-ups like 'make it 7' or 'turn it off'. " +
                    "For weather use the weather tool. For news, scores, prices or anything recent, use web_search " +
                    "and summarise the answer; never read out URLs. " +
                    "When the user says 'remember ...', use the remember tool and convert relative dates like " +
                    "'tomorrow' or 'the 5th' into exact dates. Answer questions about saved facts from long-term memory below. " +
                    "Only bring up saved facts when they are relevant. " +
                    "Reply in 1-2 short spoken sentences. No markdown, no emojis. Current date and time: $now.\n\n" +
                    "Long-term memory (facts the user asked you to remember):\n$memoryText"))
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