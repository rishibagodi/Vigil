package com.rishi.vigil

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

// Whisper runs on Groq with the same key as the LLM
private const val GROQ_URL = "https://api.groq.com/openai/v1"

// Groq rate limits are per model, so if one is busy we try the other
private val FALLBACK_MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")

// Phrases that clearly point at what's on the screen ("summarise this page", "explain this", "on my screen")
private val SCREEN_WORDS = Regex(
    "\\b(this|these)\\s+(page|article|screen|message|messages|chat|post|email|mail|text|website|site|news|story|error|bill|invite|invitation)\\b" +
            "|\\b(summari[sz]e|explain|read|translate|describe|check)\\s+(this|it)\\b" +
            "|\\bwhat('?s| is| does| do)\\s+(this|these)\\b" +
            "|\\b(on|from)\\s+(my|the)\\s+screen\\b" +
            "|\\bscreen\\b"
)

// Tools that only run if YOUR words clearly ask for them (a bare or misheard name never triggers them)
private val GUARDED_TOOLS = setOf("call_contact", "send_sms", "open_app")

class AssistActivity : ComponentActivity() {
    private var status by mutableStateOf("Starting…")
    private var heard by mutableStateOf("")
    private var reply by mutableStateOf("")
    private var recording by mutableStateOf(false)
    private var level by mutableFloatStateOf(0f)       // mic loudness 0..1 for the UI bar
    private lateinit var tts: TextToSpeech
    private val history = JSONArray()                 // conversation for this session
    private var closeAfter = false                     // true when we handed off to another app
    private var followUp = false                       // true when the mic reopened by itself
    private var listenJob: Job? = null
    private var contactHint = ""                       // contact names → helps Whisper spell them
    private val toolsUsed = mutableListOf<String>()    // tools run for the current command
    private var lastResult = ""                        // last tool result (for app/call handoff)
    private val openedAt = System.currentTimeMillis()  // assist-structure screen text must be captured around this time

    // Whole-utterance phrases that mean "I'm done" (checked after removing punctuation)
    private val stopWords = Regex("^(stop|cancel|thanks|thank you|thats all|that is all|bye|goodbye|nothing|no|no thanks|ok bye)$")

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

        lifecycleScope.launch(Dispatchers.IO) { contactHint = contactNames() }
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
                            if (recording) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
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
        listenJob?.cancel()                                           // stop any recording in progress
        heard = ""
        if (!auto) reply = ""                                         // keep last reply visible during follow-up
        status = if (auto) "Anything else? (say \"stop\" to close)" else "Listening… speak now"

        listenJob = lifecycleScope.launch {
            // 1. Record until you stop talking
            val wav = runCatching { withContext(Dispatchers.IO) { record(if (auto) 5_000 else 8_000) } }
                .getOrElse {
                    if (it is CancellationException) throw it
                    status = "Mic error: ${it.message}. Tap to try again."
                    return@launch
                }
            if (wav == null) {                                        // nobody spoke
                if (followUp) finish() else status = "I didn't hear anything. Tap to try again."
                return@launch
            }

            // 2. Speech → text with Whisper
            status = "Transcribing…"
            val text = runCatching { withContext(Dispatchers.IO) { transcribe(wav) } }
                .getOrElse {
                    if (it is CancellationException) throw it
                    status = "Couldn't transcribe (${it.message}). Tap to try again."
                    return@launch
                }
            if (text.isBlank()) {                                     // Whisper says it was just noise
                if (followUp) finish() else status = "I didn't catch that. Tap to try again."
                return@launch
            }

            // 3. Same flow as before
            onHeard(text)
        }
    }

    private suspend fun onHeard(text: String) {
        val plain = text.lowercase().replace(Regex("[^a-z ]"), "").trim()
        if (stopWords.matches(plain)) { finish(); return }            // natural stop

        heard = text
        status = "Thinking…"
        reply = runCatching { ask(text) }.getOrElse {
            if (it is CancellationException) throw it
            "Error: ${it.message}"
        }
        Memory.log(this, text, toolsUsed.toList(), reply)             // save to command history
        if (closeAfter) { finish(); return }                          // get out of the way of the call/app
        status = "Vigil"
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "vigil")
    }

    // Records 16 kHz mono audio. Returns WAV bytes, or null if nobody really spoke within waitMs.
    @SuppressLint("MissingPermission")
    private suspend fun record(waitMs: Int): ByteArray? {
        val rate = 16_000
        val chunk = rate / 10                                         // 100 ms of samples
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 8))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(chunk)
        var prev: ByteArray? = null                                   // 100 ms pre-roll so the first word isn't clipped
        var noise = Double.MAX_VALUE
        var spoke = false
        var silentMs = 0
        var speechMs = 0                                              // how long you actually spoke
        var elapsed = 0
        recording = true
        try {
            rec.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()               // stop if the user tapped again / closed
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) continue

                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / n)                                // loudness of this 100 ms
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    .apply { asShortBuffer().put(buf, 0, n) }.array()
                elapsed += 100
                level = (rms / 3000).toFloat().coerceIn(0f, 1f)

                if (elapsed <= 300) { noise = minOf(noise, rms); prev = bytes; continue }   // learn background noise

                if (rms > maxOf(500.0, noise * 3)) {                  // speech
                    if (!spoke) prev?.let { pcm.write(it) }
                    spoke = true; silentMs = 0
                    speechMs += 100
                } else if (spoke) silentMs += 100                      // silence after speech
                if (spoke) pcm.write(bytes)
                prev = bytes

                if (spoke && silentMs >= 1200) break                  // finished speaking
                if (!spoke && elapsed >= waitMs) return null           // nobody spoke
                if (elapsed >= 15_000) break                           // hard limit
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            recording = false; level = 0f
        }
        // Short bursts of noise are not speech (stricter in follow-up mode, where phantom words came from)
        if (speechMs < (if (followUp) 600 else 400)) return null
        return wav(pcm.toByteArray(), rate)
    }

    // Raw PCM → WAV file bytes (44-byte header)
    private fun wav(pcm: ByteArray, rate: Int): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1.toShort())                                      // PCM
            putShort(1.toShort())                                      // mono
            putInt(rate); putInt(rate * 2)                             // sample rate, byte rate
            putShort(2.toShort()); putShort(16.toShort())              // block align, bits per sample
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()

    // Upload WAV to Groq Whisper, get text back ("" if Whisper thinks it was not real speech)
    private fun transcribe(wav: ByteArray): String {
        val boundary = "vigil${System.currentTimeMillis()}"
        val conn = URL("$GROQ_URL/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        // Spelling hints: your custom words first, then contact names (Whisper limit ~224 tokens)
        val words = Memory.words(this).take(300)
        val hint = "Voice command to an assistant called Vigil in Bengaluru. " +
                "Words: $words. Contact names: $contactHint"

        conn.outputStream.use { out ->
            fun field(name: String, value: String) = out.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("model", "whisper-large-v3-turbo")
            field("language", "en")
            field("temperature", "0")
            field("response_format", "verbose_json")                  // includes "is this speech?" scores
            field("prompt", hint)
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val ok = conn.responseCode in 200..299
        val res = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (conn.responseCode == 429) error("speech limit reached, wait a minute")
        if (!ok) error("HTTP ${conn.responseCode}: ${res.take(120)}")

        val json = JSONObject(res)
        // Discard if Whisper itself isn't confident this was speech (stops phantom words like "Samaraz Dispatch")
        val segs = json.optJSONArray("segments")
        if (segs != null && segs.length() > 0) {
            val doubtful = (0 until segs.length()).all {
                val s = segs.getJSONObject(it)
                s.optDouble("no_speech_prob", 0.0) > 0.5 || s.optDouble("avg_logprob", 0.0) < -1.0
            }
            if (doubtful) return ""
        }
        return json.optString("text").trim()
    }

    // Contact names (starred first) as spelling hints for Whisper
    private fun contactNames(): String {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val names = linkedSetOf<String>()
        contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME), null, null, "${Phone.STARRED} DESC")
            ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names += it } }
        return names.joinToString(", ").take(350)                      // leaves room for custom words
    }

    // Guarded tools only run if YOUR words clearly asked for them
    private fun explicitlyAsked(tool: String, said: String): Boolean {
        val s = said.lowercase()
        return when (tool) {
            "call_contact" -> Regex("\\b(call|ring|phone|dial)\\b").containsMatchIn(s)
            "send_sms" -> Regex("\\b(text|sms|message|send)\\b").containsMatchIn(s)
            "open_app" -> Regex("\\b(open|launch|start|show|go to|switch to)\\b").containsMatchIn(s)
            else -> true
        }
    }

    // Text of the screen behind Vigil:
    // 1st choice: Accessibility service (reliable, works in Chrome and most apps)
    // 2nd choice: what Android's assistant API sent when Vigil opened
    private fun screenText(): String {
        val a11y = VigilAccessibilityService.instance
        if (a11y != null) {
            val (pkg, text) = runCatching { a11y.screenText() }.getOrNull()
                ?: return "(No screen text available: couldn't find the app behind Vigil.)"
            return if (text.isNotBlank()) "App: $pkg\n$text"
            else "(No screen text available: that screen has no readable text, e.g. an image or video.)"
        }
        val fresh = ScreenContext.capturedAt >= openedAt - 5_000
        return when {
            fresh && ScreenContext.text != null -> ScreenContext.text!!
            else -> "(No screen text available: turn on Vigil in Settings → Accessibility → " +
                    "Downloaded apps → Vigil. The Vigil app's step 6 opens that page.)"
        }
    }

    // Conversation loop: LLM may call tools, we run them, then it gives the final spoken reply
    private suspend fun ask(text: String): String {
        toolsUsed.clear()

        // "Summarise this page" etc. → attach the screen text ourselves, so the LLM can't skip it
        val content = if (SCREEN_WORDS.containsMatchIn(text.lowercase())) {
            toolsUsed += "screen"
            val screen = withContext(Dispatchers.IO) { screenText() }
            "$text\n\n[Text currently on the user's screen]\n$screen"
        } else text
        history.put(JSONObject().put("role", "user").put("content", content))

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
                val result = when {
                    name in GUARDED_TOOLS && !explicitlyAsked(name, text) ->              // safety guard
                        "Not done: the user did not clearly ask for this. Ask what they want."
                    name == "read_screen" ->
                        withContext(Dispatchers.IO) { screenText() }
                    name in Tools.network ->
                        withContext(Dispatchers.IO) { Tools.run(ctx, name, args) }        // internet tools off main thread
                    else -> Tools.run(ctx, name, args)
                }

                toolsUsed += name
                lastResult = result

                // Opened an app or started a call → Vigil should close
                if (result.startsWith("Opened") || result.startsWith("Calling")) closeAfter = true

                history.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.getString("id"))
                    .put("content", result))
            }

            if (closeAfter) return lastResult                          // e.g. "Calling Arjun" in history
        }
        return "Done."
    }

    // One request to the LLM with system prompt + saved memory + recent history + tools
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
                    "When a user message includes a '[Text currently on the user's screen]' section, 'this' and 'this page' " +
                    "mean that text: answer from it directly and never ask which page. Ignore menus, buttons and ads in it. " +
                    "If it says no screen text is available, tell the user the exact reason given in brackets. " +
                    "If the user's words are unclear, random or just a name, ask what they want instead of acting. " +
                    "For weather use the weather tool. For news, scores, prices or anything recent, use web_search " +
                    "and summarise the answer; never read out URLs. " +
                    "When the user says 'remember ...', use the remember tool and convert relative dates like " +
                    "'tomorrow' or 'the 5th' into exact dates. Answer questions about saved facts from long-term memory below. " +
                    "Only bring up saved facts when they are relevant. " +
                    "Reply in 1-2 short spoken sentences. No markdown, no emojis. Current date and time: $now.\n\n" +
                    "Long-term memory (facts the user asked you to remember):\n$memoryText"))

        // Only send the recent part of this session (saves tokens). Start at a user message
        // so an assistant tool request is never separated from its tool results.
        val start = (0 until history.length()).firstOrNull { i ->
            i >= history.length() - 12 && history.getJSONObject(i).getString("role") == "user"
        } ?: 0
        for (i in start until history.length()) messages.put(history.get(i))

        // Main model first, then the fallback if it is rate-limited (429)
        val models = (listOf(BuildConfig.LLM_MODEL) + FALLBACK_MODELS).distinct()
        for (model in models) {
            val body = JSONObject()
                .put("model", model)
                .put("reasoning_effort", "low")
                .put("messages", messages)
                .put("tools", Tools.schema)

            val conn = URL("${BuildConfig.LLM_BASE_URL}/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode == 429) continue                     // this model is busy → try the next

            val ok = conn.responseCode in 200..299
            val res = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            if (!ok) error("HTTP ${conn.responseCode}: ${res.take(150)}")
            return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
        error("Groq is busy right now. Wait a minute and try again.")
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}