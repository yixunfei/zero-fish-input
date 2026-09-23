# Offline word association validation

Date: 2026-09-21

This page records the initial 286-pair implementation and its original test run.
The subsequent 1,698-pair expansion, frozen synthetic evaluation and current
verification are documented in [word-association-quality.md](word-association-quality.md).
Generated APK/report paths below may have been rebuilt; the old hash identifies
the initial artifact, not the latest file at that path.

## Delivered behavior

- A default-on input setting enables Chinese and English next-word suggestions.
  Successful IME commits can show at most eight public continuations; taps can
  chain. Space and Enter retain normal editor behavior and never accept an idle
  prediction. Continuing to type restores composing candidates.
- The bundled, project-authored seed has 157 Chinese pairs across 75 prefixes
  and 129 English pairs across 63 prefixes. English word boundaries and separators
  are explicit. Rime's active script normalizer supplies simplified/traditional
  output. Unknown contexts produce no predictions.
- Context is limited to 32 UTF-16 units in the active controller. It is neither
  persisted nor learned, and no surrounding editor text or private history is
  queried. Candidate selection does not record personal frequency.
- Failed commits cannot enter context or learning. Session, view, cursor,
  settings/privacy, language/engine and direct insertion boundaries clear it.
  A reproduced fallback bug was fixed: an unconsumed key committed directly by
  the editor now also breaks association context.

Implementation decisions and limits: [ADR 0013](adr/0013-offline-word-associations.md).

## Automated verification

The following root command passed with real pinned Rime sources:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug `
  -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" --no-parallel --console=plain --quiet
```

After the fallback fix, `:ime-core:testDebugUnitTest`, `privacyCheck` and
`:app:lintDebug` passed again, followed by APK rebuild and device execution.
The final JVM/Debug unit reports total **206 tests, zero failures**. Lint reports
`No issues found.` `privacyCheck` covers both Debug and Release merged manifests.
There is no new permission, exported component, dependency or native change in
this feature.

Association-specific unit coverage includes longest suffixes, language isolation,
English spacing/case, malformed public data, bounded context, memory wiping,
script deduplication, provider readiness, failed commits, stale clicks, ordinary
space/enter, privacy revocation, direct insertion and engine fallback.

Android 16 / API 36, x86_64 emulator `ZeroInputModelApi36` passed **22 tests**:

| Suite | Tests | Relevant checks |
| --- | ---: | --- |
| `WordAssociationIntegrationTest` | 3 | English chaining, Chinese chaining, traditional output, cursor/settings/incognito/no-learning restart |
| `InputPanelTest` | 10 | 320/411 dp portrait and 800 dp landscape, both themes, stable height, candidate identity, existing gestures/panels |
| `SettingsPanelTest` | 2 | English/Chinese labels, 320 dp, theme controls, association switch callback |
| `ModelIntegrationTest` | 3 | Editor success result, existing scorer, Space commits the visible highlighted composition candidate |
| `ModelSessionPrivacyTest` | 2 | Existing context/privacy isolation |
| `InputPipelineTest` | 1 | Existing real engine input pipeline |
| `KeyboardAppearanceTest#attachedPublicAssociationsRenderInBothModes` | 1 | Attached public candidate fixture in light/dark modes |

Reproduce the device selection from the repository root:

```powershell
$associationTests = @(
  'dev.zeroinput.ime.WordAssociationIntegrationTest',
  'dev.zeroinput.ime.InputPanelTest',
  'dev.zeroinput.ime.SettingsPanelTest',
  'dev.zeroinput.ime.ModelIntegrationTest',
  'dev.zeroinput.ime.ModelSessionPrivacyTest',
  'dev.zeroinput.ime.InputPipelineTest',
  'dev.zeroinput.ime.KeyboardAppearanceTest#attachedPublicAssociationsRenderInBothModes'
) -join ','
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass $associationTests
```

The attached preview test also passed separately after rotating the emulator to
landscape (one additional execution). Rotation was restored afterward. The four
actual light/dark portrait/landscape screenshots were visually checked for text,
candidate/keyboard alignment and clipping. Only fixed public fixture text appears
in them. Detached view measurements are used for geometry, not screenshot claims.

The existing model Space test now reads the highlighted candidate rather than
assuming list position zero: on repeat runs a learned personal row can precede
the engine highlight. Production composition selection behavior is unchanged.
The new editor restart test switches language through the keyboard action so the
Android subtype agrees with the displayed language.

## Local artifacts

- Debug test APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`.
- SHA-256: `3f73c6aae301222c65416d0c702cf0d8a489513e6309298ecef2d19d08735476`.
- Reports/logs: `build/association-validation.log`,
  `build/association-final-checks.log`, `build/association-device.log`,
  `build/association-rotation.log`; normal Gradle XML and HTML reports remain
  under each module's `build/` directory.
- Public screenshots: `build/associations-light.png`,
  `build/associations-dark.png`, `build/associations-landscape-light.png`,
  `build/associations-landscape-dark.png`.

Generated artifacts are ignored by Git. The APK is a Debug x86_64 testing build,
not a signed production release or a physical ARM test artifact.

## Remaining acceptance

- ARM devices and vendor editor/lifecycle behavior were not exercised; only an
  x86_64 emulator was available. API 26 behavior was not exercised this iteration.
- No Release or universal/three-ABI package was built in this feature iteration;
  JNI, CMake and ABI configuration were unchanged. Release signing is outside scope.
- The public seed covers a small set of common phrases. Representative prediction
  quality, wider corpus licensing/coverage, physical-device latency and energy
  remain unmeasured. Passing functional tests does not establish competitive
  next-word accuracy or production performance.

For device acceptance, type `xiexie` and select `谢谢`, then `你` and `的帮助`;
type `thank` plus Space, then tap `you`, `very`, `much`. Verify one English word
separator, normal Space/Enter, no old candidates after moving the cursor or
switching editors, and no suggestions in passwords, email, incognito or with
learning disabled. Repeat with a cold start, hide/show, rotation and a different
application. Use public synthetic phrases only.
