package dev.zeroinput.ime.ai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException

/** Untrusted imports are reviewed here; only the keyboard can submit or insert them. */
class AiComposeActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private var readSignal: CancellationSignal? = null
    private val handler = Handler(Looper.getMainLooper())
    private val attachments = mutableListOf<AiAttachment>()
    private var generation = 0L
    private var dataGeneration = -1L
    private var observer: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private lateinit var prompt: EditText
    private lateinit var attachmentRows: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: MaterialButton
    private lateinit var confirm: MaterialButton
    private val expire = Runnable { finish() }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || isFinishing || isDestroyed) return@registerForActivityResult
        val token = generation
        val owner = WeakReference(this)
        val resolver = applicationContext.contentResolver
        val main = handler
        val signal = CancellationSignal().also { readSignal = it }
        add.isEnabled = false
        confirm.isEnabled = false
        try {
            graph.aiDocumentExecutor.execute {
                val value = runCatching { AiAttachmentReader.read(resolver, uri, signal) }.getOrNull()
                if (!main.post {
                    val activity = owner.get()
                    if (activity == null) value?.bytes?.fill(0) else activity.deliverAttachment(value, token)
                }) {
                    value?.bytes?.fill(0)
                }
            }
        } catch (_: RejectedExecutionException) {
            status.setText(R.string.ai_attachment_failed)
            add.isEnabled = true
            confirm.isEnabled = true
        }
    }

    private fun deliverAttachment(value: AiAttachment?, token: Long) {
        readSignal = null
        if (token != generation || isFinishing || isDestroyed || !dataCurrent()) {
            value?.bytes?.fill(0)
        } else {
            if (value == null || attachments.size >= AiLimits.MAX_ATTACHMENTS ||
                attachments.sumOf { it.bytes.size } + value.bytes.size > AiLimits.MAX_ATTACHMENT_BYTES) {
                value?.bytes?.fill(0)
                status.setText(R.string.ai_attachment_failed)
            } else { attachments += value; renderAttachments() }
            add.isEnabled = true
            confirm.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        dataGeneration = graph.aiDataGeneration.current()
        val incoming = intent
        val initial = if (savedInstanceState == null) AiImportIntent.text(incoming) else null
        intent = Intent()
        incoming.replaceExtras(null as Bundle?)
        incoming.clipData = null
        if (initial == null || !dataCurrent()) { finish(); return }
        buildScreen(initial)
        observer = graph.observeAiConfiguration { runOnUiThread { finish() } }
        settingsObserver = graph.settings.addChangeListener { runOnUiThread { finish() } }
        handler.postDelayed(expire, AiContentInbox.LIFETIME_MS)
    }

    private fun dataCurrent() = graph.aiDataGeneration.isCurrent(dataGeneration) &&
        graph.settings.learningEnabled && !graph.settings.incognitoMode

    private fun buildScreen(initial: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30)
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        content.addView(TextView(this).apply { setText(R.string.ai_import_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(R.string.ai_import_notice); textSize = 14f })
        prompt = EditText(this).apply {
            hint = getString(dev.zeroinput.ime.ui.R.string.ai_input_hint)
            filters = arrayOf(InputFilter.LengthFilter(AiLimits.MAX_INPUT_CHARS))
            setText(initial)
            minLines = 3
            maxLines = 8
            isSaveEnabled = false
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        content.addView(prompt)
        add = button(R.string.ai_add_attachment) { picker.launch(ALLOWED_TYPES) }
        content.addView(add)
        attachmentRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(attachmentRows)
        status = TextView(this).apply { isSaveEnabled = false }
        content.addView(status)
        confirm = button(R.string.ai_import_confirm, ::confirmImport)
        content.addView(confirm)
        content.addView(button(R.string.cancel) { finish() })
        setContentView(ScrollView(this).apply {
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    private fun confirmImport() {
        if (isFinishing || !confirm.isEnabled || !hasWindowFocus() || !dataCurrent()) return
        val text = prompt.text.toString().trim()
        if (text.isBlank() && attachments.isEmpty()) {
            prompt.error = getString(dev.zeroinput.ime.ui.R.string.ai_input_required)
            return
        }
        val inbox = graph.aiContentInbox
        confirm.isEnabled = false
        inbox.put(AiImportedContent(text.toCharArray(), attachments.toList()))
        attachments.clear() // Ownership moves to the expiring inbox.
        Handler(Looper.getMainLooper()).postDelayed({ inbox.expire() }, AiContentInbox.LIFETIME_MS)
        Toast.makeText(this, R.string.ai_import_ready, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun renderAttachments() {
        attachmentRows.removeAllViews()
        attachments.toList().forEach { attachment ->
            attachmentRows.addView(MaterialButton(this).apply {
                text = getString(R.string.ai_remove_attachment, attachment.displayName)
                isAllCaps = false
                setOnClickListener {
                    if (attachments.remove(attachment)) attachment.bytes.fill(0)
                    renderAttachments()
                }
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onDestroy() {
        generation++
        observer?.close()
        settingsObserver?.close()
        handler.removeCallbacks(expire)
        readSignal?.let { signal ->
            runCatching { graph.aiCancellationExecutor.execute { signal.cancel() } }
        }
        readSignal = null
        attachments.forEach { it.bytes.fill(0) }
        attachments.clear()
        if (::prompt.isInitialized) prompt.text?.clear()
        if (::status.isInitialized) status.text = ""
        super.onDestroy()
    }

    private fun button(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val ACTION_PICK = "dev.zeroinput.ime.ai.PICK_CONTENT"
        private val ALLOWED_TYPES = arrayOf("text/plain", "image/jpeg", "image/png", "image/webp",
            "audio/wav", "audio/x-wav", "audio/mpeg")
    }
}
