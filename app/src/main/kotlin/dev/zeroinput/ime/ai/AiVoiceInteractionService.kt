package dev.zeroinput.ime.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** Selected by the user through Android; invocation never grants screen or microphone access. */
class AiVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); disableContext() }
    override fun onPrepareToShowSession(args: Bundle, flags: Int) { disableContext() }
    override fun onLaunchVoiceAssistFromKeyguard() = Unit
    private fun disableContext() { setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT) }
}
