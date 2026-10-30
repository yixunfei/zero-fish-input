# Reported input bugs: 2026-10-30

## Scope and implementation

User approved the five-item repair plan. Pre-existing uncommitted audit changes
were preserved. No storage format, permission, exported component, native library
or authentication policy changed.

1. Symbols: eight bounded offline pages cover common punctuation, Chinese marks,
   mathematics, arrows/shapes, units and phonetic marks. Page buttons and horizontal
   swipes cycle through every page; swipe completion cancels the touched key.
   Multi-pointer gestures cannot insert a symbol or change the page.
2. Private selection copy: a 96 dp toolbar control precedes other tools and reuses
   the authenticated import path. Host PROCESS_TEXT ordering cannot be controlled
   by the IME. Password/PIN gates and the existing explicit-save flow still apply.
3. Deletion: preserve style metadata in the bounded cursor read and recognize
   replacement spans with a known left boundary. Textual label-like suffixes
   delegate one Delete gesture to the host. They never cause guessed bulk
   deletion. Explicit key rejection falls back to one character; exceptions and
   accepted gestures never trigger a duplicate edit. Recheck connection after
   the read. Ordinary formatting and clipped left spans do not expand deletion.
4. Chinese Shift: comma and period display and commit literal half-width values;
   one-shot Shift resets after insertion. Caps lock preserves shifted behavior.
   English punctuation keeps its original engine route.
5. Learning: locally edited dictionary candidates previously skipped recordUse.
   Resolve active replacement IDs and learn through the encrypted lexicon worker;
   combine learned frequency with the configured public weight by maximum.
   Tests cover three clicks, reconstruction from saved data, candidate order,
   unchanged dictionary weights, disabled learning, and disabled/deleted edits.

## Regression evidence

Before production fixes, the three initial keyboard regressions failed. The
replacement-span regression, replacement-candidate learning regression and two
host-label deletion regressions also failed before their corresponding fixes.
A persistence check required Robolectric for Android JsonReader; this test-runtime
failure was corrected without weakening the assertion.

## Verification

JVM reports: 903 tests, 901 passed, zero failures/errors, two live-provider tests
skipped because explicit test credentials were not supplied. Android API 37
x86_64 emulator: 40 platform tests passed. Final Gradle command succeeded;
privacyCheck, Debug/Release merged-manifest checks, Lint (no issues), real-Rime
Debug and instrumentation APK builds all passed. `git diff --check` passed.
The final build log is `.tmp-evidence/reported-bugs-delivery.log`; platform results
are in `.tmp-evidence/reported-bugs-device.log`.

Commands from the repository root:

```powershell
./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

Emulator suites: KeyboardTouchTest, KeyboardExperienceTest,
KeyboardPlacementAndGlideTest, KeyboardNavigationGestureTest,
AndroidEditorConnectionTest, AndroidEditorConnectionFailureTest,
InputExperienceVisualTest and ClipboardSelectionCopyTest. Installed current
intermediate APKs with `adb install -r -t`, without uninstalling or clearing data.
The visual test rendered 240/320/360/411/720 dp in light and dark themes, including
landscape configuration. Four exported fixed-fixture images were visually
inspected: 240 dp dark letters, 320 dp light letters and Chinese symbols, and
720 dp dark Chinese symbols. Local images are under
`.tmp-evidence/reported-bugs-visual/`.

Debug APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`, 114291028 bytes.
SHA-256: `7d7acdf03b7ead350979cee9073d78909ef60cc8e7fde9c7b9d834b8ccbd007e`.
Test APK: `app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`.
The older `app/build/outputs/apk/androidTest/` file was not used.

Local logs: `.tmp-evidence/reported-bugs-*.log`,
`.tmp-evidence/learning-bug-baseline.log`, `.tmp-evidence/label-delete-baseline.log`.
These are local evidence, not release artifacts.

## Limits and manual acceptance

- Test common social editors with their own custom emoji. Editors that strip
  replacement spans and ignore Delete key events may still delete labels one
  character at a time. No particular third-party app/version was supplied or
  installed for acceptance; a generic label pattern cannot safely guarantee
  whole-token deletion in every editor.
- On a physical device, select text in a normal incognito editor and use private
  copy, authenticate, review and save. Repeat in a password field (must be
  unavailable). Confirm the host selection-menu order does not affect the shortcut.
- Verify OEM keyboards/windows, physical rotation and real biometric/credential
  flow separately. Configuration-based layouts are not physical-device evidence.
- No Release build, ARM device acceptance or distribution APK is claimed.
  The generated x86_64 Debug APK is for emulator testing only.
