# Review of the latest three commits

Date: 2026-10-26

## Scope

The user requested review and repair of the latest three commits:

- `15431ba`: private import lifecycle and device-test JDK discovery.
- `fcb43f7`: personal candidate routing, shared promotion threshold, English
  engine ownership, reviewed Rime artifacts, deployment and regression tests.
- `e0a9cb8`: history reconciliation. Its tree matches its first parent `a8e6646`
  (`35f7a94bd95631a0631114b5b0d3ea646fc25355`), so it adds no source changes.

The working tree was clean at entry. Review followed the changed code and its
selection, persistence, deployment, authentication and build callers. Earlier
audit reports were context, not evidence of this revision passing. No unrelated
feature work, storage migration, dependency update or native artifact regeneration
was included.

## Confirmed findings and repairs

### P2: missing default prebuilt directory was accepted as a cache hit

`RimeAssetInstaller.prebuiltMatches()` returned true when `shared-*/build` did
not exist. With `.installed` still present, deployment skipped recovery and
librime compiled the full public dictionary into user storage. This bypassed
the reviewed default cache and reintroduced the expensive startup path.

A device regression first initialized an isolated fixture, closed it, deleted
only its shared build directory and restarted. Before repair it failed with
`Missing prebuilt data must be restored from reviewed APK assets`. The repair
returns false for a missing build directory, allowing existing bounded
redeployment to restore all three assets. The test requires a restored shared
table, no user-compiled default table and actual `nihao` conversion.

### P2: default device-test JDK discovery failed before Gradle

The new assignment to `$jdkCandidates` read that same variable before its first
assignment. Under StrictMode, omitting `-Jdk17Path` raised an uninitialized-variable
error even when valid JDK environment hints existed. The original assignment was
reproduced in a fresh PowerShell invocation before repair.

Discovery now belongs to `tools/lib/JavaToolchain.ps1`: explicit path, environment
hint, then a sibling `jdk17`. An invalid explicit override fails rather than
silently choosing another compiler. Tests cover precedence, absent hints, invalid
versions, paths containing spaces and exactly one returned path. The device
regression command intentionally omits `-Jdk17Path` to exercise sibling discovery.

### P2: Python optimization disabled all prebuilt build checks

`tools/rime-prebuilt/verify.py` used Python assertions for every integrity check.
Under `-O` (also enabled by `PYTHONOPTIMIZE`), a fixture with an unreviewed Rime
commit and absent archives printed a successful verification message. A negative
test reproduced this before repair.

All checks now raise explicit failures. Negative tests reject the invalid manifest
in normal and optimized modes and reject an archive hash mismatch under `-O`.
The real reviewed artifacts also pass optimized verification. File names, limits,
hashes and source/runtime identity requirements are unchanged.

### P2: default-asset hash input was never closed

The new default-generation check opened `rime/default.yaml` and passed its stream
to a hashing helper that does not own or close streams. Repeated initialization
or revision checks could retain asset descriptors until garbage collection.
The caller now closes it with `use`, including exceptional exits. This finding
is based on ownership inspection; no descriptor-exhaustion claim is made.

## Test corrections and adjacent review

- The test named `personal space use also honors the opt in` returned no personal
  suggestions, exercising a native candidate instead. It now supplies a distinct
  personal word and asserts the personal ID and `recordUse` route.
- The real-IME three-choice promotion fixture now sets threshold 3 explicitly and
  restores the previous value, so a user's saved threshold cannot alter the test.
- Native candidate identity, canonical reading and partial-selection behavior are
  preserved by the ranking change. English personal lookup remains in the gated
  controller; independent drafts retain the empty personal port.
- Private import deliberately writes no saved instance state. Its existing
  foreground, authentication, replacement and request-generation checks remain
  in place. No additional production change was justified in that path.

## Verification

- `test testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest`, with
  `-PrequireRime=true --no-parallel`, JDK 21 runtime and JDK 17 compiler: passed.
  Debug Android tests plus pure JVM modules total 886 cases: 884 passed, two
  external-credential tests skipped. Release Android unit suites also passed:
  812 cases, two skipped. These are Gradle task results, including valid cache
  reuse, not a claim that every case executed from scratch.
- PowerShell toolchain and APK-artifact resolver regression scripts: passed.
- Python verifier negative tests and real `python -O` verification: passed.
- Debug/Release merged main manifests: backups disabled, cleartext traffic
  disabled, Release has no debuggable override. Debug remains debuggable.
- Final three-ABI Debug build and Lint passed. Each APK contains only its
  expected ABI, native Rime, the manifest and all three prebuilt archives with
  matching reviewed hashes; APK signature checks passed. Immutable copies and
  SHA-256 values are in ignored `build/review-final-artifacts/manifest.json`.
- The first focused device run on the existing credential-locked emulator
  reported 41 cases, 17 failures, no skips. UI failures waited for missing IME
  windows/resumed activities or reached an already-discarded import request.
  `dumpsys window policy` confirmed `showing=true`, `secure=true`; the lockscreen
  occupied the foreground. This run is retained in
  `build/review-three-commits-device.log`, not counted as passing evidence.
  A separate temporary API 36 x86_64 emulator was created without altering the
  existing AVD's data or lock configuration.
- The final focused run on the temporary emulator passed all 41 cases, with zero
  failures/skips, in 250.887 seconds. It includes deployment/corruption/missing
  cache, custom dictionary recovery, close/cancel/retry, actual Chinese/English
  editor input, personal learning and thresholds, public associations, isolated
  drafts, AI/IME interaction and private-import lifecycle/layout tests.
  `build/review-three-commits-final-device.log` records the run. Installed APK
  SHA-256 was checked before and after instrumentation:
  `473067381c39b4543533f29109286245a5c0b086418ed8d50178cf01e20b00af`.
  The receipt is preserved at
  `build/review-final-artifacts/device-verification.json`. The temporary emulator
  was stopped and its verified AVD directory deleted after completion; the
  original emulator remains intact.

Regression entry points (from repository root):

```powershell
./tools/test-java-toolchain.ps1
./tools/test-test-apk-artifact.ps1
python tools/rime-prebuilt/test_verify.py
python -O tools/rime-prebuilt/verify.py
./gradlew.bat test testDebugUnitTest privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  -PrequireRime=true --no-parallel "-Porg.gradle.java.installations.paths=$env:JDK17_HOME"
```

Use a JDK 21 host for SDK 36 Robolectric and set `JDK17_HOME` to the independent
compiler toolchain. Device checks use `tools/test-input-experience.ps1` with an
unlocked disposable emulator and the final immutable APK. The exact class list
is recorded in the verification receipt. Before-fix device evidence is retained
in `build/review-three-commits-before-device.log`; the initial combined validation
and final packaging/Lint logs are `build/review-three-commits-validation.log` and
`build/review-three-commits-final-build.log` respectively.

The device scope is a focused API 36 x86_64 emulator suite. Physical ARM timing,
the entire application instrumentation suite and a Release APK build/signing
are outside this verification. No code was committed or pushed.
