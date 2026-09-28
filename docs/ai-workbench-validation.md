# AI workbench validation

Latest follow-up: [AI entry discoverability](#ai-entry-discoverability-2026-09-28)
records the visible shortcut, restriction feedback and replacement test APK.
Earlier validation and environment limitations describe their respective runs.

Follow-up: the [2026-09-28 code audit](code-audit-2026-09-28.md) fixes the module
Lint findings below and records successful Android 36 AI panel/settings device
tests. The earlier validation and environment limitations below describe that
earlier run.

Date: 2026-09-28. This record covers the current source changes, not the previously
published APKs. See the [assessment and plan](ai-workbench-plan.md) and
[ADR 0015](adr/0015-ai-workbench-boundary.md) for scope and approved networking.

## Automated checks

Executed from the repository root on Windows with the Gradle Wrapper:

```powershell
./gradlew.bat :ai-api:test testDebugUnitTest :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat :app:assembleRelease privacyCheck -PrequireRime=true --no-parallel
```

Both commands passed. The Debug application Lint report states `No issues found`.
The Release build used the required Rime sources and produced unsigned APKs for
`arm64-v8a`, `armeabi-v7a` and `x86_64`. No release signing key was generated.
Local logs are `build/ai-verification-final.log` and
`build/ai-release-verification-final.log`; generated logs are not source artifacts.

The AI-specific JUnit suites contain 40 passing tests (6 in `ai-api`, 23 in `app`,
11 in `user-data`), with no failures, errors or skipped cases. They cover:

- Restricted editor policies, default-off networking/persistence and size bounds.
- Late and duplicate callbacks, cancellation during provider startup, coalesced
  streaming delivery, one-time insertion and stale conversation selection.
- HTTPS endpoint validation, malformed/truncated/error SSE streams, cancellation,
  multilingual deltas, line/stream/output limits and required stream termination.
- Conversation continuation/deletion, clear versus queued persistence, corrupt or
  duplicate history, failed deletion blocking access, retry and owned-buffer wiping.
- Atomic configuration revocation: late reads/saves cannot restore an older
  enabled configuration; editor invalidation does not discard configuration loading.

The full command also passed the existing input, engine, encrypted storage,
clipboard and language-pack unit regressions. `privacyCheck` checked Debug and
Release merged permissions, the unchanged system-clipboard restrictions, and
negative fixtures restricting networking to the approved AI provider.

The packaged Debug APK and all three Release APKs were inspected with `aapt2`
and ZIP metadata: package IDs and ABI contents match, `allowBackup` and
`usesCleartextTraffic` are false, all include `libzeroinput_rime.so`, permissions
match the approved set, and Release is not debuggable. Local documentation links
were checked and the modified Kotlin sources meet the file/function size limits.

## Additional module Lint finding

The extra `:ime-ui:lintDebug` check failed on three existing findings outside the
AI implementation:

- `EnginePreparationView.kt:27`: `ResourceType` on `attributes.getColor(1, ...)`.
- `values/strings.xml` and `values-en/strings.xml`: `PluralsCandidate` on the
  existing hexadecimal `input_diagnostics_format` string.

No Lint rules or privacy checks were disabled to hide these findings. The normal
required `:app:lintDebug` check passes; this extra module check is not a pass.

## Device and service verification still required

`AiWorkbenchPanelTest` compiled successfully but was not executed. ADB reported
no connected device. Starting the existing Android emulator was rejected by the
automatic approval review with reason `blocked by policy`; it was not retried
through another launch mechanism.

No real provider request was sent and no real API key was used. Therefore this
record does not certify provider interoperability, latency, OEM editor behavior,
screen protection, visual fit, rotation, or the existing authenticated clipboard
flow on a device. The [manual acceptance procedure](ai-workbench-plan.md#acceptance-procedure)
covers small portrait/landscape layouts, light/dark themes, explicit insertion,
all actions, conversation management, stale responses and privacy changes.

Core input remains independent of the AI workers by construction; no claim of
unchanged measured typing latency is made without device measurements. The AI
draft supports local Chinese/English conversion and edits at its tail; a local
generative model, voice, cloud sync and arbitrary cursor editing are outside this
implementation. The selected HTTPS provider receives submitted text and selected
history and may retain them under its own policy.

## Build artifacts

- Debug x86_64: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`.
- Unsigned Release: `app/build/outputs/apk/release/app-<abi>-release-unsigned.apk`.

The injected Debug ABI build uses the intermediates APK path. The unsigned
Release files are validation artifacts, not installable signed release packages.

## Provider draft app-switch regression (2026-09-28)

The provider dialog previously dismissed itself in `MainActivity.onStop`, clearing
all unsaved fields when the user visited another app. It now keeps the protected,
view-owned draft across a temporary stop and clears it on explicit dismissal,
page finish or destruction. It does not restore drafts after recreation/process
death or automatically persist them.

Added three `AiSettingsFailureTest` cases for a real Home/return lifecycle,
cancellation without persistence, and destruction with secure-window protection.
The app-switch case failed before the production fix and passed afterward.
Synthetic endpoint/model/key fixtures were used; no network request was sent.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug privacyCheck :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

All 90 app Debug unit tests, Lint, privacy checks and the build passed. Seven
selected Android 36 x86_64 platform tests passed (`AiSettingsFailureTest`,
`SettingsPanelTest`, `AiWorkbenchPanelTest`). Logs: `build/ai-draft-before.log`,
`build/ai-draft-after.log`, `build/ai-draft-after-build.log`. The emulator was
stopped after testing. This change was not tested on physical/OEM devices,
against real providers, or with a new Release build.

## Toolbar and request-feedback regression (2026-09-28)

The Tools button changed its state, but the Debug diagnostic candidate strip
still took precedence over the toolbar. AI composition candidates had the same
precedence defect, and unrelated idle renders reset the open toolbar. The header
now respects an explicit Tools request until typing or switching panels; Debug
diagnostics cannot replace secondary-panel navigation. Expanded AI candidates
collapse when Tools is opened.

AI policy/configuration rejection could occur before a request started. The
error was written into the detail area while that area remained hidden in draft
editing mode, making Submit appear unresponsive. Failures now reveal the detail
area and show localized, actionable policy/configuration/network/response
messages. Exception text is never displayed, and insertion stays disabled.

Three toolbar regression tests failed before the fix and passed afterward.
The actual IME interaction test also reproduced the hidden rejection before its
fix. Six new Android device tests now pass:

- `KeyboardToolbarRegressionTest` (3): real screen taps with Debug diagnostics,
  AI candidates, unchanged renders and navigation to other panels.
- `AiImeInteractionTest` (1): the actual `ZeroInputService`, Tools -> AI -> draft
  typing -> Submit -> visible disabled-policy feedback. The host editor stays
  empty, closing clears the draft, and a no-learning editor revokes AI access.
- `ai/AiWorkbenchFlowTest` (2): the real workbench controller with a public
  test-only provider. Ask, Plan, Polish, Rewrite and Translate produce a response
  that requires one explicit insertion. Stop/privacy revocation reject late
  answers. History saving remains disabled.

Another 23 existing device tests passed: `AiWorkbenchPanelTest`,
`AiSettingsFailureTest`, `SettingsPanelTest`, `KeyboardTouchTest`,
`KeyboardEditorIntegrationTest`, `CompactLandscapeTest` and `InputPanelTest`.
These include panel sizing under light/dark portrait/landscape configurations,
provider draft retention across app switches, keyboard gestures and editor
integration. The emulator is Android 36 x86_64 (`ZeroInputReview20260928`),
with a 320 x 640 display at density 160. Test logs are
`build/ai-interaction-device.log` and `build/ai-tools-device-regressions.log`.

Executed from the repository root:

```powershell
./gradlew.bat :ai-api:test testDebugUnitTest :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug :ime-ui:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/package-test-apk.ps1 -Abi universal
```

Both commands passed. App and UI-module Lint, the privacy checks and all selected
unit tests pass; the earlier UI-module Lint findings no longer apply. The
packaging script additionally ran the full `test` task and verified the Debug
signature, package ID, all three ABIs and approved permissions. Rime sources
were required for the builds. Logs: `build/ai-tools-verification.log` and
`build/ai-tools-package.log`.

The installable test artifact is
`app/build/outputs/test-apk/20260928-141329/zero-fish-input-0.3.0-debug-universal.apk`,
with notices and `SHA256SUMS.txt` beside it. Its SHA-256 is
`a74e511282fb5e018c53015ad1c8e66f7a0a969daa8487395f0628290668bccb`.
This is a Debug test package, not a signed Release.

That exact universal APK installed successfully on the emulator. The six new
screen-touch tests were then repeated with actual display rotation to
640 x 320 and system dark mode, and all six passed again. Window state confirmed
`ROTATION_90` and night mode during execution. Log:
`build/ai-tools-landscape-device.log`. Original auto-rotation and theme settings
were restored, and the emulator was stopped after verification.

No live provider/API key was used. Successful response tests use public fixtures;
they establish UI/controller behavior, not real-service interoperability or
latency. This follow-up does not certify physical/OEM devices, measured typing
performance or the authenticated clipboard flow on a device. Clipboard,
authentication and networking boundaries were not changed by these fixes.

## AI entry discoverability (2026-09-28)

The reported screenshot showed the diagnostic candidate header with only Tools.
The earlier tests checked reaching AI after Tools, but did not require an AI
shortcut to be visible on the first screen; their touch helper also revealed
off-screen controls before tapping them. New visibility tests reproduce the
missing idle entry on the previous UI. The current local Tools action itself
worked in the emulator; the screenshot does not establish the installed binary
or the source of an OEM-specific no-op.

The idle candidate header now includes a 48dp AI icon next to Tools. The toolbar
places the same icon near its leading edge and resets its scroll position when
Tools is opened. During composition the shortcut yields its width to candidates;
Tools still exposes AI on its first screen. The existing 320dp four-short-candidate
regression caught a width reduction during development; the final layout restores
that capacity without changing candidate dimensions or weakening the assertion.

Restricted fields keep a dimmed, accessibly labelled explanation action. Tapping
it refreshes the editor privacy policy and displays a fixed localized message;
it cannot open the workbench, load AI conversations or submit a request. A policy
change during the click and a stale previously available entry are tested.
AI/network defaults, editor restrictions and clipboard boundaries are unchanged.

At 320 x 640, Android 36 x86_64 passed 34 device tests: seven toolbar/entry
regressions, two actual-IME interactions, two controller/action flows and the 23
existing panel/settings/touch/editor regressions from the earlier follow-up.
`DeviceTouch.tapVisible` verifies controls before any test-driven scroll, and the
actual-service test opens AI directly as well as through Tools. Local logs:
`build/ai-entry-before-device.log` and `build/ai-entry-device-final.log`.

App unit tests, app/UI Lint, privacy checks, the full packaging-script `test` task
and required-Rime builds passed. No check was disabled. Commands:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :ime-ui:lintDebug privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/package-test-apk.ps1 -Abi universal
```

Replacement Debug artifact:
`app/build/outputs/test-apk/20260928-144322/zero-fish-input-0.3.0-debug-universal.apk`.
The package script validated its ID, three ABIs, Debug signature and permissions.
SHA-256: `9f5906ef00447a514d7694fdd7c02da03c41ec87048b88259f6d8004bad42a8a`.
Logs: `build/ai-entry-final-build.log` and `build/ai-entry-package.log`.

The exact replacement APK was installed on the emulator and passed 21 selected
tests at a 360dp portrait width (1080 x 1920, density 480), including actual IME
entry taps and the unchanged four-candidate assertion. Eleven entry/controller
tests passed again after actual landscape rotation. Two focused positive tests
also passed while capturing the public preview, confirming both the visible icon
and the opened workbench without scrolling. Logs are
`build/ai-entry-package-device.log`, `build/ai-entry-landscape-device.log` and
`build/ai-entry-visual-device.log`. The reviewed images are in the local generated
directory `build/ai-entry-verified/`. Original display and rotation settings were
restored, then the emulator was stopped.

Only public preview fixtures are captured for visual review; live AI screen
protection was not disabled. Real-provider interoperability, physical/OEM device
touch handling and performance measurements remain outside this follow-up.
