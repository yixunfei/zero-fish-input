# Clipboard product repair plan

## Revised entry design (2026-10-31, pending capture-boundary approval)

The user requests OCR efficiency and package evaluation before choosing a capture
strategy. See [measured feasibility](ocr-feasibility-2026-10-31.md); this is not
approval to implement production screenshots/OCR.

The current user request retains private copying and rejects system clipboard
body collection. QQ, WeChat, LINE, social feeds, messages and web/article bodies
are primary target scenarios, not optional edge cases. Their customized menus
make PROCESS_TEXT alone inadequate. An incognito warning is an immediate repair,
not acceptance of the page-copy requirement.

Proposed replacement interaction:

1. After dedicated setup, provide a persistent private-copy accessibility overlay
   button while the user has enabled the tool. Do not wait for selection events,
   read selection events, click the source Copy command or inspect the clipboard.
2. A tap binds a single foreground application window and starts one bounded
   capture. Collect exposed, visible, noneditable and nonsensitive text only on
   that explicit request. Show a protected ZeroInput selection surface where the
   user chooses the actual range; source selection menus play no role.
3. For self-drawn pages, offer an explicit single-frame target-window capture and
   local OCR path within the same tool. Do not silently capture when node reading
   fails. This is a new capture boundary awaiting explicit approval, not part of
   the existing AI page-reference authorization. No network OCR, image persistence,
   clipboard fallback or AI access. System secure-window protection remains binding.
4. Authenticate and save through the existing vault flow. Read no existing vault
   text merely to collect a new draft. Capture buffers, nodes and recognition
   results have bounded lifetimes and clear on cancellation, lock, timeout,
   configuration change or target-window invalidation.

Keep this independent of AI page references/device-agent authorization and the
metadata-only clipboard guard. A new accessibility capability needs a dedicated
disclosure, ADR, manifest/privacyCheck review and negative tests. Evaluate a
fixed-version offline OCR model and its license, accuracy, resource cost and
runtime behavior before selecting a dependency; existing ONNX infrastructure is
not evidence that a suitable OCR model is already included.

Acceptance must include a fixture app with custom menus that omit PROCESS_TEXT,
an exposed-text page and a self-drawn text page. Test prompt availability without
an IME/editor, no selection notification, repeated captures, cancellation, secure
windows and changed windows. Then validate actual target app/version/device
combinations using public fixtures. No physical target or exact OEM build has
yet been supplied; an emulator fixture cannot establish QQ/WeChat/LINE support.

Immediate implemented subset: incognito enable confirmation and denial of AI
extraction from private editor-selection drafts. Progress and validation are
tracked in `project-progress.md`. The broader entry redesign remains unimplemented.

Status: ordinary-app repairs and approved two-stage paste implemented and emulator-validated.
Vendor history deletion remains unresolved. The user allows supported vendor
interfaces but excludes root, Shizuku and system-permission workarounds.

## Report and acceptance gap

The latest report (2026-09-09) identifies an iQOO Neo9 running Android 16.
Items remain in the clipboard page opened from an input field's long-press
menu. Copy to ZeroInput is missing from most source applications, or appears
deep in the menu overflow. The precise OriginOS build, history owner and source
application versions are not yet known. No physical device is currently
connected to this workspace.

The requested outcome is usable private copying and effective automatic cleanup,
including the reported history, without requiring the user to delete entries
manually or select a particular keyboard just to clear them. Current-item tests
and revised warning text do not satisfy that outcome. History cleanup remains
an open requirement until its actual owner and deletion capability are verified.

## Vendor interface investigation

The public vivo documentation tree at
<https://dev.vivo.com.cn/webapi/doc/tree> contained 628 entries on inspection.
The one clipboard-titled entry, document 959, is a clipboard **read permission**
policy (<https://dev.vivo.com.cn/webapi/doc/info?id=959>). It does not specify a
history-deletion operation. No history-deletion document was identified in that
catalogue. This is limited public-documentation evidence, not proof that no
private or partner-only OriginOS API exists. No such API was guessed or invoked.
The reported menu's owning component and exact OriginOS build still require
target-device identification before any vendor integration can be implemented.

## Defects verified before repair

| Finding | Evidence | Consequence |
| --- | --- | --- |
| The keyboard has no copy-selection command | `ime-ui/.../SecureClipboardPanelView.kt` offers saved items and management only; `KeyboardAction.kt` has no copy-selection action | A source application's optional selection menu is the only selection-copy entry |
| Foreground inspection shares the default-IME gate | `app/.../clipboardguard/ClipboardGuardRuntime.kt`, `mayAccess`; `ClipboardGuardSettingsView.kt`, `render` | A focused ZeroInput page rejects requests that Android can allow without default-IME identity |
| Startup ignores preexisting contents in automatic mode | `ClipboardGuardSession.start` records the timestamp; a unit test explicitly expects no clear | Enabling automatic cleanup can leave the existing current item intact |
| Platform cleanup targets the current primary clip | Android 16 `ClipboardService.clearPrimaryClip` sets the primary clip to null | It does not establish erasure of a vendor or keyboard history store |
| Metadata can be unavailable without an exception | Android 16 read access checks may return null | Null metadata alone cannot prove successful deletion |

Android 16 source reference:
[ClipboardService.java, android-16.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java).
The focused-app read allowance and background default-IME allowance are distinct.
AOSP evidence does not verify additional OriginOS behavior.

## Proposed implementation

### 1. A visible private-copy action in the keyboard

- Add a visible copy-selection control to the private clipboard panel. Opening
  the panel must preserve the editor selection. No source menu search is needed.
- On an explicit tap, capture the active editor identity, selection and request
  generation. Read only that selection through the editor connection, with a
  bounded worker and at most one outstanding request. Reject missing, empty,
  oversized or malformed selections and sensitive/unknown editors.
- Validate both the observed selection length and the returned text against the
  vault's existing 8192 UTF-16-unit limit; strip spans. Discard late results after
  selection, session, settings, panel or service changes. Do not fall back to
  reading the system clipboard or surrounding text.
- Present authentication and explicit save through a secure, app-owned flow.
  Define the transition into that flow as a one-use ownership transfer: an
  unrelated session change must not authorize another selection. Keep drafts
  bounded, cancellable and memory-only; clear mutable buffers on all exits.
- Reuse the vault's authentication, serialized writes and deletion-generation
  checks. No new persistent format, key alias or compatibility layer is needed.
- Retain PROCESS_TEXT and plain-text sharing for noneditable text. Audit failures
  in actual source apps; a receiving app cannot force third-party menus to include
  or prioritize its action. Do not broaden accepted attachments to hide failures.

### 2. Correct current-item cleanup

- Separate foreground operation authorization from background monitoring.
  A focused, resumed ZeroInput page can request cleanup with another IME selected.
  A foreground request carries its own short-lived lease; the monitoring worker
  must not inherit it. Focus loss, lock, settings changes and cancellation revoke it.
- Keep background monitoring conditional on platform eligibility. Removing the
  check from all operations would not grant background access on Android 16.
- Extend the explicit automatic-mode choice to cover an already-present current
  item as well as subsequent changes. State that destructive effect in the mode
  confirmation. When automatic protection becomes eligible again, inspect the
  current item rather than silently treating it as an ignored baseline.
- Merely enabling reminders or monitoring must not enable deletion. Authentication
  continues to require confirmation mode. Use events and bounded retries; do not
  introduce a polling loop, clipboard payload reads or random overwrites.
- Revalidate request generation, access, foreground lease when applicable, and
  current metadata immediately before clearing. Keep the compare/clear race and
  unavailable metadata explicit in the internal result model. Never report that
  independent history was cleared based on the current-item result.

### 3. Resolve the iQOO history requirement against its actual owner

- Identify the component/package presenting the reported long-press clipboard
  page and its installed version, plus the exact OriginOS build. Start with
  component metadata; do not collect real history text, screenshots or UI dumps.
- Check for a supported, authorized deletion operation that clears only clipboard
  history. Verify its behavior with isolated public fixtures, including ordinary
  entries, pinned entries and reopening/restarting the owner.
- With ordinary app permissions, there is no universal Android API for erasing
  another app's private history. Shizuku/ADB shell access is not root, accessibility
  does not grant private storage access, and root does not establish cloud erasure.
  Evaluate additional capabilities only within the user's chosen privilege scope.
- A device-specific integration requires a concrete API, authorization model,
  bounded failure behavior and negative tests before implementation. A global
  reading service, blanket app-data deletion or manual-deletion instructions
  cannot substitute for this requirement. Do not assume a vendor API exists.
- If no viable interface is available, record the unsupported target accurately;
  do not mark the overall history requirement complete. Preventing new system
  clipboard writes via private copying is useful but does not erase old copies.

## Module responsibilities and security review

`ime-ui` owns controls and user intents. `app/input` owns verified selection
tracking; `app/clipboard` owns the bounded reader, draft transfer and authenticated save.
`app/clipboardguard` owns foreground/monitoring leases and cleanup policy.
`ZeroInputService` remains lifecycle wiring rather than owning another large
workflow. Existing `user-data` and `security` contracts should be reused.

The additionally approved two-stage paste is defined in ADR 0012. An application
coordinator retains no plaintext or connection across authentication. A returned
user confirmation binds the new connection for a cancellable read; the vault
rechecks the read's deletion generation and cancellation under its existing lock.
This changes no stored format and adds no permission.

Before production changes, update the reviewed decisions in ADR 0006/0007 (or a
new ADR where needed), architecture, threat model, SECURITY and README. Amend
the root collaboration rule that currently requires default-IME identity for
foreground cleanup once the new foreground boundary is approved. Preserve
zero runtime network access, no system-clipboard body reads, default-off active
features, secure windows, one-use authorization and generation invalidation.
Any privileged history integration needs a separate concrete security review.

## Acceptance and verification

1. Reproduce the missing keyboard action and ignored initial current item in
   regression tests before changes. Add foreground cleanup with another IME
   selected, without making its background monitor eligible.
2. Verify selected-text copying without changing the system primary clip, followed
   by authenticated save and paste. Cover editor switches, selection changes,
   oversized responses, duplicate callbacks, cancellation, worker rejection,
   disabled vault, settings changes, process recreation and concurrent vault clear.
3. Verify preexisting/current-item automatic cleanup, disabled monitoring,
   authentication denial, device lock, focus loss during a queued operation,
   stale requests, new contents and explicitly denied platform access.
4. Run module and clipboard regressions, `privacyCheck`, Debug/Release merged
   manifest checks, `:app:lintDebug`, and Debug/instrumentation builds with actual
   Rime and `-PrequireRime=true`. Verify copy controls across small/large screens,
   rotation, themes and supported Chinese/English resources.
5. On the iQOO target, independently check ordinary paste and the reported history
   page. Use public fixtures, not the user's existing records. Verify private-copy
   usability in representative actual source apps and history persistence after
   reopening. Record precise component/build versions and remaining restrictions.

Implementation results are recorded in the device validation document; the plan
itself does not establish iQOO history deletion.
Prior emulator results in [clipboard-device-validation.md](clipboard-device-validation.md)
and [model-integration-validation.md](model-integration-validation.md) remain
evidence only for the specific behavior they exercised.

Final ordinary-app evidence: 17 real-credential/vault/layout instrumentation
cases, 51 clipboard/input/encrypted-store regressions and 352 JVM tests passed.
Privacy, Lint, merged manifests and an ARM64 Debug test package passed validation.
The history requirement is not included in those successes and remains open.
