# ADR 0036: Private temporary input

Status: accepted

## Context

On 2026-11-11 the user confirmed a private keyboard draft that opens by default
once enabled. Each transition of the default requires system authentication.
Explicit save and submit commands during use require no additional authentication.
Reading a saved body retains the existing one-use grant and paste confirmation.

## Decision

- An app-private default starts off. Enabling it explicitly enables the secure
  clipboard in the same settings edit. Disabling requires a new one-use grant.
  The stored setting is consent for explicit draft writes, with no read capability.
  If the user turns off the secure clipboard independently, writes fail closed.
- A separate local input controller accepts only input to the private keyboard
  draft in supported plain text editors. The IME window is secure, the draft
  display shows current composition and candidates plus the last three committed
  characters. An explicit display toggle reveals the full draft until it is
  masked again or the draft closes. Lifecycle changes clear the draft, reset
  the display toggle and revoke queued operations.
- Save and close appends one encrypted entry. Save and submit also appends by
  default, with an independent preference to skip the copy. Submit only
  goes directly to the editor. Submission checks
  the original connection and interaction again. If saving succeeds but the
  editor refuses the text, the saved copy remains and the app reports the outcome.
- The vault writes each private draft to a new UUID-named AES-256-GCM file with a
  dedicated Keystore alias, in its no-backup directory. Directory metadata
  supplies opaque IDs and times for listing. The existing vault format remains
  readable as before. Both stores share the entry limit, deletion generation,
  authentication entry points and whole-vault purge.

## Alternatives and consequences

Reusing the existing multi-entry body would require decrypting old entries during
an append. Separate encrypted files allow the new operation to write without
reading old bodies. They also make removal of a draft independent of the legacy
body. Clear-all must remove both stores and their keys, including any temporary
atomic files.

The bounded live draft and active engine preedit need plaintext for editing.
Encrypting a redundant copy on each keystroke would retain those same plaintext
values and add work. The draft stays in memory until explicit completion, the UI
receives a masked prefix by default, and disk writes happen only after encryption on a
worker. Kotlin/engine/display strings remain subject to garbage collection.
