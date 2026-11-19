# Steganography usability self-audit (2026-11-19)

Scope: the current repair only, preserving existing unrelated uncommitted work.
Reviewed owners: StegoWorkspace, StegoPanelView, StegoAlgorithmView, StegoOptions,
LocalDraftInput and the existing service commit/selection adapters and crypto
publication/cleanup path. This is a code self-review, not a cryptographic audit.

## Disposition before platform acceptance

- The previous primary flow stopped at preview. The new primary intent captures
  the original editor target before the worker, requires successful self-check,
  consumes PREVIEW before the single commit, then disposes the workspace. It
  never calls Send and cannot authorize automatic decrypted-plaintext insertion.
- The callback review found that an old field/algorithm callback could otherwise
  act after the same workspace was reopened. All panel callbacks now capture a
  workspace generation. Close/reopen rejects stale callbacks; a platform
  regression checks reopening as well as ordinary revocation.
- Inline field views receive only masked secret/credential displays by default.
  Close wipes every text/caret view and callbacks, with no saved view state,
  autofill, content capture, Android editable field or second input connection.
- Checkbox selection accepts only the fixed registry, appends each algorithm at
  most once, preserves valid widths, and rejects empty cascades before KDF.
- Automatic failure consumes the attempt before touching the external editor;
  there is no retry or fallback to plaintext. Selection changes still close and
  revoke publication; settings, lock, vault generation and session policy remain.
- Private masking now projects every committed character to a bullet (bounded to
  32 display units). Only the endpoint maps through the compressed mask; explicit
  reveal restores positioning. Old visible-suffix platform expectations were
  updated without relaxing stale/busy/grapheme guards.
- Cryptographic envelope, vault format, key aliases, authentication, private-save
  confirmation, permissions, network and system-clipboard boundaries are unchanged.
- Dead cascade-dialog code and unused resources were removed, not exempted from
  Lint. Function/file sizes remain within project limits.

No unresolved blocking finding remains after these corrections. Device acceptance
and the final build/privacy/Lint results are recorded separately in
[text-steganography-validation](../text-steganography-validation.md).
