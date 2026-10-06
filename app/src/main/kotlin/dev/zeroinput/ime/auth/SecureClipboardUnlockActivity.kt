package dev.zeroinput.ime.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.zeroinput.ime.R
import dev.zeroinput.security.AuthenticationGrant

class SecureClipboardUnlockActivity : AppCompatActivity() {
    private var requestId = ""
    private var completed = false
    private var finishedGrant: AuthenticationGrant? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isEmpty()) {
            finish()
            return
        }
        authenticate()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A prompt can succeed before its Activity has stopped covering the editor.
        // Deliver only after this navigation ends, so return binding cannot attach
        // to the transient editor exposed while the credential page is closing.
        if (!isChangingConfigurations && requestId.isNotEmpty()) {
            AuthenticationBroker.complete(requestId, if (completed) finishedGrant else null)
        }
        finishedGrant = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (requestId.isNotEmpty()) AuthenticationBroker.complete(requestId, null)
        completed = false
        finishedGrant = null
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        setIntent(intent)
        if (requestId.isEmpty()) finish() else authenticate()
    }

    private fun authenticate() {
        if (!canAuthenticate()) {
            complete(null)
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    complete(AuthenticationGrant.afterSuccessfulSystemAuthentication())
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    complete(null)
                }
            },
        )
        prompt.authenticate(createPromptInfo())
    }

    @Suppress("DEPRECATION")
    private fun createPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(if (intent.getIntExtra(EXTRA_PROMPT_TITLE, 0) == R.string.clipboard_guard_authentication_title) {
                R.string.clipboard_guard_authentication_title
            } else R.string.unlock_secure_clipboard))
            .setSubtitle(getString(R.string.app_name))
            .setConfirmationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(COMBINED_AUTHENTICATORS)
        } else if (hasSecureDeviceCredential()) {
            builder.setDeviceCredentialAllowed(true)
        } else {
            builder
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.cancel))
        }
        return builder.build()
    }

    private fun canAuthenticate(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return BiometricManager.from(this).canAuthenticate(COMBINED_AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        if (hasSecureDeviceCredential()) return true
        return BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun hasSecureDeviceCredential(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun complete(grant: AuthenticationGrant?) {
        if (completed) return
        completed = true
        finishedGrant = grant
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "dev.zeroinput.ime.auth.REQUEST_ID"
        const val EXTRA_PROMPT_TITLE = "dev.zeroinput.ime.auth.PROMPT_TITLE"

        private const val COMBINED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
