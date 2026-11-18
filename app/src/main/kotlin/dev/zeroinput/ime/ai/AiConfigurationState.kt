package dev.zeroinput.ime.ai

import dev.zeroinput.userdata.AiConfiguration

/** Publishes loaded configuration atomically with revocation, independently of editor changes. */
internal class AiConfigurationState {
    private var revision = 0L
    private var value: AiConfiguration? = null
    private var controlRevoked = false

    @Synchronized fun current(): Long = revision

    @Synchronized fun snapshot(): AiConfiguration? = value

    @Synchronized fun isCurrent(token: Long): Boolean = revision == token

    /** Recovery is editable only; a failed control operation must keep transport revoked. */
    @Synchronized fun forEditing(token: Long, configuration: AiConfiguration): AiConfiguration? {
        if (token != revision) return null
        return if (controlRevoked) configuration.copy(enabled = false, networkAllowed = false) else configuration
    }

    @Synchronized fun revoke(): Long {
        value = null
        controlRevoked = true
        return ++revision
    }

    @Synchronized fun publish(token: Long, configuration: AiConfiguration?, explicitControl: Boolean = false): Boolean {
        if (token != revision || (controlRevoked && !explicitControl)) return false
        val redacted = configuration?.withoutCredentials()
        if (value != redacted) value = redacted
        if (explicitControl) controlRevoked = false
        return true
    }
}
