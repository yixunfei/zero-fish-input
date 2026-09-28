# Interaction usability validation

Date: 2026-09-28

## Approved behavior

- Fuzzy pinyin has a master switch in settings and a keyboard toolbar shortcut.
  Turning it off preserves every selected rule. Turning it on reuses that
  selection. With no selected rules, the shortcut opens the rule settings.
  Changes during composition take effect after that composition ends.
- Back navigation first closes tools, expanded candidates or emoji search, then
  returns from the secondary panel to the keyboard. More symbols returns to
  symbols before returning to letters. Only the keyboard root yields to the
  platform to dismiss the IME. The toolbar, legacy Back key and Android 13+
  Back callback share the same navigation decisions.
- Horizontal swipes change emoji categories, including from an empty category.
  Expanded candidates move by a viewport and request another engine page at
  the list edge. The single candidate row retains horizontal scrolling and
  requests another page when a new swipe starts at an edge. Swipe ownership
  cancels the pending child click to avoid inserting an item accidentally.
- Ordinary typing and Backspace after individual-syllable candidate selection
  restore the temporary engine caret to the end of the uncommitted composition.
  In the fallback engine, newly appended pinyin is deleted before undoing the
  preceding selected segment.
- Moving the cursor in the external editor finishes the existing visible
  composition without reading, rewriting or learning that text. Subsequent
  deletion uses the new editor position. This handoff ends conversion; it does
  not continue live pinyin conversion at an arbitrary external cursor position.

These changes use the existing session, settings invalidation and bounded
candidate routes. They add no networking, permission, clipboard access, personal
data format or background collection. See [architecture](architecture.md),
[threat model](threat-model.md), [Chinese configuration ADR](adr/0004-chinese-input-configuration.md)
and [composition ADR](adr/0010-composition-editing-and-candidate-continuation.md).

## Regression evidence

| Area | Regression coverage | Result |
| --- | --- | --- |
| Fuzzy pinyin | Preserved rule mask, disabled effective matching/schema, settings persistence and keyboard shortcut | Passed |
| Fallback editing | Select a segment, append input, then delete the appended input before undoing selection | Failed before the fix; passed after it |
| Native editing | Enter individual-syllable selection, then repeatedly delete or continue typing | Failed before the fix; passed after it |
| Editor handoff | Move the external editor cursor during composition, preserve text and delete at the new position | Passed |
| Back navigation | Emoji search to browser to keyboard to hidden IME; tools and symbols hierarchy | Passed with Back key and view actions |
| Horizontal gestures | Empty emoji category, candidate viewport/page navigation, child-click cancellation | Passed |
| Appearance and input | Existing toolbar, touch, editor, nine-key, options, theme and geometry regressions | Passed |
| System edge Back | Same hierarchy using injected screen-edge touch | Not verified; see limitation below |

The native/fallback failure logs are `build/composition-device-before.log` and
`build/interaction-regression-before.log`. They contain public test fixtures only.

## Automated checks

Run from the repository root with the configured Android SDK and JDK 17:

```powershell
./gradlew.bat testDebugUnitTest :engine-api:test privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest -PrequireRime=true --no-parallel "-Pandroid.injected.build.abi=x86_64"
./tools/package-test-apk.ps1
```

Both commands passed. Packaging ran its checks without `-SkipChecks`, including
the JVM and Android Debug/Release unit-test tasks. The final XML reports contain
636 test executions with zero failures, errors or skips: 302 Android tests for
each of Debug and Release, plus 32 JVM tests (334 cases when variants are counted
once).

`privacyCheck`, Debug Lint, the Debug APK, the instrumentation APK and Release
manifest processing passed. The package script also verified the Debug signature,
application ID, permission allowlist and all three supported ABIs. A Release APK
was not built or validated in this task.

Build logs:

- `build/interaction-final-checks.log`
- `build/interaction-package.log`

### Android device tests

Environment: Android 36 x86_64 emulator, serial `emulator-5554`. Test input is
fixed public text. Editor integration disables personalized learning and restores
the saved IME and settings after each fixture.

The following 50 tests passed in two instrumentation runs:

- 9 tests: `CompositionEditingRegressionTest`, `KeyboardNavigationGestureTest`.
  Result: `build/interaction-device-final.log`.
- 41 tests: `NativePinyinTest`, `ChineseOptionsTest`, `NineKeyPinyinTest`,
  `SettingsPanelTest`, `KeyboardExperienceTest`, `KeyboardTouchTest`,
  `KeyboardToolbarRegressionTest`, `KeyboardAppearanceTest`,
  `KeyboardEditorIntegrationTest`, `KeyboardInteractionIntegrationTest`.
  Result: `build/interaction-platform-regressions.log`.

Re-run the three real-editor interaction tests after installing the Debug and
instrumentation APKs built by the command above:

```powershell
adb -s emulator-5554 shell am instrument -w -e class dev.zeroinput.ime.KeyboardInteractionIntegrationTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The appearance tests capture public previews for all four palettes in light and
dark mode, with and without candidates. The classic light/dark candidate captures
were also visually reviewed: candidates and keyboard keys remain legible without
overlap. Existing geometry checks cover small layouts. Rotation and OEM behavior
still require the manual checks below.

### Edge gesture limitation

The optional `-e edgeBack true` instrumentation variant injects a touchscreen
gesture from the left screen edge above the keyboard. Two of its three tests
passed; the Back hierarchy test timed out because no navigation occurred.
It is not included in the 50 passing tests. See
`build/interaction-edge-back-cold.log`.

The same injected edge swipe also failed to leave the Android Settings homepage,
whereas `adb shell input keyevent 4` returned to the launcher. This persisted
after a cold emulator boot with gestural navigation enabled. The control check
indicates a system gesture or injection limitation in this environment; it does
not establish that physical edge gestures work in the IME. The Android 13+
callback implementation therefore still needs acceptance on a device whose
system Back gesture works. The ordinary Back route is verified separately.

## Debug test artifact

- APK: `app/build/outputs/test-apk/20260928-154705/zero-fish-input-0.3.0-debug-universal.apk`
- Licenses: `app/build/outputs/test-apk/20260928-154705/zero-fish-input-0.3.0-debug-notices.zip`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- SHA-256: `ff0aff3ac74558a46e6b36555edf62f78fe46a938b2a4fcdf27069a6db5b1e4a`.

This is a Debug test package. It is not a signed Release artifact. Build outputs
and local logs are not source-controlled documentation dependencies.

## Remaining device acceptance

Use public fixture text, not private editor or clipboard contents.

1. On an Android 13+ physical device, first confirm that edge Back works in
   Android Settings. Then open emoji search in ZeroInput: successive edge Back
   gestures must close search, return to the keyboard and finally hide the IME.
   Repeat for tools, expanded candidates, AI candidates and nested symbols.
2. On Android 8-12, repeat the hierarchy using the system Back key. The legacy
   handler is implemented but was not exercised on an older OS in this run.
3. Repeat fuzzy shortcut, horizontal swipes and deletion on a narrow screen and
   in landscape, then rotate while a secondary panel is open. Check stable
   geometry, reachable controls, gesture cancellation and both theme modes.
4. Check TalkBack focus and labels for the fuzzy shortcut and panel controls.
   Horizontal swipe containers must not hide their interactive children.
5. In multiple host editors, type `nihao`, enter individual-syllable selection
   and delete repeatedly. Then move the host editor cursor within visible
   preedit and delete: text must remain in place and deletion must affect the
   new position. Repeat after switching fields and with a sensitive field;
   old candidates and actions must not survive the session change.

These unrun checks cover physical gesture dispatch, OEM editor differences,
older Android versions, rotation and accessibility services. They are not
claimed as passing by the emulator test results.
