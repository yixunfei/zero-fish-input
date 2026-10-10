# Keyboard experience validation

## Candidate density update (2026-10-09)

Expanded candidates use measured word widths with a 48dp minimum touch width and
distribute each row's remaining space. Strip padding is 6dp per side; shared panel
icons use 14dp padding in their existing 48dp targets. Composition secondary
actions live in the left tools entry; the right overflow menu is removed.

Verified `:ime-ui:testDebugUnitTest`, `:app:assembleDebug`, `privacyCheck`, and
`:app:lintDebug`. The API 36 emulator passed 11 tests in `CandidateDensityTest`,
`PanelInteractionRegressionTest`, and `KeyboardToolbarRegressionTest`, including
240/360/720dp light/dark packing, continuous prefetch/anchor preservation, and
tool/AI navigation. Public fixture renders are under `build/candidate-density`.
Physical-device rotation and Android 8 rendering remain manual checks.

Date: 2026-09-07. Baseline: `aab7366`.

## Layout and behavior

| Measurement | Baseline | Updated |
| --- | --- | --- |
| Portrait toolbar plus candidate allocation | 120dp | 72dp shared header |
| Landscape toolbar plus candidate allocation | 96dp | 48dp shared header |
| Standard portrait keyboard content | 328dp | 280dp |
| Portrait key row heights | 52dp | 48 / 52 / 60dp |
| Landscape key row heights | 48dp | 48 / 48 / 52dp |

Content heights exclude system navigation/cutout insets and the optional clipboard
guard reminder. Pixel measurements add each rounded row height individually.
Tools, composition and candidate changes keep keys at the same vertical position.
Expanded candidates reuse the selected keyboard height.

Settings provide Classic, Minimal gray, Mint and Rose with light/dark resources
and a real keyboard preview. Numeric, phone and date/time editors use literal
number keys. Enter labels and controller actions share the same EditorInfo policy.
Both symbol pages have backspace and direct letter-page access. One-shot Shift,
timed caps lock and geometry-based key reuse preserve gesture cancellation.

## Verification

Environment: Windows, JDK 17, SDK 36, the pinned librime source tree, x86_64 API 37
emulator `emulator-5554`. All commands below run from the repository root.

```powershell
./tools/test-input-experience.ps1 -Serial emulator-5554
./tools/package-test-apk.ps1
./gradlew.bat :ime-ui:lintDebug -PrequireRime=true --no-parallel
```

The complete device suite passed 109 tests in 124.768 seconds. The packaging
script passed JVM/Android unit tests, `privacyCheck`, Debug/Release merged
manifest checks, app Lint and the universal Debug build with `requireRime=true`.
The UI module's standalone Lint also passed. The APK signature, allowed
permissions and three native ABIs were verified by the packaging script.

The final universal APK was installed on the emulator and passed four additional
smoke tests covering editor actions, timed Shift holds, full-screen rotation and
attached kaomoji search (10.867 seconds).

New device coverage includes:

- Stable header and expanded-panel geometry, plus retired-view callbacks.
- All four palettes, three heights, 320dp portrait and 800dp landscape layouts,
  light/dark mode and 1.3 font scale; key text fit and foreground contrast.
- Attached light/dark screenshots of idle/candidate states and settings preview.
- Theme/height persistence and exclusive radio selection.
- Numeric/phone flags, literal digits and actual numeric-to-text editor restart.
- Enter action dispatch and suppression by `IME_FLAG_NO_ENTER_ACTION`.
- Key reuse, old gesture rejection, one-shot Shift, timed hold and cancellation.
- Symbol-page backspace. Existing full-screen, expression search, privacy,
  encryption, clear/write races, engine and continuous-input tests also passed.

Only public constructed content is used for screenshots and editor assertions.
Generated screenshots remain under `app/build/`, outside source control.

## Input measurements

The same public-fixture instrumentation measured dispatch, editor observation and
the next frame separately. Values below are microseconds, p50 / p95.

| Measurement | Baseline | Updated full suite |
| --- | --- | --- |
| Key dispatch | 7966 / 12132 | 8996 / 12814 |
| Editor observation | 4332 / 9395 | 5302 / 9935 |
| Next frame | 15036 / 16483 | 14317 / 16907 |
| Candidate layout | 71 / 136 | 74 / 139 |

These are single emulator runs, not a statistically controlled benchmark. The
updated measurements do not establish a latency improvement: dispatch and editor
medians increased, while the frame median decreased. Key/view reuse reduces
reconstruction on Shift; no claim about lower end-to-end latency follows from
that implementation change. Rapid input retained every fixture phrase in full
pinyin, nine-key pinyin and the reference dictionary engine.

## Test artifact and remaining coverage

Artifact directory: `app/build/outputs/test-apk/20260907-080219/`.

- `zero-finish-input-0.1.0-debug-universal.apk`
- `zero-finish-input-0.1.0-debug-notices.zip`
- `SHA256SUMS.txt`

APK SHA-256:
`e0996204cc50a2bff4d43c1c64e32b2ffe971c417ae6ce846ea7dc2064f04557`.

This is a debuggable, Debug-signed test package. The artifact contains arm64-v8a,
armeabi-v7a and x86_64 libraries. Only x86_64 executed on a device in this round;
ARM hardware, OEM editors, Android 8 platform behavior and physical-device
latency remain unverified because those devices were not connected. This round
did not build or sign a final minified Release APK; release signing is outside
the keyboard task.

On a target phone, select each appearance and height, rotate a full-screen editor,
enter a long pinyin phrase, expand candidates and return through tools. Confirm
the host editor stays visible and no keys move or clip. Repeat in number, signed
decimal, phone, PIN, password and multiline search fields; test Shift holds,
backspace cancellation and both symbol pages. Use public fixture text only.
