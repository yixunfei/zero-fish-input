package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Local declarative library. A picker result is reviewed, never activated or sent. */
class AiSkillsActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private val main = Handler(Looper.getMainLooper())
    private val importEpoch = AtomicLong()
    private var started = false
    private var library = AiSkillLibrary()
    private var libraryBusy = true
    private var importing = false
    private var session: AiLibrarySession<AiSkillLibrary>? = null
    private var editor: AlertDialog? = null
    private var observer: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var signal: CancellationSignal? = null
    private var pickerDeadline = 0L
    private var pickerData = -1L
    private var pendingUri: Uri? = null
    private var pendingDraft: AiSkillDocument.Draft? = null
    private val readTimeout = Runnable {
        importEpoch.incrementAndGet(); signal?.cancel(); signal = null
        importing = false
        updateActions()
        if (started) status.setText(R.string.ai_skill_import_failed)
    }
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var create: MaterialButton
    private lateinit var importButton: MaterialButton
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val valid = uri != null && pickerDeadline > 0 && SystemClock.elapsedRealtime() <= pickerDeadline &&
            graph.aiDataGeneration.isCurrent(pickerData) && !isFinishing
        pickerDeadline = 0
        if (valid) { pendingUri = uri; if (started && hasWindowFocus()) consumeImport() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent = Intent(); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        status = TextView(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false }
        create = button(R.string.ai_skill_create) { showEditor() }
        importButton = button(R.string.ai_skill_import) {
            if (allowed() && !libraryBusy && !importing) {
                pickerDeadline = SystemClock.elapsedRealtime() + 120_000
                pickerData = graph.aiDataGeneration.current()
                picker.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown"))
            }
        }
        body.addView(TextView(this).apply { setText(R.string.ai_skill_title); textSize = 24f })
        body.addView(TextView(this).apply { setText(R.string.ai_skill_notice) })
        body.addView(create); body.addView(importButton); body.addView(status); body.addView(rows)
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val pad = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
    }

    override fun onStart() {
        super.onStart(); started = true
        if (!allowed()) { finish(); return }
        observer = graph.observeAiConfiguration { main.post { finish() } }
        settingsObserver = graph.settings.addChangeListener { main.post { finish() } }
        session = AiLibrarySession(graph.aiPersistenceExecutor, graph.aiDataGeneration, { main.post(it) }, ::loaded)
        reload()
    }

    override fun onResume() { super.onResume(); if (started) consumeImport() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && started) consumeImport() }
    private fun allowed() = started && !isFinishing && !isDestroyed && graph.settings.learningEnabled && !graph.settings.incognitoMode &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun reload() {
        busy(); val repository = graph.aiSkills
        session?.run { current -> repository.read(current) }
    }
    private fun busy() { libraryBusy = true; create.isEnabled = false; importButton.isEnabled = false; rows.removeAllViews(); status.setText(R.string.ai_library_loading) }
    private fun loaded(result: Result<AiSkillLibrary>) {
        if (!allowed()) return
        result.fold({ value ->
            library = value; libraryBusy = false; updateActions()
            status.text = ""; render()
            pendingDraft?.let { pendingDraft = null; showEditor(imported = it) }
        }, {
            status.setText(R.string.ai_library_failed)
            rows.removeAllViews(); rows.addView(button(R.string.ai_library_retry) { reload() })
        })
    }

    private fun render() {
        rows.removeAllViews()
        if (library.skills.isEmpty()) rows.addView(TextView(this).apply { setText(R.string.ai_skill_empty) })
        library.skills.forEach { skill ->
            rows.addView(TextView(this).apply { text = skill.name; textSize = 18f })
            rows.addView(TextView(this).apply { text = skill.description })
            if (skill.requiredTools.isNotEmpty()) rows.addView(TextView(this).apply {
                text = getString(R.string.ai_skill_unsupported, skill.requiredTools.joinToString(", "))
            })
            rows.addView(button(R.string.ai_library_edit) { showEditor(skill) })
            rows.addView(button(R.string.ai_library_delete) {
                editor?.dismiss()
                editor = MaterialAlertDialogBuilder(this).setTitle(R.string.ai_skill_delete)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_library_delete) { _, _ ->
                        if (allowed() && !importing) save(library.skills.filterNot { it.id == skill.id })
                    }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
            })
        }
    }

    private fun showEditor(existing: AiSkill? = null, imported: AiSkillDocument.Draft? = null) {
        if (!allowed() || libraryBusy || importing) return
        editor?.dismiss()
        editor = AiSkillEditorDialog.show(this, existing, imported, ::allowed) { value ->
            save(library.skills.filterNot { it.id == value.id } + value)
        }
    }

    private fun save(skills: List<AiSkill>): Boolean {
        if (!allowed() || libraryBusy) return false
        val value = runCatching { AiSkillLibrary(skills = skills) }.getOrNull() ?: return false
        val revision = library.revision; val repository = graph.aiSkills
        busy(); session?.run { current -> repository.write(revision, value, current) }
        return true
    }

    private fun consumeImport() {
        if (!allowed()) { pendingUri = null; return }
        val uri = pendingUri ?: return
        pendingUri = null
        importing = true; updateActions()
        val cancellation = CancellationSignal().also { signal?.cancel(); signal = it }
        val epoch = importEpoch; val ticket = epoch.incrementAndGet()
        val data = graph.aiDataGeneration; val dataTicket = data.current()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val current = { epoch.get() == ticket && data.isCurrent(dataTicket) && SystemClock.elapsedRealtime() <= deadline }
        val resolver = applicationContext.contentResolver; val owner = WeakReference(this); val handler = main
        status.setText(R.string.ai_library_loading)
        main.removeCallbacks(readTimeout); main.postDelayed(readTimeout, 30_000)
        try {
            graph.aiDocumentExecutor.execute {
                val result = runCatching { AiSkillDocument.parse(AiDocumentTextReader.read(resolver, uri, cancellation, AiSkill.MAX_BYTES, current)) }
                handler.post {
                    val activity = owner.get()
                    if (activity != null && activity.allowed() && current()) activity.imported(result)
                }
            }
        } catch (_: RejectedExecutionException) { imported(Result.failure(IllegalStateException("Import unavailable"))) }
    }

    private fun imported(result: Result<AiSkillDocument.Draft>) {
        main.removeCallbacks(readTimeout)
        importing = false; updateActions()
        signal = null
        result.fold({ draft ->
            status.text = ""
            if (libraryBusy) pendingDraft = draft else showEditor(imported = draft)
        }, { status.setText(R.string.ai_skill_import_failed) })
    }

    private fun button(text: Int, action: () -> Unit) = MaterialButton(this).apply { setText(text); setOnClickListener { action() } }
    private fun updateActions() {
        create.isEnabled = !libraryBusy && !importing
        importButton.isEnabled = !libraryBusy && !importing
    }
    override fun onStop() {
        started = false; session?.close(); session = null
        main.removeCallbacks(readTimeout)
        importEpoch.incrementAndGet(); signal?.cancel(); signal = null
        observer?.close(); observer = null; settingsObserver?.close(); settingsObserver = null
        editor?.dismiss(); editor = null; library = AiSkillLibrary(); pendingDraft = null; pendingUri = null; importing = false
        rows.removeAllViews(); status.text = ""
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }
}
