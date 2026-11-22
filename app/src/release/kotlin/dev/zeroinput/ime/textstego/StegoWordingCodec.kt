package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageFailure
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageRequirements
import dev.zeroinput.security.message.MessageStage

@Suppress("UNUSED_PARAMETER")
internal object StegoWordingCodec {
    const val available = false

    fun seal(assets: AssetManager, selected: StegoWordingProfile, secret: CharArray, key: MessageCredential,
             stages: List<MessageStage>, kdf: MessageKdf, randomWidths: Boolean,
             current: () -> Boolean): StegoWordingCarrier = throw MessageFailure()

    fun open(assets: AssetManager, selected: StegoWordingProfile, carrier: String, key: MessageCredential,
             current: () -> Boolean, approve: (MessageRequirements) -> Boolean): CharArray? = throw MessageFailure()

    fun inspect(assets: AssetManager, selected: StegoWordingProfile, carrier: String,
                current: () -> Boolean): MessageRequirements = throw MessageFailure()
}
