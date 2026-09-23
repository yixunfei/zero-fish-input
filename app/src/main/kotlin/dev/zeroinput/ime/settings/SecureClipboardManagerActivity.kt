package dev.zeroinput.ime.settings

import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.userdata.SecureClipboardMetadata

class SecureClipboardManagerActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread(
        name = "zeroinput-secure-clipboard-settings",
        queueCapacity = 8,
    )
    private lateinit var screen: SecureClipboardManagerView
    private var metadata: List<SecureClipboardMetadata> = emptyList()
    private var authenticationInProgress = false
    private var busy = false
    private var authenticationGeneration = 0L
    private var authenticationRequest: AuthenticationBroker.RequestHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = SecureClipboardManagerView(this)
        setContentView(screen)
        bindScreen()
        openVault()
    }

    override fun onStop() {
        screen.clearMetadata()
        if (!authenticationInProgress) {
            metadata = emptyList()
            if (!isChangingConfigurations) finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        authenticationGeneration++
        authenticationRequest?.close()
        authenticationRequest = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun bindScreen() {
        screen.onBackRequested = ::finish
        screen.onAddRequested = ::showAddDialog
        screen.onEnableRequested = ::enableAndOpen
        screen.onDeleteRequested = ::confirmDelete
    }

    private fun openVault() {
        if (!graph.settings.secureClipboardEnabled) {
            screen.renderDisabled()
            return
        }
        screen.renderLoading()
        authenticate(cancelClosesScreen = true, onGranted = ::loadMetadata)
    }

    private fun enableAndOpen() {
        authenticate(cancelClosesScreen = false) { grant ->
            graph.settings.secureClipboardEnabled = true
            loadMetadata(grant)
        }
    }

    private fun loadMetadata(grant: AuthenticationGrant) {
        val generation = graph.secureClipboard.captureGeneration()
        runVaultOperation(
            operation = { graph.secureClipboard.metadata(grant, generation) },
            onSuccess = {
                metadata = it
                screen.renderEntries(it)
            },
        )
    }

    private fun showAddDialog() {
        if (busy) return
        val label = secureField(R.string.secure_item_label, MAX_LABEL_LENGTH, multiline = false, concealed = false)
        val value = secureField(R.string.secure_item_value, MAX_VALUE_LENGTH, multiline = true, concealed = true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
            addView(label)
            addView(value)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_secure_item)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                filterTouchesWhenObscured = true
                setOnClickListener {
                    val secret = value.editText?.text?.toString().orEmpty()
                    if (secret.isBlank()) {
                        value.error = context.getString(R.string.secure_item_required)
                        return@setOnClickListener
                    }
                    val itemLabel = label.editText?.text?.toString().orEmpty()
                    val generation = graph.secureClipboard.captureGeneration()
                    dialog.dismiss()
                    authenticate(false) { grant -> addItem(itemLabel, secret, grant, generation) }
                }
            }
        }
        dialog.show()
    }

    private fun secureField(
        hint: Int,
        maxLength: Int,
        multiline: Boolean,
        concealed: Boolean,
    ): TextInputLayout {
        val field = TextInputEditText(this).apply {
            val variation = if (concealed) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            inputType = InputType.TYPE_CLASS_TEXT or variation or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (!multiline) imeOptions = imeOptions or EditorInfo.IME_ACTION_NEXT
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            maxLines = if (multiline) 6 else 1
            filterTouchesWhenObscured = true
        }
        return TextInputLayout(this).apply {
            setHint(hint)
            if (concealed) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(field)
        }
    }

    private fun addItem(label: String, value: String, grant: AuthenticationGrant, generation: Long) {
        runVaultOperation(
            operation = { graph.secureClipboard.add(label, value, grant, generation) },
            onSuccess = { added ->
                metadata = listOf(added) + metadata
                screen.renderEntries(metadata)
            },
        )
    }

    private fun confirmDelete(item: SecureClipboardMetadata) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_secure_item)
            .setMessage(item.label)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                authenticate(false) { grant -> deleteItem(item, grant) }
            }
            .show()
    }

    private fun deleteItem(item: SecureClipboardMetadata, grant: AuthenticationGrant) {
        val generation = graph.secureClipboard.captureGeneration()
        runVaultOperation(
            operation = { graph.secureClipboard.remove(item.id, grant, generation) },
            onSuccess = { removed ->
                if (removed) metadata = metadata.filterNot { it.id == item.id }
                screen.renderEntries(metadata)
            },
        )
    }

    private fun authenticate(cancelClosesScreen: Boolean, onGranted: (AuthenticationGrant) -> Unit) {
        if (busy) return
        setBusy(true)
        authenticationInProgress = true
        val generation = ++authenticationGeneration
        authenticationRequest?.close()
        authenticationRequest = AuthenticationBroker.requestCancellable(this) authCallback@{ grant ->
            if (generation != authenticationGeneration) return@authCallback
            authenticationRequest = null
            authenticationInProgress = false
            if (isFinishing || isDestroyed) return@authCallback
            if (grant != null) {
                onGranted(grant)
            } else {
                setBusy(false)
                if (cancelClosesScreen) {
                    finish()
                } else {
                    if (graph.settings.secureClipboardEnabled) {
                        screen.renderEntries(metadata)
                    } else {
                        screen.renderDisabled()
                    }
                    showError(getString(R.string.authentication_failed))
                }
            }
        }
    }

    private fun <T> runVaultOperation(operation: () -> T, onSuccess: (T) -> Unit) {
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess(onSuccess).onFailure { showError(it.message ?: getString(R.string.operation_failed)) }
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        screen.setActionsEnabled(!value)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_LABEL_LENGTH = 64
        const val MAX_VALUE_LENGTH = 8_192
    }
}
