# Stego paste, master material and layout self-review

Scope: the latest explicit two-field paste, variable master material and compact
layout request. Existing uncommitted work is retained. This is a code self-review,
not an independent cryptographic audit.

## Review before platform acceptance

- System payload access is isolated to StegoSystemPaste and its exact privacy
  allowlist. Only a live COVER/CARRIER source-menu action begins one source-worker
  read. Reject multiple items, nontext MIME, URI/Intent, NUL, malformed Unicode and
  field-specific limits without coercion or truncation. No listener, system write,
  automatic collection, AI route, permission or exported component was added.
- The worker lease closes on workspace/session/settings/lock/deletion expiry;
  main-thread publication rechecks the actual current workspace. A late read past
  its shorter source deadline returns to editable failure instead of hanging in
  WORKING. Dialog source actions are consumed before invocation to reject replay.
- Vault source selection retains fresh system authentication and a separate
  foreground confirmation. Navigation wipes secret drafts and credentials. The
  review found the need to carry a validated detached ciphertext when pasting its
  exact cover; only that encrypted block and public target/editor identity may
  accompany navigation. No old InputConnection or plaintext draft is retained.
- A one-use, 30-second read-result buffer populates a newly opened isolated
  workspace for the matching eligible editor after an explicit Stego tap. Other
  editor starts, selection changes or continued interaction revoke it, as do
  settings/lock/deletion/timeout. It cannot write an external editor. Standalone
  source flows remain local and return no data to callers.
- The final lifecycle review found that ordinary IME teardown and engine adoption
  could cancel that buffer before its first editor return. A new unbound/bound
  phase preserves only the first return and engine preparation; matching input-view
  binding then makes subsequent leave/selection/input revoke it. Default private
  input does not preempt this pending explicit return. A real IME/authentication
  round-trip regression supplements the coordinator-only test.
- Random material generation runs on the crypto worker; completion checks both
  operation token and workspace generation. Owned returned characters are cleared
  even for a missing owner, rejected post, stale result or closed workspace.
  Canonical Base64url accepts only 32..512 decoded bytes. Creation allows
  256..4096 bits in multiples of 8. Cipher subkeys and HKDF-SHA256 stay unchanged;
  neither longer material nor cascades claim additive or above-256-bit strength.
  Existing 32-byte roots and serialized envelope/vault formats are unchanged.
- UI remains focus-free with no platform editor, native selection menu, autofill,
  content capture or saved state. Fields precede credentials and cipher selection;
  primary execution stays fixed below the scroll area. Optional settings and old
  standalone source buttons are secondary. All command targets remain 48 dp.
- The visual review caught awkward English label breaks at 240 dp and 1.3 font
  scale. Buttons now use zero letter spacing, 13 sp labels and no vertical inset;
  mode names are concise. Explicitly revealed long credentials expand within the
  bounded scroll area, so a generated 4096-bit key is not clipped to three lines.

No blocking code finding remains after these corrections. Final runtime, layout,
build and packaging results are recorded in the validation record; failed initial
test harness runs are retained as historical evidence and are not counted as a
pass. Remaining platform limitations must be stated with the delivery.
