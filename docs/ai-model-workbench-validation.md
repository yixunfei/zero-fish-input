# AI model management and workbench validation

Date: 2026-10-07 (Asia/Shanghai)

## Behavior

- Provider setup offers Fetch available models immediately after the endpoint/key.
  A new provider has no assumed model. Remote IDs can be selected without typing;
  the selected-model field remains editable for manual IDs. Saving is explicit.
- The keyboard header shows the current model and New chat in draft and result
  modes. Quick choice uses the active provider's saved models, clears draft,
  attachments and context, and revokes pending output/history writes. It is local
  to the workbench; settings still owns the saved default. New chat retains the
  current choice; leaving the workbench restores the default.
- Request model membership and image/audio capability checks use the effective
  model. Neither quick selection nor New chat sends a request. Results still need
  explicit insertion into a valid editor session.
- SSE parses bounded events, including multiline data, and requires a normal
  text stop plus DONE. Missing stops, tool payloads, post-stop data, multiple
  choices and trailing JSON fail closed. A real completion, not HTTP success or
  model-list discovery alone, establishes model availability.

The encrypted configuration and conversation formats remain unchanged. Existing
uncommitted provider routing/discovery work was preserved and extended.

## Artifact

Universal Debug APK (arm64-v8a, armeabi-v7a, x86_64):

`C:/Users/yixun/Downloads/ZeroInput-test-apk/20261007-123453/zero-fish-input-0.4.0-debug-universal.apk`

SHA-256: `27de50beb2e3b76d9b6695b48e16361c167be397405b9e8a06210df8df258c81`

Notices, corresponding handwriting sources, BUILD-PROVENANCE.json and checksums
accompany it. Earlier artifacts described in the other AI reports predate these
workbench additions. Final installed-artifact acceptance is recorded below.

## Verification

- New regressions first reproduced missing-stop/tool acceptance and multiline SSE
  failure. After repair, focused Debug/JVM suites passed: ai-api 8, app 174,
  ime-ui 29 and user-data 39 tests.
- Packaging ran root `test`, `privacyCheck`, App Debug Lint and required-Rime
  universal assembly. APK identity, signature, permission and three-ABI checks
  passed. The artifact resolver regression script passed.
- The exact artifact above was installed on Android 16 emulator-5554. All 20 AI
  device regressions passed (63.117 s), including settings draft retention,
  discovery/selection/save, protocol rejection and actual IME interactions.
- `AiLiveSettingsFlowTest` passed (21.243 s) on that same APK: enter the temporary
  endpoint/key, discover/select/save two models without manually entering their
  IDs, select/probe `grok-4.7`, generate two rounds through the real keyboard,
  recover a public number from history, start a blank new chat, switch models in
  both directions, generate again, and explicitly insert. Before insertion the
  external editor stayed empty. The saved default did not change.
- `AiLiveProviderTest` passed (9.294 s): discover three models including
  `grok-4.7`, complete the real fixed-prompt probe, use base/full endpoint addresses,
  recover the prior public context and reject an invalid key as authentication
  failure. No provider or transport callbacks were mocked in either live fixture.
- Seven provider/panel tests passed again at each of 320 x 640dp and 640 x 320dp
  in dark mode (10.110 s / 10.657 s). The panel fixture also checks both light and
  dark themes. An initial narrow-landscape regression exposed clipped primary
  command labels; adjusting the AI/keyboard width allocation resolved it. Public
  fixture renders were inspected. Display, theme and rotation were restored.
- The final installed APK hash still matches the artifact after all device/live
  and size checks. `DEVICE-ACCEPTANCE.json` accompanies the package.

Local evidence: `build/ai-model-workbench-package.log`,
`build/ai-model-workbench-final-device.log`,
`build/ai-model-workbench-final-live-ui.log`,
`build/ai-model-workbench-final-live-protocol.log`,
`build/ai-model-workbench-dark-320x640.log` and
`build/ai-model-workbench-dark-640x320.log`.

The opt-in live fixtures use only public synthetic text. The settings fixture
restores encrypted configuration and keyboard/privacy settings in `finally`.
Credentials are supplied at runtime through ADB stdin, never checked into source,
written into scripts or included in screenshots. Test output is redacted and
contains no response body. Source/documents and task logs were scanned for the
temporary key pattern without matches.

## Environment and limits

L: ran out of space during native staging and artifact copying. The packaging
script now accepts `-OutputDirectory`. On this machine only,
`app/build/outputs/apk/debug` is a directory junction to
`C:/Users/yixun/.codex/artifacts/zeroInput/gradle-apk-debug-20261007`; its original
files were retained. This is an ignored local build-path change. Gradle continues
to use its existing path; deliverables are in the Downloads directory above.

Physical ARM/OEM runtime behavior and other providers/media dialects were not
validated. This is a debug-signed test build; no signed Release is provided.
Previously documented unrelated standalone ime-ui Lint findings remain; required
App Lint passes. `ZeroInputService.kt` remains an existing large lifecycle class
(1,746 lines, below the exceptional 2,500-line cap). Only narrow AI callbacks were
added there; model state, menu and protocol logic stay in their own classes. A
broader lifecycle extraction is outside this repair.
