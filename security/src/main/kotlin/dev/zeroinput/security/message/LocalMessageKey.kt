package dev.zeroinput.security.message

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Worker-only. Each initialized operation needs its own biometric authorization. */
object LocalMessageKey {
    private const val ALIAS = "zeroinput.message.biometric.hmac.v1"
    private const val STORE = "AndroidKeyStore"
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_HMAC_SHA256

    @Synchronized
    fun begin(creatingMessage: Boolean): Mac {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) {
            try { return Mac.getInstance(ALGORITHM).apply { init(existing) } }
            catch (invalid: KeyPermanentlyInvalidatedException) {
                if (!creatingMessage) throw invalid
            }
        }
        if (!creatingMessage) throw MessageFailure()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        val key = KeyGenerator.getInstance(ALGORITHM, STORE).run { init(spec.build()); generateKey() }
        return Mac.getInstance(ALGORITHM).apply { init(key) }
    }
}
