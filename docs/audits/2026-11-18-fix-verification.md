# Audit repair and verification (2026-11-18)

## Result and scope

Repaired the 13 retained findings in [the reviewed audit](2026-11-17-code-audit.md)
and re-reviewed the affected implementation, callers, failure paths, tests,
configuration and final APK contents. Baseline: `main` at `26a6cec`, with this
uncommitted working-tree patch. The original untracked audit is preserved.
No changes were committed, pushed or published. This was an affected-path review,
not a claim that all project code or every platform failure was exhaustively audited.

No encrypted format, key alias, Android permission or network adapter authorization
was changed. The settings window now discards unsaved AI provider drafts on stop;
text language packs reject overlong/overfielded records. These behavior changes
and residual limits are documented in README, architecture, SECURITY and the
threat model.

## Finding disposition

| ID | Change | Verification / limit |
| --- | --- | --- |
| H-5 | All 17 JNI exports contain full C++ exception boundaries; fixed Java errors preserve pending exceptions. String, context, commit, iterator, unselected-session and partial initialization leases clean up on failure. | All three Debug/Release ABIs built. Shared production JNI helper failure injection passed on isolated Android x86_64. Whole librime allocator exhaustion was not injected. |
| H-7 | APK verifier requires Rime, C++ shared runtime and both ONNX libraries for every requested ABI; packaging passes its explicit ABI set. | 12 individual missing-library negatives plus missing-ABI case; old 0.7.4 package rejected; new universal has all 12 entries. All Release split contents passed too. |
| M-6 | Removed repository plaintext cache. Application snapshots strip every key; request workers resolve selected credentials with exact snapshot and metadata checks before/after load. Settings drop secrets on stop/destroy and failed saves. | Repository storage change/failure regression, state redaction, rejected/queued/revoked credential-load tests, full AI regression suite and platform flow tests passed. Active strings cannot be guaranteed erased from JVM heap. Format remains 5. |
| M-18 | Added server/asynchronous/datagram channels, sockets, connection types and DNS/reverse lookup calls/import aliases/method references to the existing gate. Pure URI, InetAddress value construction and file channels remain allowed. | `testPrivacyBoundary` and `privacyCheck` passed. Numeric address fixtures use `getByAddress` without DNS. Static text scanning is not a complete language-level capability proof. |
| L-7 | Reject raw text lines above 4096 characters or 16 fields before splitting, including rows beyond the entry budget; reject the entire loaded dictionary. | Tab/space amplification negatives passed. Original 8,000,000-byte real-parser probe now returns `PARSED bytes=8000000 entries=0` under `-Xmx128m`. |
| L-27 | Stop JNI allocation/lookup chains after each failure, including array elements, ranges and consecutive update strings. | Production helper fake-JNI failure injection asserts no further non-cleanup call after a pending exception. Both native and Java allocation failures covered. Actual ART heap exhaustion/CheckJNI abort injection not performed. |
| M-14 | One shared test-fixture contract used by English, fallback, language pack and actual native Rime. Covers start, keys, invalid/valid selection, page directions, reset, immutable snapshots, Enter/Space and close. English's selected-word suffix is an explicit fixture parameter. | JVM tests passed; final universal native test passed without fallback. Ownership ends at close; no new behavior is promised after close. |
| M-23 | Added coroutine core/core-jvm/android 1.6.4 component/source/notice and exact upstream Apache license text. | Runtime graph from reviewed audit; upstream 1.6.4 license checked. Final APK and companion notices ZIP contain matching license bytes. |
| L-14 | Read empty-write stamp immediately, require it within the write interval and expire suppression after 1000 monotonic ms or a changed stamp. | Four metadata regressions passed. A same-interval external replacement remains briefly indistinguishable; suppression is bounded. Android 8 actual-device interleaving not tested. No clipboard payload/length read added. |
| L-42 | Toolbar long press reads the current localized content description; initial language label/description also use resources. | Chinese/English normal and sensitive-description Robolectric regression passed; final package layout cases passed. |
| D-1 | Added `ime-ui -> ime-core` and `app -> security` to architecture diagram. | Compared with module build files. |
| D-2 | Authentication navigation documentation corrected from 60 to 30 seconds. | Compared with broker/coordinator; runtime duration unchanged. |
| D-3 | Threat model distinguishes model/remote/public-dictionary transports and local Gradle/package checks from existing CI workflows. | Compared with gate, adapters, ADR 0033 and workflows. |

## Verification

Final aggregate reports contain 87 pure JVM tests and 999 tests per Android
Debug/Release variant. Each Android variant has 997 passed, 2 opt-in live-provider
skips, 0 failures/errors. This is 1086 distinct JVM cases (1084 passed, 2 skipped) across
pure JVM plus Debug, with the Android cases additionally exercised in Release;
variant repetitions and Gradle reused results are not new distinct tests.

Successful commands (run from repository root):

```powershell
./gradlew.bat test testDebugUnitTest privacyCheck :app:lintDebug `
  :app:assembleDebugAndroidTest :app:processDebugMainManifest `
  :app:processReleaseMainManifest "-Pandroid.injected.build.abi=x86_64" `
  -PrequireRime=true --no-parallel
python -m unittest discover -s tools/resources -p 'test_*.py'
./tools/test-test-apk-artifact.ps1
./tools/test-jni-failures.ps1 -SdkRoot D:/env/AndroidSDK -Serial emulator-5580
./tools/package-test-apk.ps1 -Abi universal -MaxApkBytes 180000000
./gradlew.bat :app:assembleRelease -PrequireRime=true --no-parallel
```

The package command ran its own complete tests/privacy/Lint checks; `SkipChecks`
was not used. Final checks after the final gate/license/test refinements also
passed. Lint: `No issues found`. Python: 16 tests passed. JNI exports: 17/17 have
translation boundaries, each function remains below 100 lines. Packaging locator
and reset regressions passed. Debug/Release merged manifests passed privacyCheck;
Release manifest has backup disabled and no debuggable flag. Three Release split
APKs passed the full resource/native/hash verifier, and each contains the new
fixed native boundary messages.

Final universal APK was installed into the existing isolated API 37 x86_64 AVD
and its installed SHA-256 was checked before/after instrumentation. **22 tests
passed, 0 skipped** across NativeEngineContractTest, AiProtocolPlatformTest,
AiRemoteToolsPlatformTest, AiWorkbenchFlowTest, AiAssistantActivityTest,
EnginePreparationUiTest and ClipboardGuardPanelTest. Layout coverage includes
small portrait/landscape and light/dark themes. The first broader pass passed 17
and skipped 6 clipboard cases whose prerequisites were absent; those skips are
not claimed as device acceptance. The AVD started for this work was stopped.

Final device report:
`build/device-apk-verification/20261118-010609-a62b1d10ac2c4e61a6a3bfc2000f5d46.json`.

Evidence is local in `.tmp-evidence/`: `audit-final-checks.log`,
`audit-package-universal.log`, `audit-final-platform.log`, `audit-jni-failures.log`,
`audit-fixed-line-probe.log`, `audit-fix-python.log`, `audit-old-apk-rejected.log`,
`audit-final-apk-entries.json`, `audit-release.log`, `audit-release-artifacts.json`,
`audit-release-manifest.txt` and `audit-apk-artifact-test.log`. Earlier failed
iterations are retained in targeted logs and `audit-package-constructor-red.log`;
only final successful results above establish acceptance.

## Deliverable

Installable Debug test package, **0.7.5-debug**, application ID
`dev.zeroinput.ime.debug`, includes `arm64-v8a`, `armeabi-v7a`, `x86_64`:

`app/build/outputs/test-apk/20261118-090141/zero-fish-input-0.7.5-debug-universal.apk`

- Size: **104,087,362 bytes** (104.087 MB / 99.265 MiB).
- SHA-256: `6f870e7b4d4bf3afd4aeda0e3e27933e42596aa220aab2add833a14e38de0d7c`.
- Test budget 180,000,000 bytes: passed. 80,000,000-byte target: exceeded.
- Companion directory contains notices ZIP, handwriting corresponding sources
  ZIP, SHA256SUMS, APK-SIZE, BUILD-PROVENANCE and VERIFICATION JSON.
- Release splits exist in `app/build/outputs/apk/release/`; they are **unsigned**,
  not installable signed releases. No release key was created or used.

## Remaining verification

Physical ARM64/ARMv7 execution, Android 8 platform timing, OEM-specific editor
behavior, full-process native allocation exhaustion and live external AI services
were not verified. Only API 37 emulator images are installed locally. The fixed
resource boundary injection test does not simulate every upstream librime error.
Android clipboard compare-and-clear is not atomic, and JVM String memory is not
reliably erasable. The static privacy gate remains defense in depth. No additional
confirmed finding was identified in this affected-path re-review.
