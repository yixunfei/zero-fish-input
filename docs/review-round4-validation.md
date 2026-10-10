# Fourth review verification (2026-10-10)

## Scope and decisions

The user approved fixes for A1/A2/B5/B6/B7/B8, evidence-based treatment of B3/B4/B9,
and retaining manual AI models, the current keyboard layout and P1-P5 observations.
Seven report items were addressed including the concrete dialog race found under
B4. B1/B3/B9 were clarified against the ADR and actual control flow. Existing
uncommitted changes were preserved; no commit, push or publication was performed.
See [current dispositions](../CODE_REVIEW_BUGS.md).

Stored formats, model bytes, library bytes, permissions, network destinations,
one-use grants and generation protections remain unchanged. Clipboard failure is
generic status only; the unauthenticated list never reads body data. Authenticated
management repairs mismatched or unavailable indexes, not every successful read.

The existing `ZeroInputService.kt` grew from 1843 to 1847 lines, above the general
1500-line target but below the 2500-line ceiling. The four added lines carry panel
failure status through existing lifecycle/revision ownership. Splitting service
ownership as part of this correction would enlarge the review and concurrency risk;
a separate service-coordinator extraction remains the appropriate follow-up.
New helpers/tests remain below 100 lines per method and 1500 per file.

## Host validation

From the repository root (Windows PowerShell):

```powershell
./gradlew.bat testDebugUnitTest :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test `
  privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:processReleaseMainManifest :model-scoring:verifyReleaseRuntimeArtifact `
  :engine-rime:verifyReleaseXzRuntime -PrequireRime=true --no-parallel
python -m unittest discover -s tools/resources -p test_resource_integrity.py
python -B tools/resources/prepare-mini.py
python tools/resources/verify-apk.py full app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
python tools/resources/verify-apk.py full app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
python tools/resources/verify-apk.py full app/build/outputs/apk/debug/app-x86_64-debug.apk
```

Gradle: BUILD SUCCESSFUL. Lint: No issues found. `privacyCheck` passed.
Both debug and release ONNX/XZ tasks ran against their own configurations.
Final XML counts (up-to-date/cache results included, not every test reexecuted):

| Module | Tests | Failures/errors/skips |
| --- | ---: | --- |
| app | 225 | 0/0/0 |
| user-data | 90 | 0/0/0 |
| security | 20 | 0/0/0 |
| ime-ui | 70 | 0/0/0 |
| ime-core | 240 | 0/0/0 |
| engine-rime | 97 | 0/0/0 |
| model-scoring | 40 | 0/0/0 |
| language-pack | 88 | 0/0/0 |
| engine-api | 5 | 0/0/0 |
| engine-english | 12 | 0/0/0 |
| engine-dictionary | 30 | 0/0/0 |
| ai-api | 10 | 0/0/0 |
| Total | 927 | 0/0/0 |

Python: 3 tests passed. A tampered library plus a matching generated stamp is
rejected; missing, mismatched and unsupported runtime ABI sets are rejected.
The unchanged local AAR, six native library files and AAR members match the new
source lock. The source checkout is clean at its pinned revision, and the operator
config matches. Calling the packager against existing native outputs reproduces
the locked AAR. These are validated existing binaries, not fresh native rebuilds.

Mini was also exported into an isolated build directory before running the new
entry point. Both exports reproduced graph SHA-256
`5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446`.
All three public archives were prepared in `build/review-resource-package`;
the resulting catalog is structurally identical to the reviewed source catalog.
The source inputs were already cached and revalidated; this was not a fresh
network-download test. No Release download or publication was needed.

All three full Debug APKs passed the archive verifier, including XZ tables and
locked runtime/JNI bytes. Debug/Release main merged manifests both disable backup
and cleartext; Release is not debuggable. The exported component set is unchanged.

## Platform validation

On the connected x86_64 emulator, install the Debug and test APKs, then run:

```powershell
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.zeroinput.ime.settings.AiConnectionDialogLifecycleTest,dev.zeroinput.ime.clipboard.SecureClipboardVaultReliabilityTest,dev.zeroinput.ime.clipboard.ClipboardCopyPanelTest,dev.zeroinput.ime.InputExperienceRegressionTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

19 distinct tests passed. Clipboard cases cover deletion races,
corruption, blocked operations, failed writes and recovery; panel cases distinguish
failure from empty/disabled, hide stale rows and preserve status after paste
confirmation. Layout fixtures cover Chinese/English, light/dark, 320dp portrait and
800dp landscape at 1.3 font scale. Error-text bounds passed; English light portrait
and Chinese dark landscape rendered fixtures were visually inspected without clipping.
Input regressions cover drift/cancellation, overlapping shifted keys, candidate
browsing, number/symbol return, tools and stable panel measurements.

Before the B4 fix, `AiConnectionDialogLifecycleTest` failed reproducibly:
the replaced dialog's delayed dismiss closed requests `[0, 1]` instead of `[0]`.
After the fix the same test passes; old completion/cancellation cannot enable the
new request's retry button. Initial test-APK compilation also caught a missing
test import and unsupported local variable property reference; both were corrected.

## Limits

- No GitHub Actions run or Release publication; Linux clean-run export and upload
  remain CI acceptance items. Hash mismatch still fails closed, without rewriting
  the reviewed catalog or runtime lock.
- No Release APK build/signing, no lite APK rebuild, and no new ONNX native compile.
  Debug builds package all three ABIs; actual device execution here is x86_64 only.
- No physical device, API 26-32 or real biometric/key-invalidation run. Constructed
  stores exercise fault/generation behavior; they do not replace hardware Keystore
  acceptance. Theme/size fixtures are not a full IME navigation-bar rotation matrix.
- No broad performance claim or P1-P5 refactor. The vault remains serialized, and
  runtime hashes are not a guarantee against compromise of the reviewed repository.
