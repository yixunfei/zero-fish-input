package dev.zeroinput.ime.clipboard

import dev.zeroinput.security.AuthenticationGrant

/** Public editor identity narrows return navigation; the final tap binds the actual connection. */
internal data class PasteEditorIdentity(val packageName: String, val fieldId: Int, val inputType: Int)

/** Owns only an unused grant and an item identifier, never decrypted text or an editor connection. */
internal class SecurePasteConsent(
    val id: String,
    val source: PasteEditorIdentity,
    val generation: Long,
    private val now: () -> Long,
) : AutoCloseable {
    private var deadline = now() + 60_000L
    private var grant: AuthenticationGrant? = null
    private var targetSession: Long? = null
    private var closed = false
    var authorized = false
        private set

    fun authorize(value: AuthenticationGrant?): Boolean {
        if (!valid() || authorized || value == null) { close(); return false }
        authorized = true
        grant = value
        deadline = now() + 30_000L
        return true
    }

    fun bind(session: Long, editor: PasteEditorIdentity): Boolean {
        if (!valid() || editor != source || (targetSession != null && targetSession != session)) {
            close()
            return false
        }
        targetSession = session
        return true
    }

    /** Authentication navigation may end the original session only before return binding. */
    fun leaveEditor(): Boolean {
        if (targetSession != null) close()
        return valid()
    }

    fun editorChanged(identity: PasteEditorIdentity?): Boolean {
        // This callback reports an external selection edit, not return navigation.
        // Credential edits before binding are ignored; source edits or any edit
        // after return binding revoke the consent, even with the same public ID.
        if (targetSession != null || identity == source) close()
        return valid()
    }

    fun ready(session: Long): Boolean = valid() && authorized && targetSession == session

    fun confirm(session: Long): AuthenticationGrant? {
        if (!ready(session)) { close(); return null }
        return grant.also { close() }
    }

    override fun close() { closed = true; grant = null; targetSession = null }

    private fun valid(): Boolean {
        if (now() >= deadline) close()
        return !closed
    }
}
