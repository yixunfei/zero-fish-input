# Keyboard appearance validation

Validated on 2026-10-04. Default decoration uses cool gray/blue, with graphite
surfaces in dark mode. Palette, six materials, borders, radius, spacing, height
and background are independent. Classic intentionally keeps square, gapless keys.

## Automated checks

From the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

The full debug unit-test run passed (405 tests, zero failures/errors). Final
appearance refinements were rechecked with app/ime-ui unit tests, Debug/test APK
builds, privacyCheck and Lint. Both merged main manifests retain the approved
permission set, disabled backup, no cleartext traffic and a nonexported appearance
Activity; Release remains nondebuggable. No privacy/test/lint rule was relaxed.

The connected API 37 x86_64 emulator (1080x2400, density 420) passed all 16 targeted
instrumentation tests:

- `settings.KeyboardBackgroundTest`: stream byte caps, malformed content, URI
  scheme/cancellation rejection, downsampling, encrypted round trip, failed
  replacement preserving the old image, truncated ciphertext, key loss, deletion
  racing an import, cancelled callbacks and durable deletion after UI detachment.
- `KeyboardMaterialTest`: all six styles and both border states at 320/411/800 dp
  in light/dark mode, distinct rendering, identical key hit geometry, key actions,
  persisted appearance round trips and background-only opacity.
- `KeyboardAppearanceTest`: palette/height persistence, actual preview geometry,
  attached light/dark keyboard rendering and public candidate states.
- `AppearanceSettingsLayoutTest`: portrait/landscape settings, full visible
  preview, reachable bottom controls and secure window flag.
- `KeyboardTouchTest`: continuous hit targets, overlapping pointers, cancellation
  and literal symbol digits.
- `KeyboardWindowIntegrationTest`: floating/single-hand/docked modes, outside-touch
  pass-through, rotation and orientation-specific placement.

Final build/device logs are local generated artifacts:
`build/appearance-complete-build.log`, `build/appearance-complete-device.log`.
The earlier full unit run is `build/appearance-final-verification.log`.
Public-only rendering captures and `styles-preview.png` are under
`build/appearance-fixtures/`; they are not source assets or private-data snapshots.

## Remaining device checks

Physical devices, API 26/27, OEM document pickers and Release APK builds were not
run for this change. The x86_64 Debug APK is an emulator test artifact, not a
signed release. No native implementation or ABI contract changed.

On target devices, choose a local portrait JPEG with EXIF orientation, PNG and
WebP; check center cropping and the 0/50/100 opacity values. Verify border/style
combinations, image blur, readability over bright/dark photos, and keyboard typing
after leaving settings. Cancel the picker/import, reject an oversized/corrupted
file, rotate during import, background/return to settings, and delete/reset then
restart: no removed image may return. Deletion failures should remain retryable.
Provider/native decoder cancellation is best-effort; a stalled provider can occupy
the single bounded image worker while ordinary typing remains available.

See [ADR 0018](adr/0018-keyboard-materials-and-private-backgrounds.md),
[architecture](architecture.md) and [threat model](threat-model.md).
