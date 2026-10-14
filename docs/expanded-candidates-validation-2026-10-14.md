# Expanded candidate layout validation

## Scope

The expanded candidate panel now supports adaptive columns or a single-column list,
compact/standard/spacious density, a 16-24 sp text-size setting, and optional
separators. Defaults are spacious density, 18 sp, adaptive columns, and separators
enabled. The Keyboard Appearance page includes a fixed public preview and keeps all
existing appearance choices.

## Automated checks

- `:ime-ui:testDebugUnitTest` passed, including row packing and appearance model tests.
- `:app:testDebugUnitTest` passed, including appearance preference defaults,
  persistence, bounds, and invalid-value fallback.
- `privacyCheck`, `:app:lintDebug`, `:app:assembleDebug`, and
  `:app:assembleDebugAndroidTest` passed with `-PrequireRime=true` and the x86_64
  ABI before the final test-only fixture adjustment.
- On the Android 16 emulator (`emulator-5554`), candidate density tests passed for
  240/360/720 dp widths in light and dark themes. Long candidates wrapped, selection
  stayed aligned, and changing appearance preserved the visible scroll anchor.
- The settings preview test passed in portrait and forced landscape configurations;
  the preview remained visible and the controls remained reachable.
- The continuous-scroll regression test passed independently after the fixture was
  stabilized: one forward and one backward fling crossed at least three page
  boundaries without requesting the opposite direction.

The final combined Gradle command was attempted again after the last test-fixture
edit, but Gradle Wrapper could not download `gradle-8.14-all.zip` because the host
network timed out. No production source changed after the successful build checks;
the remaining limitation is the unavailable repeated full-run confirmation.

## Manual visual checks

The emulator screenshots cover the default spacious layout, narrow dark mode, and a
real landscape settings preview. Candidate cells remain opaque against custom
keyboard backgrounds, have stable touch heights, and show readable separators.

## Remaining coverage

The device run uses the Android 16 emulator and x86_64 native build. A physical ARM
device and the complete release packaging command were not run for this UI-only
change.
