package dev.zeroinput.ime.ai

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Required metadata target on older Android. Text assistant deliberately has no speech engine. */
class AiDisabledRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onStopListening(listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) = Unit
}
