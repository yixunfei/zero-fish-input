# Input experience optimization validation

Date: 2026-10-09

## Scope and outcome

Implemented the approved [plan](input-experience-optimization-plan.md). The changes
focus on candidate browsing, reliable touch delivery and predictable navigation:

- Same-input candidate refresh preserves the visible candidate ID and pixel
  offset. New input starts at the first candidate. Long strip entries have a
  bounded width; expanded entries wrap and expose the complete phrase.
- Key release dispatches immediately. Small finger drift stays within a bounded
  tolerance; overlapping one-shot Shift presses keep their captured actions.
  Page changes and cancelled gestures still invalidate held keys.
- A dedicated text number pad supports repeated digits, direct symbols and return
  to the original language. Explicit language switching commits unfinished input
  literally; rejected commits retain composition. Settings/privacy cancellation
  remains a separate path.
- The idle toolbar contains tools, numbers, AI and expressions. Secondary tools
  remain accessible in the tools row; its return control stays fixed while the
  row scrolls. Opening tools preserves reconversion while still invalidating
  pending private interactions and refreshing session privacy.
- Candidate/header bindings avoid redundant updates. Emoji updates use bounded
  range notifications and retain immediate stale/private-holder clearing.
- Responsive layout uses stable window bounds instead of the collapsed IME's
  previous height. This fixes handwriting layout oscillation. Very short
  landscape windows reserve editor space and fit all key rows inside the panel.

No new dependencies, network paths, permissions or persisted formats were added
by this optimization. Existing unrelated workspace changes were preserved.

## Reproduced regressions

Before implementation, four focused UI tests failed: slight finger drift dropped
a key, overlapping shifted presses lost the second key, same-input candidate
refresh reset horizontal scroll to zero, and a long first candidate occupied too
much of the strip. The explicit-language-switch core regression also failed.
Baseline logs are `build/ux-baseline.log` and `build/ux-core-baseline.log`.

Integration testing additionally exposed lost reconversion after opening tools
and handwriting switching repeatedly between compact and stacked layouts. Both
were fixed and covered by the final device regression run.

## Environment and verified artifact

- Windows host, JDK 17, Android SDK at the configured local SDK location.
- Android 16 x86_64 emulator `emulator-5554`; no physical device connected.
- Debug build using actual Rime with `-PrequireRime=true`.
- APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`.
- SHA-256: `c2915653815f00f6b0528a5b245f914d1421137521ad3e0dfb810e8760af9656`.
- The runner checked the installed APK hash before and after device tests.
  Evidence: `build/device-apk-verification.json`.

The APK is a Debug emulator artifact, not a signed release or an ARM phone build.

## Checks

The following command completed successfully; the corresponding Android unit
test XML reports contain 546 tests, zero failures/errors and zero skipped tests.
Gradle reused up-to-date results where sources had not changed.

```powershell
$env:JAVA_HOME='D:/env/jdk17'
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug :ime-ui:lintDebug -PrequireRime=true --no-parallel '-Pandroid.injected.build.abi=x86_64'
```

Build/check evidence: `build/ux-final-validation.log`.

The final device run passed **74 tests** in 113.556 seconds. It covered rapid
full-pinyin/nine-key/fallback typing, warmup, real editor language handoff,
number/symbol return, overlapping/cancelled touch, stale candidate selection,
candidate paging/density, reconversion, internal search isolation, handwriting,
expression updates, AI draft navigation, floating windows and real rotation.
AI checks used local public fixtures; no provider request was needed.

Reproduction command (run after the build above):

```powershell
$classes = @(
    'InputExperienceVisualTest', 'InputExperienceRegressionTest',
    'KeyboardToolbarRegressionTest', 'KeyboardTouchTest',
    'KeyboardExperienceTest', 'KeyboardNavigationGestureTest',
    'CandidateDensityTest', 'InputPipelineTest',
    'KeyboardEditorIntegrationTest', 'KeyboardInteractionIntegrationTest',
    'KeyboardWindowIntegrationTest', 'InternalSearchInputTest',
    'CompactLandscapeTest', 'CompositionEditingRegressionTest',
    'ExpressionPanelTest', 'RgiEmojiTest', 'InputLatencyTest',
    'AiImeInteractionTest', 'AiWorkbenchPanelTest'
) | ForEach-Object { "dev.zeroinput.ime.$_" }
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass ($classes -join ',')
```

Device evidence: `build/ux-final-acceptance.log`.

## Layout review

The visual test generated 60 images using attached Android Views and public
synthetic text: 240/320/360/411 dp portrait and 720 x 320 dp landscape, light/dark,
letters/numbers/symbols/tools/candidates/expanded candidates. It asserts stable
page height and complete, non-ellipsized expanded phrases. Images are in
`build/input-experience/` (generated, not committed).

Representative narrow number, portrait candidate, light letter/tool, dark symbol
and landscape expanded images were inspected. Key labels and full expanded
phrases render correctly. Extremely long candidates can occupy the visible grid
height at narrow widths; additional entries remain reachable by scrolling.
Separate geometry tests cover 1.3 font scale, all existing themes/heights and
short landscape sizes 540/592/800 x 280/320/360 dp.

## Timing evidence and interpretation

All timing samples use fixed public fixtures. No user text or private data is
logged. Native measurements use 1,140 warmed key samples per configuration.
The layout fixture measures render/measure/layout, not physical screen response.

| Measure | Baseline p50 / p95 (ms) | Final p50 / p95 (ms) |
| --- | --- | --- |
| Native default pinyin | 0.691 / 6.653 | 0.714 / 6.927 |
| Native strict pinyin | 0.348 / 0.689 | 0.368 / 0.755 |
| Candidate render/layout | 0.145 / 0.274 | 0.140 / 0.339 |

These runs do not demonstrate a native-engine or layout latency improvement.
Emulator scheduling varies; the benefit established here is correct delivery of
previously dropped gestures and removal of disruptive refresh/navigation behavior.

The final attached IME/editor fixture measured dispatch p50/p95 at 5.798/22.671 ms,
editor updates at 3.388/20.851 ms, and next-frame arrival at 12.404/22.825 ms.
Maximums were 37.763, 35.578 and 37.974 ms respectively. These are fixture
MotionEvents delivered to actual key views, not sensor-to-display measurements.
They are not evidence that all devices now meet a 16.7 ms frame budget.

## Remaining acceptance and maintenance notes

- ARM builds, three-ABI packaging and Release validation were not run for this
  UI iteration. No JNI implementation was changed by this task.
- Physical touch feel, haptics, OEM IME hosts and target-app latency require a
  phone. Repeat rapid alternating-finger typing, long candidate browsing,
  Chinese/English and digit/symbol return, search/handwriting/AI return, rotation,
  and password/PIN transitions on that device.
- Authentication/system-credential flows were not manually re-accepted; existing
  privacy unit tests and stale-action regressions passed. This task does not
  change the authentication protocol or private storage.
- The existing `ZeroInputService.kt` remains 1,842 lines, below the exceptional
  2,500-line cap but above the usual 1,500-line target. This task adds only a
  narrow navigation callback in that lifecycle composition root. A separate
  responsibility-based extraction would reduce coordination risk; unrelated
  service refactoring was not mixed into the input fixes.
- Historical September reports retain their original environment and claims;
  they do not describe this artifact or substitute for this run.
