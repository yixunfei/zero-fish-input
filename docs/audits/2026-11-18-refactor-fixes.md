# Refactor review disposition (2026-11-18)

Source: [review](2026-11-18-refactor-review.md).
Baseline backup: `2c86668` (`chore: backup refactor review baseline`).
The user approved the implementation choices before changes were made.

## Disposition

Sixteen findings are addressed; D-5 is withdrawn after source and regression
verification. No persisted format, permission, exported component, network route
or system-clipboard exception was added.

| ID | Result and regression evidence |
| --- | --- |
| A-1 | Separate unavailable query answers from unproven edit metadata. Prepare immutable suppression sets on the worker; keep verified pages while answers load. Unproven edits hide candidates; Space commits raw input plus a space. Paging, raw commit, invalidation and no-I/O cache-miss tests cover the behavior. |
| B-1 | Decrypt options show fixed carrier-defined credential/KDF parameters. Preparation takes authoritative parameters from the carrier. Platform coverage verifies the unavailable credential selector. |
| B-2 | Finish carrier composition before validation and revalidate the actual owned worker buffer, including after a cost-confirmation dialog. Platform coverage rejects an invalid pending composition before work starts. |
| B-3 | Allow up to 120 seconds for the bounded cancellable file read and show a distinct timeout message. A public provider delayed by 5.5 seconds completes; existing cancellation, length and temporary-grant tests remain. |
| B-4 | Keep the pre-authentication draft within a 120-second host budget. Start a new maximum 30-second confirmation deadline on authentication success, without extending the grant. Construct the write buffer only on the explicit confirmation click. Cancellation offers retry within the draft budget. Public system-PIN tests cover a 16-second pre-authentication wait plus a 16-second post-authentication wait, no callback write, cancellation and revocation. |
| B-5 | Clear partial output and deliver a memory failure for OOM; publish failure and release owned credentials before propagating other fatal errors. Injected OOM and fatal-error tests cover cleanup and propagation. |
| C-1 | Hide the inactive tools button while the steganography panel is open; assert its visibility in the panel test. |
| C-2 | Carry the recognized-carrier flag into the review view and consumption model. Ordinary text cannot consume the decrypt branch; recognized or damaged carriers still cannot reach AI. |
| C-3 | Explicitly restore actions and carrier visibility whenever review is shown. Platform coverage repeats review/busy/clear transitions and ordinary-text review. |
| C-4 | Both source launch sites catch launch rejection, discard only their matching handoff and report failure. Handoff tests verify immediate buffer clearing and preservation of a newer token. |
| D-1 | Skip overlong dictionary scalar values with bounded memory while validating their entire syntax. Manifest values and all object keys retain hard limits. Tests include 100,000-character strings, oversized numbers, malformed escapes/structure/exponents, following valid entries and strict manifest limits. |
| D-2 | Migrate the named warm-up, delivery and settings-listener paths to the recoverable policy. Cleanup runs before cancellation, interruption or fatal errors propagate. Regression tests cover startup, post and readiness failures. This is a scoped migration, not a claim that all remaining runCatching calls were audited. |
| D-3 | Retain the small module-local helpers and document their deliberately synchronized policy. This avoids a new public cross-module API for an internal exception helper. LinkageError handling is confined to engine operations. |
| D-4 | Retain the Lazy handle and destroy the handoff only if initialized, so close does not register a new listener. |
| D-5 | Withdrawn. When the executor rejects a clear control task, enqueueClear synchronously completes it and returns null. The Elvis return in clearAndAwait is reachable and correct. Preserve it with a clarifying comment; the existing rejection regression covers this path. |
| E-1 | Wrap only engine-call LinkageError as an engine operation failure; tolerate native-link cleanup failure during fallback. Other fatal errors still propagate. A regression injects UnsatisfiedLinkError from handle and close, verifies raw preedit/current-key delivery and subsequent input. |
| E-2 | Publish client/response with volatile visibility and check cancellation after client/response publication and at send entry. A 40-iteration cancellation/preparation race verifies shutdown and an empty pool without sending any request. The test uses an internal adapter observation, preserving the network gate. |

The device selection fixture additionally waits for the IME's selection
acknowledgement. Main-looper idle alone does not drain editor-to-IME Binder
updates: opening a protected panel before that acknowledgement lets the late
selection change correctly revoke it. No production invalidation rule was relaxed.

## Second self-check (2026-11-18)

Two additional gaps were reproduced and repaired within the approved policy:

1. The first LinkageError repair covered key handling and controller close, but
   reset/startup/prepared-result cleanup still used the ordinary exception helper.
   A reset failure after Enter or a personal/raw commit could escape after the
   editor had already accepted text. The core now has an explicit engine-only
   recovery helper: construction/startup, restore, normalization, reset and owned
   cleanup tolerate LinkageError; cancellation, interruption and other fatal
   errors propagate. Post-commit cleanup closes the engine without invoking the
   key-replay fallback. Six new regressions cover accepted Enter without a second
   commit/Enter, raw Space, personal selection, reset/privacy tightening, startup,
   one-use prepared disposal and nonrecoverable reset failures. Before repair,
   five of those six tests failed with UnsatisfiedLinkError; afterward all pass.
2. StegoHandoff.take used to close its current request whenever an old or missing
   token arrived. A delayed old launch could therefore erase a newer save draft.
   Token mismatch now only rejects the caller; matching-token expiry/generation
   failure still clears the draft. A new regression reproduced the overwritten
   buffer before repair and passes afterward; a second regression preserves the
   deletion-generation rejection and clearing requirement. No authentication,
   lease duration or export boundary changed.

The source review also rechecked suppression snapshot invalidation, pending-page
revision ownership, bounded scalar skipping, source-worker late results,
authentication/confirmation separation and transport cancellation publication.
No additional confirmed defect was found in those inspected paths. This is a
scoped self-review, not an independent audit or a proof of all interleavings.

Self-check evidence is under .tmp-evidence/refactor-selfcheck-*. The before logs
record real regression failures; the first handoff fixture run lacked the
AndroidX private-receiver test permission, which was supplied only in Robolectric
before reproducing the token defect. No production permission was added.

The complete self-check command passed:

```powershell
./gradlew.bat testDebugUnitTest :engine-api:test :engine-english:test `
  :engine-dictionary:test :ai-api:test privacyCheck testPrivacyBoundary `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug `
  :app:lintRelease :app:assembleRelease --offline --no-parallel --continue `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

The selected XMLs contain 1,147 executions: **1,145 passes, two opt-in live-AI
skips and no failures/errors** (app 532, ime-core 199; other suites unchanged).
privacyCheck, its negative self-tests, real-Rime x86_64 Debug/AndroidTest/unsigned
Release builds and both Lint variants pass; both Lint XMLs contain zero issues.
No JNI/CMake/native source was modified. Matching merged-manifest privacy
boundaries remain checked. The existing size exceptions noted below are unchanged.
After making the Robolectric receiver permission follow the active application
ID, the two handoff regressions also pass under both Debug and Release. The
complete app Debug suite, privacyCheck and both Lint variants were repeated for
that final fixture change and pass. Those two selected Release tests are extra
executions and are excluded from the 1,147-suite total above.

| Current self-check artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| x86_64 Debug | 97145471 | 8d261deae9716368b101e80a7c5029dd1482d0116aae7090e657c7d0d24d3b7f |
| x86_64 unsigned Release | 84295642 | 5e38b7f77c8f72ee1771cad19dbff5aa9467b47e7ffc47e15a9fb07a2df609fa |

These artifacts are under app/build/intermediates/apk and supersede the first-pass
identities below. Neither was installed during this pass. Two attempts to boot
the task-owned ZeroInput_Clipboard_P1 AVD failed with WHPX partition setup error
0x80070005 (access denied); accel-check nevertheless reports WHPX installed and
usable. Both attempts exited, adb reports no devices, and no PIN/device setting
was changed. The earlier 35 platform passes are evidence for the preceding source
state only. These two new fixes have deterministic host regression evidence;
device rerun, older APIs/OEM/ARM and native fault injection remain unverified.

## First repair verification

The final commands run from the repository root, using real Rime sources:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test `
  :user-data:test :model-scoring:test privacyCheck testPrivacyBoundary `
  :app:lintDebug :app:lintRelease :app:assembleRelease `
  --offline --no-parallel --continue `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

The first full run found the new test's direct transport-library reference in
privacyCheck. That reference was removed; the gate and its negative self-tests
were preserved. The affected app tests, Debug/AndroidTest/unsigned Release builds,
privacyCheck, testPrivacyBoundary and both Lint variants were rerun successfully.
The final AndroidTest-only fixture update also passed privacyCheck and rebuilt.

Current Debug-module XML results: 1,049 executions, no failures/errors, two
opt-in live-AI skips. Four pure JVM modules add 90 passes: **1,137 passes and two
skips** across these selected suites. Both Lint reports contain zero issues.
User-data is an Android module: its :test aggregate also runs its unchanged
Release suite; older unrelated Release XMLs are excluded from these totals.

Builds use x86_64 and require real Rime. Both merged manifests keep
allowBackup=false, unchanged permissions, and a private source excluded from
recents; Release is not debuggable. The Debug signature verifies as Android Debug.
Actual current APKs are under app/build/intermediates/apk; older files in
app/build/outputs/apk are not this run's evidence.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| x86_64 Debug | 96842800 | b7815be70e867aec4a3e1c3f31882bf3d44fee916473ec9a192d14fcd91faaba |
| x86_64 unsigned Release | 84295530 | 4494431a1e365d887d84ad6a17a34971653953e54f9e1f60e41c902417bccc82 |

Device results and cleanup are recorded in the
[validation addendum](../text-steganography-validation.md#refactor-review-follow-up-2026-11-18).
The installed Debug hash matches the table. Evidence logs are local ignored files
under .tmp-evidence/refactor-review-*; no sensitive samples are committed.

## Limits

This run does not rebuild ARM native variants or rerun native-symbol fault
injection; the Kotlin boundary uses a deterministic LinkageError regression.
No JNI/CMake/native source changed. API 26/29/33, OEM/ARM64 runtime, actual
external-channel round trips, cloud-provider UI and a real process-wide OOM remain
unverified. The OOM fallback is fault-injected at the delivery computation boundary.
No live provider request was sent. No new cryptographic proof or audit is claimed.
Android text widgets and required String bridges still create non-erasable
temporary String copies; mutable buffers are cleared and no disk cache is added.
The unsigned Release is validation output, not a signed release artifact.

Existing size exceptions remain: ZeroInputService is 2,311 lines (2,306 at the
backup) and InputSessionControllerTest is 1,600 (1,576 at the backup). This repair
adds a small launch cleanup and one regression using its existing private fixture;
it does not expand the service's responsibilities. These files remain harder to
review; future lifecycle/fixture work should extract coherent responsibilities
rather than mechanically split them during this scoped repair. Changed-document
relative paths and git diff --check pass.
