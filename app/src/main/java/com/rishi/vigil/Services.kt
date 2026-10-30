package com.rishi.vigil

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

class VigilService : VoiceInteractionService()

class VigilSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?) = VigilSession(this)
}

class VigilSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        startAssistantActivity(Intent(context, AssistActivity::class.java))
    }
}

class VigilRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, cb: Callback?) {}
    override fun onCancel(cb: Callback?) {}
    override fun onStopListening(cb: Callback?) {}
}