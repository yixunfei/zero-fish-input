# AI repair artifact acceptance

Date: 2026-10-07 (Asia/Shanghai)

## Correction of the previous delivery

The previously delivered ARM64 APK with SHA-256
`daf65e416b6761d5f38b312d607a95ec9ac92643b4cd0c8f8fd25f206a7c1d79`
was stale. Its DEX contained neither `AiModelPicker` nor `AiEndpoint`. The previous
emulator verification used a different current x86_64 APK. Consequently, the old
delivery claim was incorrect even though those emulator tests passed.

The packaging script assumed `outputs/apk/debug`, while injected single-ABI
builds emitted `intermediates/apk/debug`. Both locations can contain metadata from
different builds. Hardcoding either directory is insufficient: the fixed resolver
follows AGP's `createDebugApkListingFileRedirect/redirect.txt` from the current build.
Missing/ambiguous metadata, wrong identity, missing ABI or path escape fails closed.
There is no fallback to a different output directory. A generated provenance file
records the source locator, metadata, APK and SHA-256; copied bytes must match.

`tools/test-test-apk-artifact.ps1` covers stale files, missing locators/listings,
both output locations, duplicate entries, missing ABI and path traversal.
`tools/test-input-experience.ps1 -ApkPath` installs the explicit deliverable and
checks the installed `base.apk` hash before and after instrumentation. It builds
only the test APK, so it cannot silently substitute another main APK.

## Replacement deliverable

`app/build/outputs/test-apk/20261007-110401/zero-fish-input-0.4.0-debug-universal.apk`

SHA-256:
`74a326c0bf151477df7035bed2b5eb9aceb376e93b6b983add1a72ea53130fa8`

This exact file includes arm64-v8a, armeabi-v7a and x86_64 and the new endpoint,
discovery and model-selection classes. It was installed on emulator-5554; the
installed file's SHA-256 matches the deliverable. The version remains 0.4.0-debug;
use the hash and build directory to distinguish it from previous builds.

## Verification of this exact APK

- Packaging ran root `test`, `privacyCheck`, App Debug Lint and required-Rime
  universal assembly, followed by APK signature, permission and three-ABI checks.
- The packaging resolver regression script passed.
- 21 selected AI device tests passed: Provider management, settings failures,
  Android SSE parsing, workbench flows/panel, actual IME interaction, explicit
  content import and attachment bounds.
- `AiLiveSettingsFlowTest` passed in 14.219 seconds. It opens the actual
  `MainActivity` settings, fills the user-supplied temporary base URL/key, fetches
  remote models, selects two, saves through encrypted storage, selects `grok-4.7`
  and completes the actual settings model test. It then enables the actual IME,
  generates two rounds from the independent draft, verifies the external editor
  remains empty, and explicitly inserts the completed result. There are no injected
  Provider or settings callbacks in this test.
- `AiLiveProviderTest` passed in 7.780 seconds: three discovered models, successful
  probe, base/full endpoint generation, history continuation and invalid-key rejection.
- On the same installed APK, six Provider/panel tests passed again in each of
  320dp dark portrait and 640 x 320dp dark landscape; public-fixture renders were
  inspected. Display, rotation and theme settings were restored. Final installed
  SHA-256 still matched; `DEVICE-ACCEPTANCE.json` accompanies the APK.
- Final `privacyCheck` and App Lint passed after adding the new device test.

Both live tests require explicit runtime arguments and are skipped otherwise.
The settings flow briefly saves its credential through the app's normal encrypted
configuration path, then restores the previous configuration in `finally`. It
requires an emulator without saved AI conversations, disables conversation saving,
restores input/privacy settings and prints no credentials or model responses.
No real-key screenshot is taken. Public-fixture UI screenshots remain separate.

Local evidence: `build/ai-artifact-package.log`, `build/ai-artifact-device.log`,
`build/ai-artifact-live-ui.log`, `build/ai-artifact-live-protocol.log`,
`build/ai-artifact-final-check.log`, `build/ai-artifact-contents.json`, and
`build/device-apk-verification.json`. The artifact directory includes
`BUILD-PROVENANCE.json`, notices, corresponding handwriting sources and checksums.

This is a Debug test package, not a signed Release. Physical ARM/OEM runtime
behavior remains unverified. Previously recorded unrelated standalone ime-ui Lint
findings remain outside this repair; App Lint and privacy checks pass.
