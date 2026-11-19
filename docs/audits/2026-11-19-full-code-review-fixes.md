# Full code review verification and fixes

Date: 2026-11-19. Source: [static review](2026-11-19-full-code-review.md).

## Baseline and scope

All existing tracked/untracked source and documentation changes were committed
before implementation as `0c1c433` (`chore: backup before full code review fixes`).
The local branch `zn/backup-full-review-20261119` preserves that baseline.
Ignored build caches, SDK configuration and local evidence were not committed.

The user confirmed that missing device-agent categories must grant nothing,
while existing explicitly saved selections remain unchanged. No implicit
recommended grants, migration, new permission, persistence format or dependency
was introduced. Work was performed serially without delegated agents.

## Item-by-item disposition

| Item | Verification | Resolution |
| --- | --- | --- |
| H-1 | Confirmed: missing preferences returned every category, including observation. | Default to an empty set. Preserve explicit empty/subset/full grants. Opening settings and enabling the agent do not populate categories. Regression covers defaults, enablement, reload and unknown names. |
| M-1 | Not reproduced; the stated algorithm failure is incorrect. The display loop continues after a nonmatching separator. Validated spelling and the raw suffix form a complete raw subsequence, so every raw offset is visited. The reading annotation is appended after the editable span and touch offsets are clamped to that span. | Retain implementation. Platform probes exercise all 11 raw offsets of `nihaoxiang` displayed as `ni hao \u00b7 xiang`, both with and without a different reading annotation, via touches and accessibility selection. |
| M-2 | Not reproduced in production. `ZeroInputService.searchDraft` synchronously passes `text + state.snapshot.composition` together with that same state. `LocalDraftInput.emit` calls this callback synchronously; no independent draft/state delivery exists here. Thus the composition is always a suffix and cannot exceed the combined argument length. | Retain implementation. Platform probe verifies committed `AB` with longer formatted preedit `ni hao`, insertion at the middle caret, and removal of the preedit. |
| M-3 | Existing final `execute` already calls `DeviceActionArguments.valid`. The arguments are `Map<String, String>`, so the report's proposed `as? Number` would reject valid coordinates. Current valid calls cannot throw at the cited conversions. | Defense in depth: use nullable lookup and `toIntOrNull` at all four use sites. Keep the shared range/required-field check. Negative tests cover each missing coordinate, non-numbers, fractions, integer overflow and values outside `0..100000`. |
| M-4 | Confirmed: a personal-data refresh rebuilt the unranked snapshot and reset `modelRanked`. Preserving the flag alone would still lose the order. | Personal-data-only publication reapplies the winner by candidate ID, rebuilds native selection routes and keeps the flag. New personal priority, dictionary edits, browsing, privacy loss, changed input and explicit clear retain precedence. Tests verify all three candidate routes after refresh and revocation by personal priority or engine input. |
| L-1 | An empty/single-message direct append creates invalid selected indices. The current production caller appends a complete user/assistant pair, so this is a latent boundary defect. | Only include a complete new pair; retain previous selections only when rebased indices remain in the new transcript. Regression covers empty/single append and complete-pair rebasing. |
| L-2 | Not a reachable defect. All `MessageCipher.keyBits` lists are nonempty enum constants and `MessageStage.init` rejects unsupported widths before an option can exist. No old persisted stage bypasses this constructor. | Retain strict invariant and current cycling. Do not add a silent invalid-width fallback. |
| L-3 | No demonstrated continuity failure. With no visible laid-out button there is no visible anchor to preserve; `right == 0` is not selected when `scrollX >= 0`. A pending anchor already survives repeated renders before layout. A valid first button has `left == 0`. | Retain implementation. The suggested `left > 0` condition would drop the valid first-button anchor. Existing continuous-scroll/window/reset platform tests are included in verification. |
| L-4 | Not a defect, as the report itself acknowledges. `draftSummaries()` is empty without `draftEntries`; metadata/removal branches cannot enter in that case. Private-draft addition deliberately asserts the supported constructor contract. | Preserve assertions. Returning zero or silently skipping removal would hide a broken invariant or claim a successful operation without doing it. |
| L-5 | The alleged concurrent enqueue is absent under current ownership. Requests, invalidation and close use the main owner; the handwriting debounce also runs there. The private worker receives no other submissions. Background glide revocation changes only an atomic generation. Cancel/purge therefore frees the sole queued request before close enqueues destruction. | Keep destruction on the same worker after cancellation. Synchronous fallback could race an active native operation and block the IME thread. Existing glide release-once/owner-thread and handwriting cancellation/release tests are included. |

Two reachable production defects are fixed (H-1/M-4); two local boundaries are
hardened (M-3/L-1); six reports have no supported failure path under the present
contracts (M-1/M-2/L-2/L-3/L-4/L-5).

## Regression evidence

Before production changes, the new tests failed on missing-category defaults,
ranking retention and incomplete append indices. The targeted suite then passed
after the fixes. Tests also retain the earlier personal-priority behavior: a
newly learned candidate must override a previous model winner.

## Validation completed

The following root command passed with real x86_64 Rime sources:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-english:test :engine-dictionary:test :ai-api:test privacyCheck `
  :app:lintDebug :app:processReleaseMainManifest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

- Across the 12 requested module test directories: 1,165 cases, 1,163 passed,
  two opt-in live-provider cases skipped, zero failures/errors. Counts are for
  `test`/`testDebugUnitTest` only, excluding stale Release or Android-test reports.
- `privacyCheck` (including `testPrivacyBoundary` and both Debug/Release merged
  manifests) passed. Debug lint reports `No issues found.`
- Debug and instrumentation APKs built. Current AGP installation artifacts are
  `app/build/intermediates/apk/debug/app-x86_64-debug.apk` and
  `app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`.
  Installation requires `adb install -r -t` for these test-only builds.
- Isolated API 37 x86_64 emulator: 24 selected platform tests passed, no failures
  or skips. These include the three audit reproduction probes,
  `CandidateContinuousScrollTest`, `CandidateWindowContinuityTest`,
  `CandidateViewportResetTest`, `LocalDraftCaretPanelTest` and
  `handwriting.HandwritingCoordinatorTest`.
- A final settings-page regression was then added, rebuilt and checked with
  Debug lint. Opening the real `DeviceAgentSettingsActivity` with the preference
  absent passed without writing or granting categories. This makes 25 unique
  platform cases passed. The test restores the previous preference, including
  preserving its absence or exact stored values.
- Existing glide unit tests pass, including closing exactly once on its decoder
  worker. Handwriting platform tests verify cancellation, late-result rejection
  and release.
- `git diff --check` passed. The task-started emulator was stopped after testing.

Local logs remain under ignored `.tmp-evidence/full-review-*.log`. The prior
targeted failure logs are intentionally retained as regression evidence.

## Limits

No physical ARM/OEM or older Android runtime was tested. The opt-in live-provider
cases were not enabled. No new ARM APK, three-ABI rebuild or Release APK was
produced in this task; native code and packaging rules are unchanged. Passing
the Debug checks and Release merged-manifest check does not establish a signed
Release or full device-agent/live-provider acceptance.
