package dev.zeroinput.ime.ai

import java.util.UUID

/** In-memory authority for one task, independent of any Activity instance. */
internal class AiTaskLease(private val now: () -> Long) {
    private var nonce: String? = null
    private var owner: String? = null
    private var visible = false
    private var handoff = false
    private var background = false
    private var deadline = 0L
    private var complete = false
    @Synchronized fun begin(owner: String, background: Boolean): String {
        nonce = UUID.randomUUID().toString(); this.owner = owner; visible = true; handoff = false
        this.background = background; complete = false; deadline = now() + if (background) 120_000 else 300_000
        return requireNotNull(nonce)
    }
    @Synchronized fun enter(owner: String) { this.owner = owner; visible = true; handoff = false }
    @Synchronized fun leave(owner: String, picker: Boolean): Boolean {
        if (this.owner != owner) return true
        visible = false; handoff = picker
        return valid() && (background || picker)
    }
    @Synchronized fun destroy(owner: String): Boolean {
        if (this.owner != owner) return true
        visible = false; handoff = false; this.owner = null
        return valid() && background
    }
    @Synchronized fun valid(token: String? = nonce) = nonce != null && nonce == token && now() < deadline
    @Synchronized fun canDispatch(serviceReady: Boolean) = valid() && !complete && (visible || handoff || background && serviceReady)
    @Synchronized fun canApprove(owner: String) = valid() && !complete && visible && this.owner == owner
    @Synchronized fun completed() { complete = true; handoff = false; deadline = now() + 120_000 }
    @Synchronized fun remaining() = (deadline - now()).coerceAtLeast(1)
    @Synchronized fun revoke() { nonce = null; owner = null; visible = false; handoff = false; complete = false; deadline = 0 }
}
