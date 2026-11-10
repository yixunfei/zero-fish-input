package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiContextLibraryRepository
import java.util.UUID
import java.util.concurrent.Executor

/** Private, explicit management and selection; editing never starts a request. */
internal class AiContextLibraryDialog(
    private val context: Context,
    repository: AiContextLibraryRepository,
    executor: Executor,
    data: AiDataGeneration,
    post: (() -> Unit) -> Boolean,
    initial: AiContextState,
    private val allowed: () -> Boolean,
    private val changing: () -> Unit,
    private val apply: (AiWorkspaceContext) -> Boolean,
) : AutoCloseable {
    private var library = AiContextLibrary()
    private val baseCharacters = initial.characterCount - initial.workspace.characterCount
    private var selectedSkills = initial.workspace.skills
    private var selectedRole = initial.workspace.persona?.id
    private val selectedMemories = initial.workspace.memories.map { it.id }.toMutableSet()
    private var loading = true
    private var applying = false
    private var closed = false
    private var child: AlertDialog? = null
    private val rows = column()
    private val status = TextView(context)
    private val session = AiContextLibrarySession(repository, executor, data, post, ::loaded)
    private val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_library_title)
        .setView(ScrollView(context).apply { addView(rows) })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ai_library_apply, null).create()

    fun show() {
        dialog.setOnDismissListener { close() }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!loading && allowed()) { applying = true; busy(); session.read() }
        }
        busy(); session.read()
    }

    private fun loaded(result: Result<AiContextLibrary>) {
        if (closed || !allowed()) { close(); return }
        loading = false
        result.fold({ value ->
            if (applying) {
                applying = false
                if (value.revision == library.revision) {
                    val selection = selection()
                    if (selection != null && apply(selection)) { close(); return@fold }
                }
                selectedRole = null; selectedMemories.clear()
                library = value; render(); status.setText(R.string.ai_library_expired)
            } else {
                library = value
                if (library.personas.none { it.id == selectedRole }) selectedRole = null
                selectedMemories.retainAll(library.memories.filter { it.personaId == null || it.personaId == selectedRole }.map { it.id }.toSet())
                render()
            }
        }, {
            applying = false
            rows.removeAllViews(); rows.addView(status); status.setText(R.string.ai_library_failed)
            rows.addView(button(R.string.ai_library_retry) { busy(); session.read() })
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        })
    }

    private fun busy() {
        loading = true
        rows.removeAllViews(); rows.addView(status); status.setText(R.string.ai_library_loading)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    private fun render() {
        rows.removeAllViews()
        rows.addView(label(context.getString(R.string.ai_library_notice)))
        rows.addView(status); status.text = ""
        val roles = RadioGroup(context)
        val choices = listOf<AiPersona?>(null) + library.personas
        choices.forEach { role ->
            roles.addView(RadioButton(context).apply {
                id = View.generateViewId(); text = role?.name ?: context.getString(R.string.ai_library_default)
                isChecked = role?.id == selectedRole
                setOnClickListener { selectedRole = role?.id; selectedMemories.clear(); render() }
            })
        }
        rows.addView(roles)
        rows.addView(button(R.string.ai_library_new_role) { editRole(null) })
        library.personas.firstOrNull { it.id == selectedRole }?.let { role ->
            rows.addView(label(role.description + "\n" + role.instructions))
            rows.addView(button(R.string.ai_library_edit) { editRole(role) })
            rows.addView(button(R.string.ai_library_duplicate) { editRole(role, duplicate = true) })
            rows.addView(button(R.string.ai_library_delete) {
                confirm(R.string.ai_library_delete_role) {
                    selectedRole = null; selectedMemories.clear()
                    save(AiContextLibrary(personas = library.personas.filterNot { it.id == role.id },
                        memories = library.memories.filterNot { it.personaId == role.id }))
                }
            })
        }
        rows.addView(label(context.getString(R.string.ai_library_memory_heading)))
        val available = library.memories.filter { it.personaId == null || it.personaId == selectedRole }
        if (available.isEmpty()) rows.addView(label(context.getString(R.string.ai_library_none)))
        available.forEach { memory -> memoryRow(memory) }
        rows.addView(button(R.string.ai_library_new_memory) { editMemory(null) })
        rows.addView(button(R.string.ai_library_preview) { preview() })
        updateBudget()
    }

    private fun memoryRow(memory: AiMemory) {
        rows.addView(CheckBox(context).apply {
            text = if (memory.personaId == null) context.getString(R.string.ai_library_shared_label, memory.text) else memory.text
            isChecked = memory.id in selectedMemories
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedMemories += memory.id else selectedMemories -= memory.id
                updateBudget()
            }
        })
        rows.addView(button(R.string.ai_library_edit) { editMemory(memory) })
        rows.addView(button(R.string.ai_library_delete) {
            confirm(R.string.ai_library_delete_memory) {
                selectedMemories -= memory.id
                save(AiContextLibrary(personas = library.personas, memories = library.memories.filterNot { it.id == memory.id }))
            }
        })
    }

    private fun updateBudget() {
        val value = selection()
        val count = baseCharacters + (value?.characterCount ?: AiReference.MAX_CONTEXT_CHARS + 1)
        status.text = context.getString(R.string.ai_library_budget, count, AiReference.MAX_CONTEXT_CHARS)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = value != null && count <= AiReference.MAX_CONTEXT_CHARS
    }

    private fun preview() {
        val value = selection() ?: return
        val preview = listOfNotNull(value.persona?.let { it.name + "\n" + it.description + "\n" + it.instructions }) +
            value.memories.map { it.text } + value.skills.map { it.name + "\n" + it.description + "\n" + it.instructions }
        showChild(MaterialAlertDialogBuilder(context).setTitle(R.string.ai_library_preview)
            .setView(ScrollView(context).apply { addView(label(preview.joinToString("\n\n"))) })
            .setPositiveButton(android.R.string.ok, null).create())
    }

    private fun editRole(role: AiPersona?, duplicate: Boolean = false) {
        val name = input(R.string.ai_library_name, 80, role?.name.orEmpty())
        val description = input(R.string.ai_library_description, 512, role?.description.orEmpty())
        val instructions = input(R.string.ai_library_instructions, 4_096, role?.instructions.orEmpty())
        editor(listOf(name, description, instructions)) {
            val value = AiPersona(if (duplicate || role == null) UUID.randomUUID().toString() else role.id,
                name.text.toString().trim(), description.text.toString().trim(), instructions.text.toString().trim())
            save(AiContextLibrary(personas = library.personas.filterNot { it.id == value.id } + value, memories = library.memories))
        }
    }

    private fun editMemory(memory: AiMemory?) {
        val text = input(R.string.ai_library_memory_text, 2_048, memory?.text.orEmpty())
        val shared = CheckBox(context).apply { setText(R.string.ai_library_shared); isChecked = memory != null && memory.personaId == null }
        editor(listOf(text, label(context.getString(R.string.ai_library_scope)), shared)) {
            require(shared.isChecked || selectedRole != null)
            val value = AiMemory(memory?.id ?: UUID.randomUUID().toString(), if (shared.isChecked) null else selectedRole, text.text.toString().trim())
            save(AiContextLibrary(personas = library.personas, memories = library.memories.filterNot { it.id == value.id } + value))
        }
    }

    private fun editor(views: List<View>, commit: () -> Unit) {
        val error = TextView(context)
        val form = column().apply { views.forEach(::addView); addView(error) }
        val edit = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_library_title)
            .setView(ScrollView(context).apply { addView(form) }).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_library_save, null).create()
        showChild(edit)
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!allowed()) { close(); return@setOnClickListener }
            try { commit(); edit.dismiss() } catch (_: IllegalArgumentException) { error.setText(R.string.ai_library_invalid) }
        }
        edit.setOnDismissListener { views.filterIsInstance<EditText>().forEach { it.text?.clear() }; if (child === edit) child = null }
    }

    private fun save(value: AiContextLibrary) {
        if (!allowed() || loading) return
        changing()
        busy()
        session.write(library.revision, value)
    }

    private fun selection() = runCatching {
        val value = library.select(selectedRole, selectedMemories)
        AiWorkspaceContext(value.persona, value.memories, selectedSkills)
    }.getOrNull()

    private fun confirm(title: Int, action: () -> Unit) {
        showChild(MaterialAlertDialogBuilder(context).setTitle(title).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_library_delete) { _, _ -> if (allowed()) action() else close() }.create())
    }

    private fun showChild(value: AlertDialog) {
        child?.dismiss(); child = value
        value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        value.show()
    }

    private fun input(hint: Int, limit: Int, value: String) = EditText(context).apply {
        setHint(hint); setText(value); maxLines = 6; isSaveEnabled = false
        filters = arrayOf(InputFilter.LengthFilter(limit))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
    private fun label(value: String) = TextView(context).apply { text = value; setTextIsSelectable(true) }
    private fun button(title: Int, action: () -> Unit) = com.google.android.material.button.MaterialButton(context).apply {
        setText(title); setOnClickListener { action() }
    }
    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; isSaveEnabled = false
        val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    override fun close() {
        if (closed) return
        closed = true; session.close(); child?.dismiss(); child = null
        dialog.dismiss(); rows.removeAllViews(); status.text = ""
        library = AiContextLibrary(); selectedMemories.clear(); selectedRole = null; selectedSkills = emptyList()
    }
}
