package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.R
import dev.zeroinput.security.message.MessageCredentialMode

// Baichuan2 + Discop + Basic (top-k 256) is deferred: the estimated size saving
// over Daily is small for a 7B model, and exact cross-device recovery is unverified.
// See docs/natural-wording-steganography.md#baichuan2-basic.
internal enum class StegoWordingProfile(val label: Int) {
    VERSE(R.string.stego_wording),
    SEVEN_VERSE(R.string.stego_wording_seven),
    PROSE_WINDOW(R.string.stego_wording_window),
}

internal data class StegoWordingFile(val profile: StegoWordingProfile, val decrypt: Boolean, val mode: MessageCredentialMode)

internal data class StegoWordingCarrier(val text: String, val originalBytes: Int, val utf8Bytes: Int) {
    val utilizationPercent get() = originalBytes * 100.0 / utf8Bytes
}

internal class StegoWordingCapacityFailure : Exception("Natural wording capacity exceeded")
