# Input and interaction audit (2026-10-30)

## Scope

Reviewed controller commit/fallback paths, candidate routing and touch handling,
draft conversion, glide and handwriting worker ownership, privacy evaluation,
and settings callbacks. Existing architecture, threat model and engine ADR were
used as constraints. This is a bounded source review, not proof that the entire
application is free of defects. No persisted formats, permissions, dependencies
or approved network/data boundaries change.

## Reproduced defects and repairs

1. Raw composition was cleared before editor acceptance. Enter also reset the
   engine before attempting the commit. Rejected commits could erase unfinished
   input; fallback Backspace could then delete preceding editor text, and other
   fallback keys could replace the preedit. Commit now replaces the composing
   span directly; Enter resets only after acceptance. Failed fallback flushes
   stop subsequent insertion/deletion. Regression tests cover retry with more
   typing, Backspace, Space and literal digits. A declined character update that
   already cleared its engine snapshot is not published before the raw flush;
   its additional regression failed before that ordering repair. Successful
   fallback edits still republish the cleared association state.
2. Model-winner publication called the state observer without the isolation used
   by ordinary publication. A display exception escaped the ranking callback.
   Both publication paths now isolate observer failures; conversion remains
   usable after a failing observer.
3. Closing the glide coordinator stopped its worker without closing its decoder.
   Cleanup now runs after cancellation on the owning worker, matching the
   handwriting coordinator. Repeated close is idempotent. A regression checks
   worker identity, exactly one release and removal of pending delivery.
4. Candidate touch release trusted the previous MOVE position. An UP outside
   the item without an intervening MOVE still selected it. Release now checks
   bounds directly. A view regression verifies outside cancellation and a
   subsequent valid selection.

The first five new regression tests failed against the original implementations
before the production changes (three core tests and two application tests).
The sixth reproduced the cleared-update variant during follow-up review.

## Verification

Baseline: `testDebugUnitTest privacyCheck :app:lintDebug` passed. An overlapping
regression invocation failed with a Windows R.jar sharing error; it is excluded
from test evidence. Subsequent Gradle invocations run sequentially.

Final verification passed:

```powershell
./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test --no-parallel
```

- Both commands completed successfully. Combined reports contain 892 tests:
  890 passed, zero failures/errors, and two live-provider tests skipped because
  no explicit temporary test credentials were supplied. All six new regressions
  passed. Lint reports no issues; privacyCheck checked Debug/Release manifests.
- Android API 37 x86_64 emulator: 34 platform tests passed across
  `KeyboardTouchTest`, `KeyboardExperienceTest`, `KeyboardPlacementAndGlideTest`,
  `KeyboardNavigationGestureTest`, `AndroidEditorConnectionTest` and
  `AndroidEditorConnectionFailureTest`. These include 320/800 dp, portrait/
  landscape configuration, theme and height geometry, multi-pointer cancellation,
  glide cancellation, panel navigation and editor exception behavior.
- Installed both APKs using `adb install -r -t`; no uninstall or app-data clear.
  The injected ABI build is test-only and Debug-signed. It is not a distribution
  or Release artifact. Main APK:
  `app/build/intermediates/apk/debug/app-x86_64-debug.apk` (112,749,081 bytes),
  SHA-256 `0cfe42cdd0ede05fed44b3201b9e6c56e12826544a0ad29f8a718b19fefb8896`.
- `git diff --check` passed. Existing AGENTS.md and .codex changes were preserved.

## Remaining verification boundaries

No Release APK or distribution package was produced. Physical ARM/OEM editors,
actual device rotation and real biometric/credential flows were not tested in
this audit. Configuration-based layout tests do not replace these checks.
No JNI/native source changed; native runtime execution here is x86_64, not an
ARM device acceptance claim. The two skipped live-network tests remain unverified.
