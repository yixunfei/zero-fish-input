# Second-round review validation

Date: 2026-10-08 (Asia/Shanghai)

## Scope and decisions

The pre-repair workspace was committed as `aa3e4a5` and pushed to `origin/main`.
Direct GitHub connections failed; a one-command override using the already enabled
system proxy completed the push. No persistent Git configuration was changed.
The user then approved verification and repair of all 22 second-round findings.
The approved clipboard behavior uses an independent destructive confirmation;
disabling the feature preserves content and personalization clearing keeps its
stated scope. No persisted format, migration, permission, network path or exported
component changed. Detailed dispositions are in `../CODE_REVIEW_BUGS.md`.

M3 and M7 were not established as reported. Runtime cancellation already finalizes
from the initializing worker on success or exception; this is a source-level
argument, not an exhaustive injected native race test. Actual Rime candidate pages
were verified for every supported size (5/8/10). L7 remains a platform limitation;
L8's alleged per-key force loop is inconsistent with the one-shot retry flag.
L13 passed emulator window tests but still needs physical/vendor acceptance.

## Regression and build results

Four language-pack damage tests were first run against the original loader and
all failed: missing payload, truncation, same-length replacement and malformed
UTF-8 following valid text. After repair all four passed. Other regressions cover
ZIP modes/layout, clipboard deletion/expiry, atomic language selection, AI storage
failure and Unicode previews. Existing deletion concurrency tests execute the same
purge implementation through the authenticated clear entry point.

Executed sequentially from the repository root with JDK 17:

```powershell
./gradlew.bat :language-pack:testDebugUnitTest --tests '*LanguagePackDamageTest' --no-parallel
# Expected pre-repair result: 4 failures.

./gradlew.bat :language-pack:testDebugUnitTest :user-data:testDebugUnitTest `
  :app:testDebugUnitTest :ime-ui:testDebugUnitTest --no-parallel

./gradlew.bat testDebugUnitTest :ai-api:test :engine-api:test :engine-english:test `
  :engine-dictionary:test privacyCheck :app:lintDebug :app:assembleDebug `
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest -PrequireRime=true --no-parallel

./gradlew.bat :app:testDebugUnitTest :engine-rime:testDebugUnitTest `
  :language-pack:testDebugUnitTest :ime-ui:testDebugUnitTest :user-data:testDebugUnitTest `
  privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  -PrequireRime=true --no-parallel

./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug `
  privacyCheck -PrequireRime=true --no-parallel
```

All post-repair commands returned **BUILD SUCCESSFUL**. The full command took
1m 3s, the subsequent affected checks 29s, and the final platform-test/resource
build 11s. Lint reports **No issues found.** Privacy checks include Debug/Release
merged manifests. Three ABI Debug APKs each contain their corresponding
`libzeroinput_rime.so`; there were no JNI or third-party changes.

| Module / task | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| ai-api / test | 10 | 0 / 0 / 0 |
| app / testDebugUnitTest | 211 | 0 / 0 / 0 |
| engine-api / test | 5 | 0 / 0 / 0 |
| engine-dictionary / test | 22 | 0 / 0 / 0 |
| engine-english / test | 12 | 0 / 0 / 0 |
| engine-rime / testDebugUnitTest | 47 | 0 / 0 / 0 |
| ime-core / testDebugUnitTest | 119 | 0 / 0 / 0 |
| ime-ui / testDebugUnitTest | 37 | 0 / 0 / 0 |
| language-pack / testDebugUnitTest | 40 | 0 / 0 / 0 |
| model-scoring / testDebugUnitTest | 20 | 0 / 0 / 0 |
| security / testDebugUnitTest | 10 | 0 / 0 / 0 |
| user-data / testDebugUnitTest | 43 | 0 / 0 / 0 |
| **Total** | **576** | **0 / 0 / 0** |

Counts are from these task-specific XML reports, excluding stale Release reports.
Unchanged suites used Gradle up-to-date/cache outputs; 576 is the checked suite
inventory, not a claim that every case was newly executed.

## Emulator results and initial failures

Device: `ZeroInputModelApi36`, Android API 36, x86_64, `emulator-5554`.
The current main and instrumentation APKs were installed with `adb install -r -t`.
Instrumentation ran with the AndroidJUnitRunner and fixed public fixture text;
no live AI provider requests were sent.

The first group passed **27 tests**. The integration group initially passed 21/24:

- Two page-reference tests failed. Their legacy fixture had fixed pixel padding,
  started capture before window state settled, and assumed a directly visible AI
  entry using the target context's locale. The fixture now honors real system-bar
  insets, waits for idle, opens Tools when needed and resolves labels using the
  actual view context. Original editable/password/sensitive/invisible filtering,
  explicit choice, disabled opt-in and authorization/source revocation assertions
  remain intact. Empty prior accessibility settings are deleted, not written as
  the literal `null` value. A standalone rerun before these fixes also failed and
  is retained in the local logs.
- The 320dp English settings test found `Auto-complete paired symbols` too wide.
  It was shortened to `Auto-pair symbols`; the existing width assertion was kept.

After those changes and three supplemental tests, the final affected group passed
**24/24**. Combining the latest result of each unique case across the three groups
produces **54 passing platform cases**, with no unresolved failure or skipped case:

| Suite | Passing cases |
| --- | ---: |
| AiWorkbenchPanelTest | 8 |
| ChineseOptionsTest | 5 |
| FullscreenInputTest | 3 |
| InputPipelineTest | 2 |
| KeyboardWindowIntegrationTest | 2 |
| LanguagePackJsonPlatformTest | 6 |
| SecureClipboardManagerPrivacyTest | 3 |
| SecurityStorageBoundaryTest | 7 |
| SettingsPanelTest | 7 |
| PageReferenceBindingTest | 2 |
| PageReferencePlatformTest | 2 |
| SecureClipboardVaultReliabilityTest | 7 |

Coverage includes actual Rime page sizes, delayed native handoff preserving the
first letter, floating touch-through, docked/one-hand bottom bounds, repeated
rotation/fullscreen editing, isolated Keystore deletion of both vault keys,
cancellation of the real settings clear confirmation without data deletion,
page-service capture/revocation and current-vs-superseded binding delivery.
The tests do not purge the user's normal vault; the key deletion test uses unique
isolated stores. The new settings test cancels before deletion and verifies that
the live vault generation is unchanged.

AI panel tests render 320dp portrait/640dp landscape, light/dark and 1.0/1.3 font
scale. Public PNG fixtures were pulled locally; enlarged-font page/portrait/light
and context/landscape/dark renders were visually inspected. Controls remain
reachable, with bounded rows and separate scrollable content. Accessibility was
restored to off with no enabled service after tests.

For reproduction, run these comma-separated class groups with:

```powershell
adb -s emulator-5554 shell am instrument -w -r -e class '<class-group>' `
  dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- First: `dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.ai.page.PageReferenceBindingTest,dev.zeroinput.ime.LanguagePackJsonPlatformTest,dev.zeroinput.ime.clipboard.SecureClipboardVaultReliabilityTest,dev.zeroinput.ime.ChineseOptionsTest`
- Integration: `dev.zeroinput.ime.KeyboardWindowIntegrationTest,dev.zeroinput.ime.FullscreenInputTest,dev.zeroinput.ime.InputPipelineTest,dev.zeroinput.ime.SecurityStorageBoundaryTest,dev.zeroinput.ime.SecureClipboardManagerPrivacyTest,dev.zeroinput.ime.ai.page.PageReferencePlatformTest,dev.zeroinput.ime.SettingsPanelTest`
- Final affected: `dev.zeroinput.ime.ai.page.PageReferencePlatformTest,dev.zeroinput.ime.SettingsPanelTest,dev.zeroinput.ime.SecurityStorageBoundaryTest,dev.zeroinput.ime.AiWorkbenchPanelTest`

## Local evidence and limits

Generated evidence is ignored by Git:

- `build/review-round2-red.log`, `build/review-round2-unit.log`
- `build/review-round2-checks.log`, `build/review-round2-final-checks.log`
- `build/review-round2-platform-build.log`
- `build/review-round2-device.log`, `build/review-round2-device-integration.log`
- `build/review-round2-page-rerun.log`, `build/review-round2-device-final.log`
- `build/review-round2-visuals/`
- `app/build/outputs/apk/debug/app-{arm64-v8a,armeabi-v7a,x86_64}-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

The existing `ZeroInputService.kt` remains 1,834 lines, within the 2,500-line
complex-file ceiling. No modified block method exceeded 100 lines. Its existing
lifecycle integration was not split as unrelated refactoring.

Not run in this repair: Release APK assembly, physical ARM/vendor devices,
older Android runtime matrix, live-provider generation and exhaustive native
initialize/close fault injection. Release merged-manifest checks are distinct
from a Release build. Debug artifacts are not release packages. In particular,
L13 still needs gesture/three-button navigation acceptance on representative
physical devices, and L7's old-platform cache limitation remains documented.
Deletion failures can require an explicit retry; deletion-pending state remains
process-local. JVM strings cannot be securely zeroed and native/platform IPC may
outlive a cancellation deadline. No performance improvement is claimed.
