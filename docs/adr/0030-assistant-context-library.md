# ADR 0030: Explicit assistant roles and memory

- Status: Accepted by the owner on 2026-11-02 as E1 of the assistant expansion
- Scope: Standalone roles, scoped memory, reviewed context and encrypted storage

## Decision

The private standalone chat may manage user-authored roles and explicit memories.
Roles contain bounded name, description and instructions. Memory belongs to a
role unless the user explicitly chooses sharing. Shared items are eligible for
selection, not automatically included. Opening, saving or selecting content
never starts a model request. Each chat starts without role/memory selection.

Previewed selections share the existing 16,384-character context ceiling with
history and references. Role instructions are user preferences serialized as
structured user content, not system messages, permissions or executable commands.
Only selected text goes to the current provider on submission; local IDs do not.

Roles and scoped memories use one atomic versioned context-library file so role
deletion and removal of its memories are indivisible. It is encrypted under
noBackupFilesDir with a dedicated AI_CONTEXT_LIBRARY key, separate from settings
and conversations. Existing formats are unchanged. Explicit local Save is
independent of conversation-history opt-in. No automatic memory extraction,
plaintext disk cache, editor read or clipboard access is added.

Edits check a persisted library revision. UI work binds revocable review-session
and AI-data generations. AI clear attempts deletion of the context file/key even
if another AI deletion fails. Failed deletion closes further operations until a
successful retry. Library mutations clear the active selection before editing;
users must reapply. Lifecycle, lock, configuration and data revocation discard
transient selection, dialogs and late results. Views use FLAG_SECURE, no saved
instance content, autofill or content capture.

## Alternatives and consequences

Automatic chat mining or cross-role sharing would collect content without review.
Separate role/memory files would need cross-file transactions; one atomic library
keeps scoped deletion consistent while remaining separate from other domains.
JVM String values cannot reliably be wiped; owners release them promptly and
clear byte/character buffers wherever possible.

Tests cover scope/budget, strict and damaged formats, stale writes, deletion
failures and races, late callbacks, protocol encoding, no-send navigation and
private-view lifecycle. Other accepted stages receive boundary records when
implemented. This ADR adds no new network endpoint or privileged backend.
