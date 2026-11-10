package dev.zeroinput.ime.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionSessionService
import android.service.voice.VoiceInteractionSession

class AiVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AiNativeSession(this)
}
