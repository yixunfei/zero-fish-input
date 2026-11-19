# Stego panel presentation and interaction (2026-11-19)

Scope: the existing isolated stego workspace UI, preserving the prior uncommitted
audit and caret repairs. No cipher/envelope, credential derivation, authentication,
clipboard adapter, persistence or permission change.

- Full-width labelled draft surfaces use real hints, an active outline and an
  editing marker. They remain focus-free and receive only the host's masked
  private displays. Public long text expands without changing the draft. Explicit
  private reveal expands the secret/credential display inside the scroll area.
- Cipher controls start collapsed with an ordered public summary. Secondary
  actions/settings fold independently. Both states survive ordinary renders and
  reset on workspace close. Disclosure arrows have expanded/collapsed labels.
- Mode/clear and execution rows stay fixed. Portrait detail height requests
  320 dp, still constrained by the existing viewport policy. Field switching
  scrolls to the target; preview/result transitions return to the beginning.
  Working commands are disabled and labelled Working; Clear remains available.
- `StegoFieldView` owns presentation only. Draft state, masking, caret projection,
  lifecycle revocation, long-press source approval and source reads remain with
  their existing owners. Background drawables are reused across key renders.

## Validation

Final command from the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:testDebugUnitTest :ime-ui:testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

Passed: real-Rime x86_64 Debug and test APKs; 606 app/UI unit passes, two opt-in
live-provider skips, zero failures/errors; privacyCheck/testPrivacyBoundary; Debug
Lint with zero issues. This is scoped verification, not all-module or Release
acceptance. Log: `.tmp-evidence/stego-panel-ui-complete-build.log`.

API 37 x86_64 acceptance passes 14 distinct platform tests: new field/disclosure
interactions, existing caret/composition, toolbar/results, scroll/48-dp targets,
paste routing, variable random credentials and cipher ordering/revocation. The
two layout probes and both caret tests also pass at 240x480 dp, dark, font 1.3,
and at 640x320 dp, landscape/light, font 1.0 (eight repeated executions).
Configuration status confirms the dimensions, orientation and themes. Public
fixture screenshots were visually inspected for each configuration.
Installed main/test APK SHA-256 values match the current build artifacts; see
`.tmp-evidence/stego-panel-ui-installed-identity.json`. Display size, font scale,
rotation and night mode were restored to their captured values; the task-owned
emulator was stopped. No physical device was modified.

Evidence in `.tmp-evidence/`: `stego-panel-ui-accepted-device.log`,
`stego-panel-ui-small-dark.log`, `stego-panel-ui-accepted-landscape.log`,
`stego-panel-ui-normal-layout.png`, `stego-panel-ui-small-dark.png`,
`stego-panel-ui-landscape-light.png`. An earlier landscape attempt was actually
portrait due to combining size override and rotation; only the run reporting
orientation 2 and 640x320 counts as landscape acceptance.

Not run: ARM/OEM or older-API runtime, full release/three-ABI packaging and real
third-party transport. No Git commit, publication or external message is included.

The first device attempt installed historical `outputs/apk` files and could not
load new test classes; it is not acceptance evidence. Current injected-ABI builds
emit their APKs under `app/build/intermediates/apk`. A subsequent long-text test
matched an invisible duplicate; it now explicitly targets the shown field/control.
Neither harness issue was treated as a production pass or used to weaken checks.
