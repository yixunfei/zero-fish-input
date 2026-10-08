# Input adaptability validation

Date: 2026-10-08

## Changes

- Immediate input uses the memory-only fallback. Native Rime startup and prepared
  sessions stay on a bounded worker; optional index/discovery work uses a separate
  bounded maintenance worker.
- A prepared engine can restore an unselected, bounded built-in full-pinyin
  composition. The regression types `n`, adopts the prepared engine, then types
  `ihao` and selects the result, checking that no literal `n` was committed.
  Restoration failure keeps the current composition. Selected segments are not
  transferred. Existing session, language, package, options and privacy validation
  still applies; stale engines are closed.
- Layout uses measured IME width and height. Secondary candidate commands move
  into an accessible overflow menu in narrow windows. Docked and one-hand layouts
  avoid reserving the navigation bar twice; floating and expanded panels retain
  safe system insets.

## Automated checks

The following commands completed successfully from the repository root:

```powershell
./gradlew.bat :ime-ui:testDebugUnitTest --no-daemon
./gradlew.bat :ime-core:testDebugUnitTest :app:testDebugUnitTest --no-daemon
./gradlew.bat :engine-api:test :engine-english:test :engine-rime:testDebugUnitTest :language-pack:testDebugUnitTest -PrequireRime=true --no-daemon
./gradlew.bat privacyCheck :app:lintDebug :app:assembleDebug --no-daemon
./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
./gradlew.bat :app:assembleRelease -PrequireRime=true --no-parallel --no-daemon
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.zeroinput.ime.KeyboardWindowIntegrationTest' --no-daemon
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.zeroinput.ime.FullscreenInputTest' --no-daemon
```

- Core, UI and app unit suites: 119, 34 and 205 tests respectively, no failures.
- Engine checks succeeded with Gradle reusing unchanged test outputs.
- API 36 x86_64 emulator `ZeroInputModelApi36`: both window integration tests and
  all three fullscreen tests passed. Coverage includes floating touch-through,
  docked/left-hand/right-hand placement, bottom bounds, orientation-specific
  settings, repeated rotation, and editing in an immersive host.
- The initial cold-emulator window run timed out waiting for the IME host. The
  individual rerun and subsequent complete two-test run passed. This observation
  is retained rather than treating startup reliability as proven.
- Narrow layout policy tests cover 220 dp and 360 dp widths as well as short,
  wide and full-height viewports. They do not simulate vendor split-screen hosts.
- A supplemental run of `RimeReadinessTest`, `NativePinyinTest`, `InputLatencyTest`,
  `InputPipelineTest` and `InputPanelTest` passed 21 of 22 tests. The pipeline test
  timed out because its hardcoded Chinese accessibility label failed to switch
  an English-language fixture to Chinese. The test now resolves the resource in
  the active locale and exercises reconversion through the narrow-screen overflow
  menu. No engine-readiness assertion was removed.
- The real IME pipeline suite passed 2 tests after the delayed-preparation regression
  was added. It measured fixed public fixtures at dispatch p50/p95 5.9/9.8 ms,
  editor update 2.9/7.2 ms and next-frame 10.0/24.9 ms on this emulator. Native
  key latency was measured separately at p50/p95 0.68/4.35 ms for the default
  public fixture and 0.33/0.63 ms with strict pinyin options. These are emulator
  measurements and are not a claim for game or physical-device load.
- The delayed-preparation test holds the bounded engine worker, opens a real IME
  editor, and asserts that `n` already has a composing span before preparation can
  finish. After releasing the worker it waits for native readiness, checks that
  the composing span still starts at zero, then types `ihao` and commits `你好`.
  Rerun the two pipeline tests with:

  ```powershell
  ./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass 'dev.zeroinput.ime.InputPipelineTest'
  ```

- Debug and unsigned Release APKs were built for arm64-v8a, armeabi-v7a and x86_64.
  Build success does not establish runtime behavior on physical ARM devices.

## Physical-device acceptance still required

No reproducible target application or physical device was supplied. The following
checks remain required on representative devices, using only fixed public text:

1. Open a fullscreen game editor in landscape. Show/hide the keyboard and rotate
   repeatedly. Check the bottom row, candidate strip, visible editor and navigation
   region in gesture and three-button navigation, in light and dark themes.
2. Open a split-screen or vendor side-panel editor. Resize its width while the
   keyboard is open, including approximately 220, 320 and 360 dp where supported.
   Verify every key, candidate selection/paging and overflow action is reachable;
   switch among floating, docked and one-hand placement without clipping.
3. Under sustained game/system load, open a new input session and immediately type
   `nihao`, including while Rime is preparing. Confirm one continuous composition,
   no literal first-letter commit, correct backspace, and one candidate commit.
   Repeat after hiding the keyboard, switching editors and rotating.
4. During preparation, switch to a password/no-learning editor or change privacy
   settings. Check that old composition/candidates disappear and no delayed result
   reaches the new editor. Return to normal text and verify input recovers.

No before/after physical-device latency claim is made. The existing IME service
remains above the usual 1,500-line target (1,831 lines, below the 2,500-line ceiling);
its lifecycle coordination was not split as an unrelated refactor. The viewport
policy is isolated in its own small module-owned source file.
