# Clipboard P1 acquisition prototype (2026-10-31)

Status: first emulator validation complete; P1 version/source matrix remains open.
This is not the P2/P3 system-import product implementation.

## Self-audit follow-up

The follow-up audit found and reproduced two prototype defects: an empty
preflight incorrectly granted cleanup ownership even with no fixture write, and
loss of access during acquisition did not discard the result. Both new device
regressions failed before their fixes. The expanded scanner self-test also
failed for spaced/unqualified Kotlin payload-property access.

The fixture now records ownership only after a public sample write/copy, checks
focus/unlocked state and matching known timestamp before cleanup, and refuses to
overwrite an observed replacement between samples. Unknown timestamps revoke
mutation eligibility. The Activity is released even if cleanup fails. These
checks reduce races; timestamps still do not provide atomic identity, so the
disposable-device requirement remains mandatory. The default-IME comparison
requires a different initial keyboard, verifies the actual selected IME and
restores its prior enabled state. Character mismatch assertions report only the
sample identifier, never the returned characters, and rejected drafts are wiped
even when an assertion fails. Live access eligibility is rechecked after read.
Platform parser-rejection cases must first prove that the returned snapshot
matches their fixed sample; null/denied acquisition can no longer masquerade as
successful rejection coverage. Cleanup timestamps come from that same verified
snapshot, not a later metadata read that may describe another item.

The production direct-selection handoff was also found to enforce its five-second
expiry only by a Handler callback. A delayed main thread could consume a stale
token before that callback ran. Regression coverage uses a controlled monotonic
clock with the expiry callback still pending; acceptance must check the deadline
at consumption and wipe expired buffers independently of callback scheduling.
This changes neither the lifetime budget nor authentication/storage formats.

Audit verification: the final combined emulator suite passed all 18 tests (11
prototype cases and seven existing consent/import/panel cases). Without the
explicit runner flag, seven pure cases passed and four platform cases skipped.
App Debug JVM tests: 391 discovered, 389 passed, two live-provider skips; the
three new handoff tests passed. The final x86_64 requireRime main/test assembly,
privacy scanner self-test, Debug/Release merged-manifest checks and Lint passed.
The final raw-snapshot test-APK rebuild also passed. Evidence:

- `.tmp-evidence/clipboard-audit-scanner-before.log`: expected Gradle failure
  (exit 1) before the payload-token scan fix.
- `.tmp-evidence/clipboard-audit-before-device.log`: two expected failed tests
  before cleanup-ownership and post-read eligibility fixes. ADB itself returned
  zero, so the failing instrumentation summary was the verdict.
- `.tmp-evidence/clipboard-audit-transfer-before.log`: three tests, one expected
  overdue-token failure (Gradle exit 1) before the deadline fix.
- `.tmp-evidence/clipboard-audit-final-checks.log`: final app JVM/privacy/Lint
  and requireRime x86_64 builds, exit 0.
- `.tmp-evidence/clipboard-audit-snapshot-build.log`: final test-APK build, exit 0.
- `.tmp-evidence/clipboard-audit-final-device.log`: `OK (18 tests)`, no skips.
- `.tmp-evidence/clipboard-audit-no-optin.log`: seven passed/four skipped.

The audit covers the recent clipboard changes and adjacent direct-copy/import
lifecycle; it is not a claim that every repository feature was exhaustively
audited. Existing version/OEM/performance and P2/P3 acceptance gaps still apply.

The updated ARM64 Debug package was generated with the full script (no skipped
checks), exit 0: `./tools/package-test-apk.ps1 -Abi arm64-v8a`. All module test
results total 1746 executions: 1742 passed, four live-provider skips, zero
failures/errors. privacyCheck, Lint, requireRime ARM64 assembly and
signature/ABI/permission checks passed. Log:
`.tmp-evidence/clipboard-audit-arm64-package.log`.

Use the updated artifact for manual device acceptance:
`app/build/outputs/test-apk/20261031-230720/zero-fish-input-0.7.1-debug-arm64-v8a.apk`.
SHA-256: `c2bd81e3d1d405acd92c0e60cf93cce507132d7fb670a05e430fc0355b47987f`.
Hashes and notices/corresponding-source ZIPs accompany it. It includes the
production handoff-expiry repair; the prototype remains test-APK-only. It has
not been installed on a physical ARM device or signed/published as a Release.

## Scope

The [approved plan](clipboard-product-repair-plan.md) starts with isolated
public-fixture acquisition. `SystemClipboardImportPrototypeTest` exists only in
androidTest. Production payload reads, permissions, vault format, keys,
authentication and automatic cleanup are unchanged. Production fixes allow
private-clipboard status text to grow above its existing 72 dp minimum inside the
scrollable panel, avoiding large-font clipping, and enforce the existing
five-second direct-selection handoff deadline when consuming its token.

The privacy scanner allows acquisition only in that exact test source. Its
self-tests still reject reads in the guard, ordinary business code, AI, a renamed
test, prefixed paths and a copy moved into main. Only the fixed sample factory
and literal empty API 26/27 cleanup can be used by that fixture's write calls.

Real platform tests require `publicClipboardPrototype=true` on a disposable test
device. They first inspect metadata without reading text and skip if an existing
current item is observed. This check alone cannot prove access or absence of
user data on arbitrary devices; never run the opt-in suite on a personal device.
No payload backup or restore is performed. Parser/model tests use only fixed
in-memory samples and do not need that flag.

## Environment and observed behavior

- Fresh AVD: `ZeroInput_Clipboard_P1`, serial `emulator-5580`, x86_64.
- Image: Android 17 / API 37, `google_apis_playstore`; fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.051.A2/16418371:user/release-keys`.
- Security patch: 2026-08-05. Display: 320 x 640, mdpi.
- Initial/restored keyboard: Google Latin IME. The qualification test temporarily
  selects ZeroInput, then restores the initial keyboard.
- A focused ZeroInput page with another default keyboard acquired the fixed
  samples. The selected default ZeroInput IME acquired the separate test APK's
  `ExternalClipboardFixture` copy while that source had focus. Switching back to
  the other keyboard made the same background acquisition unavailable.
- Public application-management settings resolved, launched and returned without
  a payload read or treating the return as permission approval. No OEM permission
  screen or actual third-party permission modification was tested.

The source is the test APK's standard selectable TextView/copy action with a
different UID, not QQ, WeChat, LINE, a browser or a custom message-bubble menu.
API 37 is additional evidence; it does not replace the plan's API 26/27, 28, 29,
33 and 36 baseline or physical OriginOS acceptance.

## Checks and results

Seven new tests passed with explicit platform opt-in. Samples cover preserved
leading/trailing spaces, TAB, LF/CRLF, NBSP, mixed Chinese/English, full-width
punctuation, URL text, leading zeroes, combining characters, ZWJ family emoji,
skin tone, flag and the 8192 UTF-16 boundary. Empty/blank, NUL, unpaired
surrogates, 8193 units, URI, Intent and multiple items are rejected without
coercion or provider access. HTML uses only its supplied plain-text fallback.

Model tests verify no read without an armed action or after cancellation,
expiration/repeated consumption, and discard a result revoked during acquisition.
Null and injected SecurityException results create no draft. These are test-local
action probes, not proof of a production lifecycle/authorization coordinator.
Drafts reject AI extraction and expose no text before synthetic model
authorization; actual system authentication is outside P1.

The final combined emulator run passed all 14 tests: the seven prototype tests,
incognito consent, repeated import and private-clipboard panel regressions.
The panel matrix covers 320/800 dp, both languages/themes and 1.3 font scale;
English/light and Chinese/dark fixed-fixture PNGs were visually inspected.
App JVM results: 388 discovered, 386 passed, two live-provider tests skipped.
ime-ui JVM results: 48 passed.

Build/check commands (repository root):

```powershell
./gradlew.bat testPrivacyBoundary privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat :app:testDebugUnitTest testPrivacyBoundary privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :ime-ui:testDebugUnitTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

All three completed with exit code 0. privacyCheck includes Debug and Release
merged-manifest checks. Real Rime was required, not the fallback-only build.
Device runner examples require installing the matching Debug main/test APKs
using `adb -s emulator-5580 install -r -t <apk>` first:

```powershell
adb -s emulator-5580 shell am instrument -w -r -e class dev.zeroinput.ime.clipboard.SystemClipboardImportPrototypeTest -e publicClipboardPrototype true dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

ADB exit code alone is not the verdict: inspect the instrumentation summary
(`OK (7 tests)`); this runner also emits `INSTRUMENTATION_CODE: -1` on success.

Evidence under `.tmp-evidence/`:

- `clipboard-p1-build.log`, `clipboard-p1-rebuild.log`,
  `clipboard-p1-final-checks.log`, `clipboard-p1-ui-build.log`.
- `clipboard-p1-device.log`: seven passing prototype tests.
- `clipboard-p1-no-optin.log`: four pure tests passed and three platform tests
  skipped without explicit opt-in (the runner summary still says seven tests).
- `clipboard-p1-final-device.log`: 14 passing combined tests.
- `clipboard-p1-device-first.log`: initial HTML rejection and test-navigation
  timeout, corrected by accepting HTML's provided text and resetting fixture task
  state/returning from settings. No boundary was relaxed to hide the failure.
- `clipboard-p1-regressions.log`: initial large-font status clipping failure,
  reproduced before changing fixed height to wrap-content with the same minimum.
- `clipboard-p1-failure-en.png`, `clipboard-p1-failure-zh.png`: public UI fixtures.

## Remaining gates

T01-T06 have partial evidence only: no production enable/upgrade flow exists,
OEM denial is injected rather than measured, and baseline OS versions/custom
sources remain untested. No P2/P3 authentication, save/cleanup coordination,
generation/lifecycle integration or T07-T24 acceptance is claimed.

No physical ARM execution, biometric/credential flow, OriginOS permission/history
behavior, source-application versions, typing latency, acquisition P50/P95,
100-cycle retention measurement or matched package-size comparison is available.
Release assembly/signing/publication was not performed. The next work is P1's
remaining baseline/source coverage, then the narrowly scoped P2 production ADR,
authorization, cancellation and cleanup coordination before P3 product wiring.

## Initial ARM64 Debug handoff (before audit)

Per the user's emulator-first/manual-device workflow,
`./tools/package-test-apk.ps1 -Abi arm64-v8a` completed with exit code 0, without
SkipChecks. Full module tests, privacyCheck, Lint, requireRime ARM64 assembly,
signature/ABI/permission checks and hash generation passed. The full module
results contain 1740 executions (1736 passed, four live-provider skips across
Debug/Release variants), with zero failures/errors. Log:
`.tmp-evidence/clipboard-p1-arm64-package.log`.

Artifact:
`app/build/outputs/test-apk/20261031-224829/zero-fish-input-0.7.1-debug-arm64-v8a.apk`.
SHA-256: `1bda640e05c23eb3bac053888054efa645f6469070cf0009eea49bb5c878d1c3`.
`SHA256SUMS.txt` and license/corresponding-source ZIPs are in the same directory.
This is an existing-identity Debug test package, not a signed release. It contains
the status-layout repair and existing product features; the P1 acquisition class
is in the separate test APK, so this ARM64 app has no new system-import button.
Use it for existing direct-copy/paste and large-font status manual regression;
the new import flow cannot be manually accepted until P2/P3 are implemented.
No ARM device was installed or tested automatically. The native strip tool
reported that two native libraries were packaged without stripping; build and
artifact checks still passed. No size/performance improvement is claimed.
