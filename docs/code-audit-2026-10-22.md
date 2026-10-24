# Code audit: 2026-10-22

## Scope

Reviewed the current working tree against `HEAD` `3976d2d`: 45 modified files
(+947/-494) plus 2 untracked source files. The change set removes the personalization
completion callback, moves frequency ranking to a background snapshot with a
main-thread read-only view, adds generation stamps around clipboard import
intents, and switches several locale-sensitive comparisons to `Locale.ROOT`.
Priority areas were the key path, IME layout measurement, personalization
queueing, association windows, clipboard import, and build/test wiring.

This was a targeted review of that diff, not a line-by-line audit of all
first-party or upstream source. No persisted format, permission, network
transport or engine ABI was changed by the diff. No crash, plaintext leak,
authorization bypass or privacy-boundary defect was found.

## Findings

### M1 - Per-keystroke native OpenCC conversion added to the key path

`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeInputEngine.kt:316-317`
adds `displayText`, called at `:240`, `:294` and `:305`. The JNI side
(`engine-rime/src/main/cpp/zero_rime_jni.cpp:251-266`) takes the global
`engine_mutex` on every call and does UTF-8 encode/decode plus `Converter::Convert`.
`nativeReadUpdate` returns up to 10 candidates, so one key now performs up to 10
additional locked JNI round trips serialized with `process_key`/`read_update`.
`normalizeCandidateText` (`RimeInputEngine.kt:271`) already performs the same
conversion, and `InputSessionController.kt:290` still calls it for associations,
so there are two entry points with different failure handling (`displayText` is
not inside `nativeCall`). A null converter is unreachable: `nativeInitialize`
returns false when conversion cannot be created (`zero_rime_jni.cpp:144-152`).
Action: return converted text from `nativeReadUpdate`/`nativeCandidatePage` in one
pass, or reuse `normalizeCandidateText`, and measure the path per AGENTS 6/11.1.

### M2 - Input panel and header geometry changed without device evidence

`ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/KeyboardLayoutHost.kt:174,176,191,197`
and `ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt:1150-1156`:
compact mode now includes `safeInsets.bottom`
in the bottom inset, so `availableHeight` shrinks by the same amount and the
measured height adds `bottom` back, while docked `onLayout` keeps `insetTop = 0`.
The arithmetic is self-consistent, but this is the only visible layout change in
the diff. If a device already places the IME window above the navigation bar
while still reporting a non-zero bottom inset, the result is double padding and
compressed key heights. AGENTS 11.1 requires a device or emulator check for input
panel changes; the diff only adjusts instrumentation wait logic. Action: verify
3-button navigation, gesture navigation, floating and one-handed layouts.

### L1 - `clearBlocked` is only reset by a successful clear

`app/src/main/kotlin/dev/zeroinput/ime/personalization/QueuedPersonalizationStore.kt:274`
sets the flag, `:301` clears it, `:361` uses it as a preload gate. Neither
`invalidatePreparedData()` (`:198`), privacy invalidation, nor dictionary script
changes reset it, and `learn()` does not check it, so writes keep queueing and
fail silently inside the delegate. After one failed clear, personal candidates
and frequency ranking stay disabled for the process lifetime with no user-visible
state. The test `failed clear blocks later preload until an explicit retry
succeeds` confirms this is deliberate fail-closed behavior. Action: keep it, but
expose the state to settings, or separate "prepared data stale" from "clear
incomplete".

### L2 - First commit before association data is ready no longer re-runs

`AppGraph.addAssociationPredictorListener`, `ZeroInputService.associationPredictorObserver`
and `InputSessionController.refreshWordAssociations` were removed together with
the `WordAssociationSessionTest` case that covered a late-ready predictor.
`docs/architecture.md:365-371` already documents that there are no delivery
callbacks and that queries before readiness return no candidates, so the direction
is consistent. `WordAssociationIndex.loadBundled()` still loads asynchronously
from `AppGraph`, so a cold-start commit inside that window gets no association row
and is never retried. Impact is small (`docs/architecture.md:373-378`: 735 keys,
1,728 pairs) but the path now has neither test nor documentation. Action: state
the cold-start limitation in the architecture next-word section, or fold loading
into warmup.

### L3 - `app` re-implements the layout gate for associations

`app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt:460-464` combines
`personalizationAllowed` with `EditorInputOptions.from(attribute).layout == TEXT`,
while the authoritative rule lives in
`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:284-285`.
AGENTS 5.1 keeps reusable input rules out of `app`; adding a condition in one
place will silently diverge. Action: call `controller.requestAssociationWarmup()`
and let `ime-core` decide.

### L4 - The same resource hashes are hardcoded twice

`app/build.gradle.kts:19-30` and
`app/src/androidTest/kotlin/dev/zeroinput/ime/handwriting/HandwritingTestResources.kt:14-23`
each maintain the same four size/hash pairs. A resource bump requires editing
both; a missed edit makes build-time and runtime validation contradict each other.

### L5 - Test asset preparation fails the build and is wired only for Debug

`app/build.gradle.kts:18-56` uses `check(source.isFile && ...)` with no `onlyIf`
and declares a dependency only for `mergeDebugAndroidTestAssets` (`:138-140`),
adding about 63 MB to the test APK. On a machine that has not run
`tools/prepare-handwriting-model.py`, `assembleDebugAndroidTest` now fails during
task execution instead of at run time; Release androidTest variants are not wired
at all.

### L6 - The frequency lookup bound is reused as the association row limit

`engine-dictionary/src/main/kotlin/dev/zeroinput/engine/dictionary/WordAssociationIndex.kt:26,59`
and `ime-core/src/main/kotlin/dev/zeroinput/ime/core/WordAssociationSession.kt:52-53`
use `PersonalFrequencyStore.MAX_LOOKUP` for both the frequency query size and the
association row count and validation bound. Changing either meaning silently
changes the other, and it makes `engine-dictionary` depend on a frequency-port
constant.

### L7 - Dead assignment and unreachable safe call

`app/src/main/kotlin/dev/zeroinput/ime/settings/SettingsScreenView.kt:293` sets
`textSize = 12f` and `:298` overwrites it with `13f`.
`user-data/src/main/kotlin/dev/zeroinput/userdata/UserLexiconRepository.kt:82`
uses a safe call on `frequenciesFor(...)`, which has a non-null return type.

## Observations

- Personal candidates and frequency ranking take effect on the next key, not
  immediately. This matches `docs/architecture.md:150`, `InputSessionControllerTest`
  and the new `WordAssociationSessionTest` cases; it is an intended contract.
- The three `return null` branches in `ClipboardImportIntent` are a fix, not a
  regression: `HEAD` fell back to `ClipboardImportText.parse(value)`, while the
  tracked `ClipboardImportIntentTest:40-49` already asserts null for those
  ClipData shapes. The change turns a red tracked test green.
- No CI job runs `connectedAndroidTest` or `assembleDebugAndroidTest`;
  `.github/workflows` only publishes dictionaries and resources. That is how a red
  instrumented test survived on `HEAD`, and why the `androidTest` edits in this
  diff (`SecureClipboardManagerPrivacyTest`, `InternalSearchInputTest`,
  `PageReferencePlatformTest`) cannot be validated by CI.
- `app/src/main/kotlin/dev/zeroinput/ime/settings/SecureClipboardManagerView.kt:105-112`
  detaches, submits `null`, clears the pool and re-attaches. This is correct, since
  `AsyncListDiffer` does not clear synchronously, but each toggle rebuilds the view
  tree and loses scroll position.
- The package-less bounds-only exemption in
  `app/src/main/kotlin/dev/zeroinput/ime/ai/page/PageReferenceService.kt:86-106` only
  admits system-originated events, and `WINDOWS_CHANGE_BOUNDS = 1 shl 3` matches
  the platform value. The threat model is updated and three new cases cover both
  directions.

## Validation

The following records the baseline state before the remediation below.

- `:ime-core:testDebugUnitTest`, `:user-data:testDebugUnitTest` and
  `:app:testDebugUnitTest`: 87 result files, 572 tests, 0 failures, 0 errors,
  2 skipped.
- `:app:compileDebugKotlin`, `:engine-rime:compileDebugKotlin` and
  `:ime-ui:compileDebugKotlin` compile. Remaining warnings are pre-existing
  deprecations (`DeviceAgentAccessibilityService`, `RimeGlideLexicon.kt:127`) and
  are unrelated to this diff.
- Environment note: the host runs `JAVA_HOME=D:\env\jdk21` while the build requires
  toolchain 17 and does not detect it, so every task must be invoked with
  `-Porg.gradle.java.installations.paths=D:\env\jdk17`; without it dependency
  resolution fails before any task runs.

## Not verified

`privacyCheck`, `:app:lintDebug`, `assembleDebug`, `assembleRelease`,
`-PrequireRime=true` native builds, and all instrumented or device tests were not
run. M2 geometry, L5 build wiring and cold-start behavior after removing the
completion callback therefore rest on static evidence only. No validation document
under `docs/` covers this diff, so the AGENTS 13 documentation sync is still open.

## Resolution: 2026-10-22

- M1 fixed: native update and candidate-page calls now convert display candidates
  and committed text during the existing JNI read, avoiding one JNI lock/convert
  round trip per candidate. Association normalization continues to use the
  dedicated single-text conversion API.
- M2 not changed: this host has no attached Android device or emulator. Compact,
  floating and one-handed layouts still need verification under gesture and
  three-button navigation; no geometry defect is claimed as confirmed.
- L1 retained: a failed user-requested clear remains fail-closed until an
  explicit successful retry. Its privacy behavior and regression test are
  intentional; exposing a new settings state was outside the confirmed scope.
- L2 retained and documented in `architecture.md`: a commit before public index
  readiness does not produce a row and is not replayed after loading completes.
- L3 fixed: association warmup is requested through `InputSessionController`,
  which owns the editor-layout and privacy gate.
- L4/L5 fixed: one androidTest asset manifest supplies build-time hash
  validation and runtime test verification; pinned test assets remain mandatory,
  and preparation is wired to AndroidTest asset merge tasks.
- L6 fixed: the association count now belongs to `NextWordPredictor`, separate
  from the personal-frequency lookup limit.
- L7 fixed: the dead text-size assignment and redundant safe call were removed.

Validation: engine-dictionary, ime-core, user-data and app unit tests passed
(358 tests, 2 skipped); app/engine-rime Kotlin compilation; pinned test-asset
preparation plus Debug androidTest asset merge and Kotlin compilation;
`privacyCheck`; `:app:lintDebug`; and `-PrequireRime=true` Debug native builds for
arm64-v8a, armeabi-v7a and x86_64 all passed. Robolectric was run with Java 21
while Gradle used the configured Java 17 toolchain. No device geometry check or
instrumented test execution was available.
