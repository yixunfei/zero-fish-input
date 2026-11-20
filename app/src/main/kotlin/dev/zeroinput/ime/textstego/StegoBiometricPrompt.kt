package dev.zeroinput.ime.textstego

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import androidx.annotation.RequiresApi
import dev.zeroinput.ime.R
import dev.zeroinput.security.message.LocalMessageKey
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.Mac

/** The host closes the prompt with its workspace. The callback can consume only the original MAC. */
internal class StegoBiometricPrompt(context: Context, private val main: Handler,
                                    private var complete: ((Mac?) -> Unit)?) : AutoCloseable {
    private val app = context.applicationContext
    private val signal = CancellationSignal()

    fun start(encrypt: Boolean, current: () -> Boolean) {
        try {
            StegoSourceWorker.executor.execute {
                val mac = try { if (signal.isCanceled) null else LocalMessageKey.begin(encrypt) }
                    catch (_: Exception) { null }
                main.post {
                    if (signal.isCanceled || !current()) { close(); return@post }
                    try {
                        when {
                            mac == null -> finish(null)
                            Build.VERSION.SDK_INT >= 28 -> prompt(mac)
                            else -> fingerprint(mac)
                        }
                    } catch (_: RuntimeException) { finish(null) }
                }
            }
        } catch (_: RejectedExecutionException) { finish(null) }
    }

    @RequiresApi(28)
    private fun prompt(mac: Mac) {
        val executor = Executor { main.post(it) }
        val builder = BiometricPrompt.Builder(app).setTitle(app.getString(R.string.stego_local_mode))
            .setNegativeButton(app.getString(android.R.string.cancel), executor) { _, _ -> finish(null) }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(BiometricPrompt.CryptoObject(mac), signal, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(null)
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    finish(result.cryptoObject?.mac?.takeIf { it === mac })
            })
    }

    @Suppress("DEPRECATION")
    private fun fingerprint(mac: Mac) {
        val sensor = app.getSystemService(FingerprintManager::class.java) ?: return finish(null)
        sensor.authenticate(FingerprintManager.CryptoObject(mac), signal, 0,
            object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(null)
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) =
                    finish(result.cryptoObject?.mac?.takeIf { it === mac })
            }, main)
    }

    private fun finish(mac: Mac?) {
        val callback = complete ?: return
        complete = null
        callback(mac)
    }

    override fun close() { complete = null; signal.cancel() }
}
