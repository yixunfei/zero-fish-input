# ADR 0006: User-confirmed private text import

Status: accepted

Extended by [ADR 0012](0012-keyboard-private-copy-and-foreground-cleanup.md)
with a keyboard selection snapshot and in-keyboard confirmation.

## Context

Users need to collect selected text without first copying it into Android's
shared clipboard. Ordinary IMEs cannot replace other applications' Copy actions
or control access to the system clipboard. The protected resource is ZeroInput's
private vault, whose contents must never be available through an external read API.

## Decision

An exported import Activity accepts ACTION_PROCESS_TEXT and ACTION_SEND with
text/plain. Supporting source applications can offer Copy to ZeroInput in their
selection menu; text sharing provides a second entry. Neither entry accesses the
system clipboard. Custom selection menus may offer neither action.

All incoming Intents are untrusted, including explicit launches. Only bounded
plain text is accepted. URI/stream payloads, unsupported actions/types, malformed
extras, blank text, NULs and oversized text are rejected without opening content
providers or displaying parser errors. Formatting spans are discarded.

The Activity exposes no vault queries. System authentication precedes a separate
Save confirmation in the visible import screen. An authentication callback alone
never saves text. A disabled vault can be enabled only through the explicitly
labelled authentication action. Cancelling, replacing or recreating the screen
discards the draft. The system authentication flow may temporarily cover the
screen; the draft is retained only for that bounded request, and saving still
requires a new foreground user action after it returns.

The single-task screen uses FLAG_SECURE, excludes itself from recents, disables content/view
state capture and clears its launch Intent and temporary buffers. It returns no
text, identifiers or authentication status to its caller. Existing IME insertion
continues to use authenticated, session-bound InputConnection submission.

Background additions carry a cancellable request and the vault's deletion
generation. The vault checks these after acquiring its serialization lock and
before committing; clearing the vault invalidates queued additions. A write that
has already crossed its commit check may finish, and a subsequent clear is
serialized after it. No plaintext draft is persisted for process restoration.

## Consequences

The user performs authentication followed by confirmation, so a delayed result
cannot silently import content while another application is active. Only one
bounded draft is owned by each live import screen. Android IPC and temporary
String objects cannot be securely erased by application code; mutable buffers
are wiped and references released promptly. Source applications already know
the supplied text, and destination applications can read inserted text.

The existing vault format and keys are unchanged. No runtime permission or
dependency is added. The exported component expands only the untrusted input
surface, not access to existing private data.

## Alternatives

Reading and overwriting the system clipboard leaves an exposure window and
cannot revoke copies already read elsewhere. Global interception would require
privileges outside the ordinary Android application model.
