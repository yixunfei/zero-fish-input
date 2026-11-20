# Current code audit repairs (2026-11-19)

Source: [current code audit](2026-11-19-current-code-audit.md), baseline `33ff309`.
The three reported production paths were verified against the current code.
Regression tests first reproduced rejected-commit input loss, pre-resume export
loss and composition insertion at the wrong display position.

## B-01: rejected engine commits

The user approved retaining one pending update with explicit retry/cancellation.
The controller now owns both the failed EngineUpdate and the previous visible
composition. The advanced engine remains frozen, with a single retry candidate
instead of stale engine routes. Candidate, Space or Enter retries that exact
update; ordinary typing, paging, ranking, caret movement and engine adoption do
not drive or replay it. Backspace cancels the entire pending composition.
After acknowledgement, the saved post-commit snapshot is published and learning
runs once. Retry candidate identities change after another rejection.

AndroidEditorConnection distinguishes ACCEPTED, REJECTED and UNKNOWN. A throwing
or missing acknowledgement is UNKNOWN: no retry candidate is offered and even
explicit commit keys cannot replay it. Backspace ends this state without deleting
potentially accepted text. Reset, privacy and session boundaries revoke pending
updates. Feedback is generic and contains no editor/draft content.

LocalDraftInput no longer clears its length-failure flag merely by trying to
finish conversion. Neither character-array nor String submission returns partial
text while composition/capacity failure remains. Successful commits, explicit
cancellation and capacity recovery clear the flag. Caret taps cannot implicitly
retry a failed commit.

Regressions cover the real English engine, candidate/Space refusal, explicit
retry, no learning before acceptance, partial commits with remaining preedit,
stale retry IDs, privacy/reset revocation, a platform write that succeeds then
throws, capacity recovery and caret rejection. Device coverage includes the
actual native Rime and English capacity-refusal paths.

## B-02: expression export lifecycle

The current Activity retains the selected URI until it is resumed and its worker
is idle. Pending picker work runs before a resume refresh, or after an existing
list request completes. The export is bound to its explicit request and the
repository deletion generation; cancellation/deletion revokes it. Worker checks
precede export, opening the destination and writing. Export bytes are cleared.

Recreation retains only an incomplete-export flag, not the URI or request
authority. Failed/interrupted exports warn that the file may be empty or partial.
They never retry automatically. An external document provider cannot be made
transactional, so the user must start a fresh export after an uncertain write.

Robolectric tests cover pre-resume and during-refresh result delivery, importable
output, picker cancellation, Activity recreation, deletion while choosing a
destination and failed destination writes. The fixture cipher is reversible
public test data; production encryption is unchanged.

## B-03: stego composition and caret projection

One field projection inserts formatted preedit at the committed-text caret and
provides its displayed length to DraftTextView. The main text and per-field view
use the same projection. Drawing and touch mapping therefore use the same
composition span, including formatted Chinese spaces. Hidden secret fields
retain masking and expose neither a caret nor preedit metadata.

Regressions cover start/middle/end insertion, emoji boundaries, formatted Chinese
preedit, reveal/mask, real composition completion after a suffix tap, and hidden
or busy touch rejection. English candidate completion intentionally includes its
existing trailing word space; the touch test asserts that existing behavior.

## Verification

All three issues are repaired. The final check command completed with
`BUILD SUCCESSFUL in 5m 39s`: 470 tasks, 55 executed and 415 up-to-date.

```powershell
./gradlew.bat testDebugUnitTest :engine-api:test :engine-english:test `
  :engine-dictionary:test :ai-api:test privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel --console=plain
```

- Current Debug/JVM XML results: 1,179 cases, 1,177 passed, two opt-in live-AI
  credential skips, no failures/errors. This includes 558 app cases (556 passed)
  and 203 core cases. Fourteen app regressions were added. Unchanged modules can
  be up-to-date; these totals do not claim every case was forcibly rerun.
- `privacyCheck`, Debug Lint (`No issues found.`), real-Rime x86_64 Debug APK and
  instrumentation builds passed. Debug and Release main merged manifests retain
  `allowBackup=false`; Release is not debuggable. No permission was added.
- API 37 x86_64: 30 distinct platform cases passed, zero failures/skips. Two
  stego touch cases also passed at 240x480, dark/1.3 font and at 640x320,
  landscape/light/1.0 font: four repeated executions, not four distinct cases.
  Display/font/rotation settings were restored and the task-owned emulator stopped.
- The installed final APK hashes match local artifacts. App Debug x86_64:
  `3e1c37a013676594ab43e9794ba0c56ef465b8b5888fe1eb4249953bb3f19e06`,
  96,765,116 bytes. Test APK:
  `1355c341f87c51c6c79602a99191dfacdc49bfa21b584ffda55961114380ba2d`.
  This is a Debug build, not a signed Release or a new public distribution.

Temporary logs and public-fixture evidence are retained under
`.tmp-evidence/audit-20261119-current/repair-*`; they are not production assets.
The final evidence is `repair-verified-checks.log`, `repair-test-summary.json`,
`repair-device-accepted.log`, `repair-device-small-dark.log`,
`repair-device-landscape-light.log` and `repair-device-summary.json`.
Earlier failing attempts remain: one installed the old distribution-directory APK
against a new test APK; two exposed test-selection/English-spacing expectation
errors. Final tests select visible fields and retain the normal English word
space. The matching final APKs passed the full selected platform suite.

Not performed: physical ARM/OEM or older-Android acceptance, the real DocumentsUI
picker flow, fresh three-ABI packaging or Release APK validation. SAF lifecycle and
round-trip behavior were verified through ActivityResult/Robolectric; external
document providers and device-specific lifecycle behavior remain platform limits.
