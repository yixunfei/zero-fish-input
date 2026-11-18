# Audit follow-up verification (2026-11-18)

Reviewed the 20 findings in the current [audit](2026-11-17-code-audit.md), whose
contents were rewritten before the first follow-up. The first-run record below
is historical; the user later requested another verification and an update to the
original audit. The final re-verification is recorded at the end of this file.
This record applies to the working-tree follow-up after v0.7.6; earlier repair
and release records describe their own source state. No commit, publication,
permission, encrypted format or new network adapter is part of this task.

## First follow-up disposition (historical)

| Finding | Verified conclusion and action |
| --- | --- |
| H-1 | Confirmed for the asynchronous public clear, not the durable settings path. Rejection now throws instead of claiming acceptance, keeps reads blocked, and permits explicit retry. No caller-thread disk I/O. Closed stores also reject clear. |
| M-1 | Confirmed broad catch. Only exceptions originating in engine calls enter engine recovery; cancellation, interruption and fatal errors propagate without committing raw text or replaying a key. Ordinary observer/store faults cannot disable conversion. |
| M-2 | Confirmed public setter inconsistency. Language/pack changes use the same accepted raw-commit guard as explicit switching. Rejected editor commits retain the original engine and composition. Some service callers already defer changes until idle; the report overstated their immediate exposure. |
| M-3 | Confirmed, including previous tests encoding the unwanted behavior. Pending edits retain composition; after refresh the user can select again. Fully filtered pages commit raw text plus Space. No deferred keystroke is replayed into another session. |
| M-4 | Confirmed defense gap, with no evidence of unauthorized current transport. Expanded native/root/buildSrc/data/build-script checks and added approved-adapter capability/constraint negatives. Build tooling retains pinned dependency fetches; static matching is not a complete security proof. |
| M-5 | Confirmed missing socket write deadline. Existing adapter uses bundled OkHttp connect/read/write/call deadlines, no proxy/cookies/cache/redirect/retry and a one-dispatch interceptor. Rejected cancellation falls back to direct disconnect; timeout cleanup runs even if a terminal listener fails. |
| M-6 | Confirmed ownership ordering. Observer is owned before registration; partial registration is unregistered after platform failure and during close. Runtime fault injection covers successful registration followed by an exception. |
| M-7 | Mixed. Formats 1-4 deletion is explicitly approved in ADR 0024 and already tested, so retained. Providers without a selection are now readable/editable; active provider/key stay absent. Unknown selected IDs and invalid capabilities remain rejected. |
| M-8 | Confirmed at cited optional-work boundaries. Module-local recoverable helpers catch ordinary exceptions, propagate cancellation/interruption and fatal errors, and retain explicit cleanup-and-rethrow. Cancelled preload clears its pending marker for retry. Optional native-load LinkageError and terminal AI worker error policies remain deliberate. No broad whole-repository exception rewrite. |
| L-1 | Confirmed. English composition accepts ASCII letters/apostrophe; other letters commit literally without an automatic completion space. |
| L-2 | Partly confirmed. Zero-progress cell streams now fail rather than spin, and read loops observe interruption. The alleged unguarded trailing u16/text reads are already bounded in current source; odd tails already throw IllegalArgumentException. Callback and decoding failures are not indiscriminately relabeled. |
| L-3 | Confirmed maintenance issues, not a reachable NPE. Audio buffer allocation follows successful recorder construction and encoded audio uses a non-null local; existing final cleanup remains. |
| L-4 | Confirmed return-value inconsistency. An accepted paired insertion returns true even if caret adjustment fails; context is invalidated and insertion is not replayed. |
| L-5 | Not reproduced. JVM regex backtracking handles numeric/hyphen-ended IDs and negative nanoTime, including Long.MIN_VALUE. Existing names are retained; isolated platform coverage checks cleanup and preserves unrelated directories. |
| L-6 | Confirmed malformed UTF-8 acceptance. Decoder checks lead/continuation bytes, minimum code points, surrogate range and Unicode maximum, preserves a following valid ASCII byte, and uses valid storage for empty JNI strings. No evidence that Android actually crashes on the former empty call. |
| L-7 | Confirmed in browsing and again in personalization merge. Distinct native routes survive identical displayed text; page IDs include their original index. Personal duplicates still merge onto native entries. |
| L-8 | Report's missing-contract claim is false: EncryptedStore already documented consuming/clearing writes. Added explicit all-exit KDoc. Both draft authorization checks are retained: before Keystore work and before publication; they guard different race windows. |
| L-9 | Confirmed whole-file allocation. Text streams hash the same bounded bytes while parsing bounded lines; JSON avoids hash re-encoding but still uses the existing bounded document parser. Existing registry refresh callers run on initialization/settings workers. No measured device heap claim. |
| L-10 | Intentional security behavior. remove() decrypts existing bodies, so the one-use read grant must be consumed before load(). Moving consumption after loading would weaken the approved boundary. Failed-write rollback and fresh authentication remain required. |
| L-11 | Defensive gap confirmed by injected clock failure before scorer handling. Abnormal drain exits release scorer/buffers and clear scheduling ownership for all throwable paths, then propagate. Resource change can resume scoring. |

## First follow-up verification (historical)

Initial regressions reproduced cancellation/fatal-engine swallowing, rendering
Error swallowing, direct language-change loss, dictionary Space behavior,
unselected-provider rejection and non-ASCII English composition. Evidence:
`.tmp-evidence/audit-followup-red.log`. Its app-test compilation failure was a
test import mistake, not a successfully reproduced app regression. Intermediate
logs retain an invalid Gradle task name, a delegated test-double dispatch mistake
and a missing test dependency import; these are not passing evidence.

Final verification completed against the follow-up source state. The main command
exited 0 with `BUILD SUCCESSFUL`; 144 tasks executed and 661 were up to date.
Unchanged tasks reused Gradle's input-checked outputs, including native objects
built for all three ABIs in the preceding full run. Neither run used the missing
native fallback as a substitute for Rime verification.

```powershell
./gradlew.bat test privacyCheck :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:assembleRelease :app:lintDebug :app:lintRelease `
  -PrequireRime=true --no-parallel --max-workers=4
```

| Test execution group | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| Pure JVM modules | 90 | 0 | 0 |
| Android module Debug unit tests | 1,012 | 2 | 0 |
| Android module Release unit tests | 1,012 | 2 | 0 |
| Total variant executions | 2,114 | 4 | 0 |

Debug and Release counts include the same tests executed in both variants, not
2,114 unique behaviors. The two opt-in live AI tests in each variant require
temporary provider credentials and were intentionally skipped. Both Lint reports
say `No issues found.` Privacy verification includes gate negative self-tests,
source/resource scans and the Debug/Release merged manifest checks.

An isolated API 37 x86_64 emulator passed 50 instrumented tests with no skips:

| Platform test class | Passed |
| --- | ---: |
| NativeEngineContractTest | 1 |
| NativePinyinTest | 8 |
| EnginePreparationUiTest | 2 |
| LanguagePackJsonPlatformTest | 7 |
| AiProtocolPlatformTest | 4 |
| AiRemoteToolsPlatformTest | 3 |
| SecureClipboardVaultReliabilityTest | 7 |
| ClipboardGuardRuntimeTest | 12 |
| ClipboardGuardPanelTest | 5 |
| InputExperienceVisualTest | 1 |

```powershell
$auditClasses = @(
  'dev.zeroinput.ime.NativeEngineContractTest'
  'dev.zeroinput.ime.NativePinyinTest'
  'dev.zeroinput.ime.EnginePreparationUiTest'
  'dev.zeroinput.ime.LanguagePackJsonPlatformTest'
  'dev.zeroinput.ime.ai.AiProtocolPlatformTest'
  'dev.zeroinput.ime.ai.AiRemoteToolsPlatformTest'
  'dev.zeroinput.ime.clipboard.SecureClipboardVaultReliabilityTest'
  'dev.zeroinput.ime.clipboardguard.ClipboardGuardRuntimeTest'
  'dev.zeroinput.ime.clipboardguard.ClipboardGuardPanelTest'
  'dev.zeroinput.ime.InputExperienceVisualTest'
) -join ','
./tools/test-input-experience.ps1 -Serial emulator-5580 -TestClass $auditClasses `
  -ApkPath 'K:/zeroInput/app/build/outputs/apk/debug/app-x86_64-debug.apk' -Verbose
./tools/test-jni-failures.ps1 -SdkRoot D:/env/AndroidSDK -Serial emulator-5580
```

The JNI failure-injection executable passed separately, including malformed UTF-8,
empty strings and allocation failures. Platform tests cover native conversion
without fallback, startup handoff, signed staging cleanup, protocol handling,
clipboard failure/authorization paths and panel geometry. The visual fixture
checks 240/320/360/411 dp portrait and 720 dp landscape in both themes. Four fixed
public fixture captures were also inspected. The dedicated
`ZeroInput_Clipboard_P1` emulator was stopped after testing; its data was retained.

Each Debug and unsigned Release split passed the full resource/native contents
check for its ABI. Each Debug split also passed package, ABI and signature checks:

```powershell
foreach ($auditAbi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
  python tools/resources/verify-apk.py full `
    "app/build/outputs/apk/debug/app-$auditAbi-debug.apk" --abis $auditAbi
  python tools/resources/verify-apk.py full `
    "app/build/outputs/apk/release/app-$auditAbi-release-unsigned.apk" --abis $auditAbi
  ./tools/check-apk-installable.ps1 `
    -ApkPath "app/build/outputs/apk/debug/app-$auditAbi-debug.apk" `
    -ExpectedPackage dev.zeroinput.ime.debug -ExpectedAbi $auditAbi
}
```

| ABI | Debug APK bytes | Unsigned Release APK bytes |
| --- | ---: | ---: |
| arm64-v8a | 92,912,729 | 83,944,367 |
| armeabi-v7a | 91,466,253 | 83,282,831 |
| x86_64 | 93,139,437 | 84,161,999 |

The exact installed x86_64 Debug APK SHA-256 is
`6aee4b9503c473de95b2525d687bfeb65e06b161e181c2ddc8ef9f493af8848c`.
The test APK SHA-256 is
`2c35b14c3f862b32c42e8e0eee38ca85409636f275a47f433a583b8d3ba6541f`.
The device script verified both installed identities before and after the tests.
All packages remain above the separate 80 MB optimization target.

Local evidence, retained outside version control:

- `.tmp-evidence/audit-followup-final.log` and the preceding `audit-followup-full.log`.
- `.tmp-evidence/audit-followup-jvm-counts.json`, `audit-followup-jni.log`,
  `audit-followup-apk.log` and `audit-followup-apk-hashes.json`.
- `.tmp-evidence/audit-followup-device.log` and `audit-followup-ui/`.
- `build/device-apk-verification/20261118-040123-9e65c2c769e548a69e32baabe918b8b5.json`.

`git diff --check` and local Markdown link checks passed in that first run.
At that time the source audit SHA-256 was
`546219731d2d09f66833f87d0e906acb0968303aab19c06b000359c71cbe012b`,
matching its content at that task start. This hash is historical: the audit was
subsequently updated, including by the user-authorized final re-verification.
The first-run JSON tree-parser limitation is superseded below. Old APK paths and
hashes identify historical evidence; cleanup removed those packages before the
latest rebuild.

## Remaining limits

Physical ARM/OEM execution, Android 8 timing, live external AI/MCP requests,
actual hostile TLS socket write stalls and device heap profiling require separate
runtime evidence. Deterministic blocked-stream injection establishes cancellation
control flow, not the behavior of every TLS provider. Optional live-provider
tests require explicit temporary credentials. No production signing key is used;
unsigned Release artifacts cannot be described as an installable release.

`InputSessionControllerTest.kt` is 1,576 lines after adding the regressions. It
uses the existing shared controller/engine fixtures and remains below the 2,500
line complex-file ceiling. It is retained as one cohesive suite for this scoped
fix; navigation is the maintenance cost. Future scenario-based separation should
extract genuinely shared fixtures instead of duplicating them just to reduce
line counts. The affected production controller remains below 1,500 lines.

## Final re-verification after the updated audit

The user explicitly requested rechecking the updated audit and fixing confirmed
remaining issues before updating that original document. The current authoritative
result is in [the updated audit, section 9](2026-11-17-code-audit.md#9-最终再核验2026-11-18):
17 confirmed findings fixed, three withdrawn, no confirmed pending repair among
these 20 findings. M-7/L-2 each include one withdrawn subfinding. The first-run
partial JSON-parser note and unchanged-audit hash above are historical.

Two residual implementation issues were reproduced and repaired. Owned CMake,
host scripts and catalogs now enter the privacy gate with exact reviewed host
fetch paths; real temporary CMake/PowerShell/catalog violations were all rejected
and the fixtures removed. JSON dictionaries now fold strict events into bounded
rows with explicit depth/token/live-key limits and complete trailing validation.
The same 14,000,001-byte input and heap probe failed with OOM before the repair
and loaded successfully afterward under `-Xmx128m`. The public clear KDoc now
states asynchronous acceptance, rejection, closed-state and durable completion.
Intentional engine fallback, pending-Space behavior, approved provider reset and
clipboard authorization semantics were retained after checking their contracts.

The independent full command shown earlier passed again, this time in 11m 32s:
805 tasks, 246 executed, 27 input-matched cache hits and 532 up to date. JVM XML
results contain 2,122 passes, four credential-dependent skips and no failures or
errors (90 JVM; 1,016 Debug and 1,016 Release passes). Both Lint reports have no
issues. Three-ABI real Rime Debug/unsigned Release builds and all six APK contents
checks passed; all Debug splits passed package, ABI and signature checks.

Direct JSON dictionary Android tests were added after that full run. The final
language-pack Debug/Release tests, Android test APK and Debug Lint passed in 1m 12s.
The exact installed x86_64 APK then passed 52 API 37 tests with no skips, including
nine language-pack platform tests (the prior class had seven). Both installed
hashes were checked before and after execution. JNI fault injection passed too.
The task-started isolated emulator was stopped after verification, without wiping
its retained data. No physical ARM/OEM, Android 8, live TLS stall or Android heap
profiling claim is made.

| ABI | Current Debug bytes | Current unsigned Release bytes |
| --- | ---: | ---: |
| arm64-v8a | 92,464,362 | 83,945,843 |
| armeabi-v7a | 91,017,886 | 83,284,307 |
| x86_64 | 92,691,070 | 84,163,475 |

Current x86_64 APK SHA-256:
`b62e228c4cf10c9955fb51e9354b70ac01dfa71c055a012acc9e30bf81356992`.
Current test APK SHA-256:
`4518d3444a3f14500e69271805bd2e8ba2592fd2f5e5031f355fe650db9b1c79`.
The preceding cleanup's ARM64 test-apk delivery predates these parser changes;
use the current `app/build/outputs/apk/debug/` files for the latest source state.
Release artifacts remain unsigned. No commit or publication was performed.

Local evidence: `.tmp-evidence/audit-rereview-full.log`,
`audit-rereview-targeted-final.log`, `audit-rereview-platform-build.log`,
`audit-rereview-jvm-counts.json`, `audit-rereview-json-red.log`,
`audit-rereview-json-green.log`, `audit-rereview-gate-negative.log`,
`audit-rereview-apk.log`, `audit-rereview-apk-hashes.json`,
`audit-rereview-device.log`, `audit-rereview-device-counts.json` and
`audit-rereview-jni.log`. The new device identity report is
`build/device-apk-verification/20261118-050734-90afa3dfd6fa420185aa76e4f03c2b30.json`.
