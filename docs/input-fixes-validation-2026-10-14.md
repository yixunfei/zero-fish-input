# Input interaction fixes validation (2026-10-14)

## Behavior

- With the composition caret between `c` and `h`, Split inserts ASCII `'`,
  displays `c'h` immediately, and leaves the caret after the delimiter. Backspace
  removes the delimiter without committing or losing the suffix. The apostrophe
  on the symbols page uses the same composition input path.
- Expanded candidates alone own page requests while expanded. Prefetch follows
  the browsing direction, uses packed row distances, and continues across bounded
  window eviction during a drag or fling. Existing candidate/pixel anchors remain.
- The temporary input diagnostics implementation and its rendering hooks and
  strings are removed from all builds.

## Automated validation

Executed from the repository root on Windows:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck `
  :app:lintDebug -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" --no-parallel
```

Build, 632 unit tests, privacy checks, and Lint passed (no Lint issues).
The final strengthened scrolling test APK was rebuilt separately.

On an Android 16 x86_64 emulator, 31 instrumentation tests passed across:

- `CandidateContinuousScrollTest`
- `PinyinEditingInteractionTest`
- `CompositionEditingRegressionTest` (real native Rime)
- `KeyboardToolbarRegressionTest`
- `ReleaseUiRegressionTest` (UI assertions executed in Debug)
- `KeyboardNavigationGestureTest`
- `InputExperienceVisualTest`

The strengthened `CandidateContinuousScrollTest` then passed separately: one
backward fling and one forward fling each load at least three pages without
requesting the opposite direction. Scrolling the horizontal strip while expanded
does not request pages. This ownership assertion failed against the preceding
app build before the fix. The Split unit regression also failed before the fix.

Visual fixtures cover 240, 320, 360, 411, and 720 dp widths, light/dark themes,
portrait/landscape configurations, composition selection, and long candidates.
Selected small-screen, dark expanded-list, and landscape screenshots were
visually inspected. Only fixed public fixture text was captured.

Local logs and screenshots are under ignored `build/input-fixes-*`; the old-app
scroll regression log is `build/candidate-scroll-old-app.log`.

## Remaining device acceptance

No physical-device, ARM ABI, actual device-rotation transition, or Release build
was run for this change. No JNI/CMake/ABI implementation changed. On the target
phone, confirm:

1. Type `ch`, tap between the letters, and press Split: `c'h` appears immediately
   with the caret after `'`; Backspace restores `ch`.
2. Repeat using the symbol-page apostrophe and after selecting a composition range.
3. Expand a long candidate list; fling several times in each direction, reverse
   direction, and select a candidate. Check for jumps, stalls, or wrong selection.
4. Repeat in light/dark themes and after rotating the phone. Check that the
   diagnostic box is absent, including before typing.
