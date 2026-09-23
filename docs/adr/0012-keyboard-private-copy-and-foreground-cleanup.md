# ADR 0012: Keyboard private copying and foreground clipboard cleanup

Status: accepted for ordinary-app capabilities (2026-09-09).

## Context

An iQOO Neo9 / Android 16 report exposed a usability and acceptance gap: source
menus often omit or bury Copy to ZeroInput, and the reported clipboard history
remains after current-item cleanup. The implementation also rejected foreground
cleanup unless ZeroInput was selected, and ignored existing items on automatic
protection startup. Earlier fixture tests did not establish history deletion.

The user prioritized usable private copy and cleanup, allowing supported vendor
interfaces if necessary while excluding root, Shizuku and user workarounds for
system privilege restrictions. This decision extends ADR 0006 and updates ADR
0007's foreground authorization and automatic-startup behavior.

## Decision

Provide a copy-selection control in the private clipboard panel. It reads only
an explicitly selected, bounded range from the current nonsensitive editor.
The selected length must be known and at most 8192 UTF-16 units; the response
must have the same length and pass shared plain-text validation. A single bounded
worker performs the editor call. Session, connection, selection, interactions
and settings generation determine whether a completed result may be accepted.

The accepted snapshot transfers once to a nonexported import page. Only an opaque
token crosses the Intent boundary; a single process-local draft expires after
five seconds unless consumed. The page owns the frozen draft after handoff and
does not access or submit to the originating editor. Thus its own navigation can
end the editor session without losing the deliberately captured import. Existing
authentication followed by explicit Save is reused; no authentication result
alone writes. The vault deletion generation is captured before the editor read,
so a concurrent clear cannot be undone by a delayed transfer or save.

Foreground current-item operations retain monitoring opt-in but use a short-lived
focus lease instead of default-IME identity. Losing focus or leaving the page
revokes the queued operation; returning cannot revive the old lease. The worker
still validates the runtime generation and timestamp. Background monitoring and
background cleanup retain selected-IME and attached-service requirements.

The explicit automatic-mode warning authorizes processing the current item on
startup, when monitoring becomes eligible again and when the settings page gains
focus, as well as later callbacks. An explicit keyboard private-copy action is an
equivalent authorization moment: after the selection snapshot is captured for the
private vault, automatic mode silently inspects and clears a stale current item
left behind by a source-app copy. That queued operation holds only an
input-session lease, carries neither the captured text nor a grant, and is
revoked when the editing context changes. Other modes retain their
non-destructive startup baseline. Authentication remains incompatible with
automatic mode.

### Return and confirm private paste

The Android 16 device-credential page unbinds the originating input connection.
The user explicitly approved replacing the old automatic post-authentication
paste with authentication followed by return and a fresh Confirm paste action.
Removing the old connection checks without this new action was rejected.

An application-owned coordinator retains one item ID, source editor metadata
(package, field ID and input type), vault deletion generation and an unused
one-use grant. It holds no IME, InputConnection or decrypted text. Authentication
times out after 60 seconds; successful authentication starts a 30-second grant
deadline. Nothing is persisted or restored after process death. The initial
authentication navigation may end the original session. Temporary credential
editors receive neither the grant nor a paste control.

After the source editor returns, the coordinator binds the returned session for
a visible Confirm paste / Cancel paste choice. Public editor metadata only
narrows the return destination; it is not a trustworthy connection identity.
Only the new confirmation binds the actual current InputConnection and interaction
sequence, consumes the pending intent once, and schedules decryption on the bounded
worker. It revalidates the connection, session, request and deletion generation
before reading and before committing. Authentication callbacks never decrypt or
insert text. The destination app necessarily receives explicitly pasted text.

The authentication Activity hands its result to the broker after destruction,
not inside the prompt success callback while its window is still exiting. This
prevents binding an intermediate source editor that is about to receive a late
finish-input-view callback, and prevents starting another authentication while
the preceding authentication Activity is still closing. Cancellation and the
broker timeout can still revoke the request before delivery.

After return binding, leaving the editor, continued editing, panel changes,
settings/language/subtype/default-IME changes, cancellation, deletion and timeout
revoke the operation. Engine adoption and programmatic initial panel rendering
cannot insert text; they preserve only the unused consent awaiting a user tap.
Deleting an entry or clearing the vault invalidates its captured generation even
when a returned confirmation is still visible.

## Consequences and limits

Editable text no longer depends on a source application's optional selection
menu. Noneditable text still needs a supported selection action or text sharing.
Private copying does not write to the shared clipboard; it does not erase any
copy already stored by a source app, vendor service or another keyboard.

No permission, networking dependency, vault format, root or Shizuku path is added.
There is no verified vendor history deletion integration. The iQOO history
requirement remains open pending identification of its owner and a supported
deletion interface. No API is inferred from a menu label, and current-item
metadata cannot establish erasure of independent histories or cloud copies.

## Verification

Regressions cover selection bounds, malformed responses, cancellation during
reads and after delivery is queued, stale editor results, executor rejection,
one-use transfer, nonexported entry, concurrent vault deletion, initial automatic
cleanup and foreground cleanup without background eligibility. Platform fixtures
exercise actual IME selection, system device-credential authentication and an
explicit Save without mutating the current system clip, followed by a second
system authentication, return, explicit confirmation and insertion. Negative
cases cover cancellation, source edits, a different editor, settings/deletion,
duplicate callbacks, unused grants and expiry. The physical iQOO build, biometrics
and vendor history need separate device acceptance. Current validation evidence
is recorded in [clipboard-device-validation.md](../clipboard-device-validation.md).
