package dev.zeroinput.security

import android.os.SystemClock
import java.security.SecureRandom

class AuthenticationGrant private constructor(
    private val nonce: Long,
    issuedAtMillis: Long,
) {
    private val lifetime = AuthenticationLifetime(issuedAtMillis)

    fun consume(maxAgeMillis: Long = AuthenticationLifetime.MAX_AGE_MILLIS): Boolean =
        lifetime.consume(SystemClock.elapsedRealtime(), maxAgeMillis) && nonce != 0L

    companion object {
        private val random = SecureRandom()

        fun afterSuccessfulSystemAuthentication(): AuthenticationGrant {
            var nonce = random.nextLong()
            while (nonce == 0L) nonce = random.nextLong()
            return AuthenticationGrant(nonce, SystemClock.elapsedRealtime())
        }
    }
}

/** Pure one-use lifetime policy; a caller may shorten the window, never extend it. */
internal class AuthenticationLifetime(private val issuedAtMillis: Long) {
    private var consumed = false

    @Synchronized fun consume(nowMillis: Long, maxAgeMillis: Long = MAX_AGE_MILLIS): Boolean {
        val available = !consumed
        consumed = true
        if (!available || issuedAtMillis < 0 || nowMillis < issuedAtMillis) return false
        return nowMillis - issuedAtMillis in 0..minOf(maxAgeMillis, MAX_AGE_MILLIS)
    }

    companion object { const val MAX_AGE_MILLIS = 30_000L }
}
