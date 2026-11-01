package com.rishi.vigil

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.text.InputType

// Screen text captured when Vigil opens. Held only in memory, replaced on the next opening,
// and sent to the LLM only when you ask about the screen.
object ScreenContext {
    const val NOTHING_SENT = "Android didn't send any screen data for this opening. Close Vigil, wait a moment, " +
            "and open it again from the app you want to ask about."

    @Volatile var text: String? = null
    @Volatile var app: String = ""
    @Volatile var note: String = NOTHING_SENT        // why there's no text (shown to the user)
    @Volatile var capturedAt: Long = 0L              // when the screen was last captured

    fun reset() { text = null; app = ""; note = NOTHING_SENT; capturedAt = 0L }
}

class VigilService : VoiceInteractionService()

class VigilSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?) = VigilSession(this)
}

class VigilSession(ctx: Context) : VoiceInteractionSession(ctx) {

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)            // no invisible full-screen session window covering the app
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)

        // Grab the screen NOW, before Vigil's own window covers the app you were using
        ScreenContext.reset()
        VigilAccessibilityService.instance?.let { a11y ->
            runCatching { a11y.capture() }.getOrNull()?.let { (pkg, text) ->
                ScreenContext.app = pkg
                ScreenContext.text = text.ifBlank { null }
                ScreenContext.note = if (text.isBlank()) "That screen has no readable text." else ""
                ScreenContext.capturedAt = System.currentTimeMillis()
            }
        }

        startAssistantActivity(Intent(context, AssistActivity::class.java))
    }

    // Android's own assistant screen data: used only if the Accessibility capture got nothing
    override fun onHandleAssist(state: AssistState) {
        if (ScreenContext.text != null) return
        val structure = state.assistStructure ?: return
        val lines = linkedSetOf<String>()
        for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, lines)
        val text = lines.joinToString("\n").take(4000).ifBlank { null } ?: return
        ScreenContext.app = structure.activityComponent?.packageName.orEmpty()
        ScreenContext.text = text
        ScreenContext.capturedAt = System.currentTimeMillis()
    }

    // Walk the view tree and collect visible text, skipping password fields
    private fun collect(node: AssistStructure.ViewNode, out: MutableSet<String>) {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (!isPassword) {
            (node.text ?: node.contentDescription)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
    }
}

// Required so Android accepts Vigil as an assistant; real speech recognition uses Whisper
class VigilRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, cb: Callback?) {}
    override fun onCancel(cb: Callback?) {}
    override fun onStopListening(cb: Callback?) {}
}