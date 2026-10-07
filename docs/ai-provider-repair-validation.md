# AI provider connection and model discovery validation

Date: 2026-10-07 (Asia/Shanghai)

## Confirmed cause and behavior

The old transport posted directly to the configured address. Against the supplied
temporary test provider, POST `/v1` returned HTTP 200 with `text/html`, whereas
POST `/v1/chat/completions` returned valid SSE. GET `/v1/models` returned three
models, including the requested `grok-4.7`. An HTTP success was therefore not
evidence of a working completion endpoint.

API bases and complete Chat Completions addresses now resolve to the appropriate
same-origin routes. The settings editor can explicitly fetch and select multiple
models, retain manual entries and save up to 32 per provider. Discovery failure
does not block manual models. Capabilities remain explicitly configured per model.
HTML responses are rejected; bounded SSE and complete JSON text responses are
accepted. Empty, truncated, invalid and tool-call results cannot be inserted.

Configuration binds at request submission, deadlines include queue wait, and
cancelling a request cannot remove other queued work. Changing configuration
revokes old results, context and queued history writes before delayed observer
delivery. Configuration format 4 and conversation storage formats are unchanged;
no AI data was cleared or migrated by this repair.

## Automated verification

Passed from the repository root:

```powershell
./gradlew.bat :ai-api:test :app:testDebugUnitTest :user-data:testDebugUnitTest `
  privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" --no-parallel
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass `
  'dev.zeroinput.ime.AiProviderManagementTest,dev.zeroinput.ime.AiSettingsFailureTest,dev.zeroinput.ime.ai.AiSseReaderPlatformTest,dev.zeroinput.ime.ai.AiWorkbenchFlowTest,dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.AiImeInteractionTest'
./tools/package-test-apk.ps1 -Abi arm64-v8a
```

- Focused JVM runs: 8 ai-api, 168 app and 39 user-data tests passed.
- Packaging also passed the root `test` task, `privacyCheck`, App Debug Lint,
  required-Rime build, signature, ABI and permission validation.
- `privacyCheck` validated Debug and Release merged manifests without any scanner
  changes or additional permission exceptions.
- Android x86_64: all 18 selected tests passed at 1080 x 1920, density 420, light
  theme. All four Provider management tests also passed at 320dp portrait and
  640 x 320dp landscape in dark theme. Off-screen list tests scroll recycled rows.
- Public-fixture view renders were inspected at these sizes. The short landscape
  editor scrolls vertically; long input fields retain normal horizontal editing.
  Screen protection remained enabled. Original emulator size, density, rotation,
  keyboard and night-mode settings were restored.

Regressions cover base/full/proxy routing, invalid URLs, HTML 200, authentication
failure, redirect rejection, non-streaming completion, truncation, malformed and
oversized catalogs, cancellation, timeout while queued, unrelated queued tasks,
replacement credentials, configuration changes, stale UI delivery, explicit
multi-selection/save and continued conversation history.

Local generated evidence:

- `build/ai-provider-repair-build.log`
- `build/ai-provider-repair-device.log`
- `build/ai-provider-repair-landscape.log`
- `build/ai-provider-repair-small-portrait.log`
- `build/ai-provider-repair-live.log`
- `build/ai-provider-repair-package.log`
- `build/provider-portrait-light.png`, `build/provider-landscape-dark.png`,
  `build/provider-small-portrait-dark.png`

## Real Android transport verification

`AiLiveProviderTest` ran on emulator-5554 against the user-supplied temporary
endpoint and key, using the actual `OpenAiCompatibleProvider` and Android TLS
implementation. All checks passed in 9.696 seconds:

1. Discover three models and find `grok-4.7`.
2. Complete the settings model probe with its fixed public prompt.
3. Generate text using the API base address.
4. Use the full Chat Completions address for a follow-up with the preceding public
   user/assistant messages.
5. Reject a deliberately invalid public fixture key as an authentication failure.

The fixture runs only with explicit instrumentation arguments; normal device tests
skip it. The temporary key is absent from source, fixtures, saved app settings and
this report. The test prints only pass categories and a model count, not the key
or model output. It creates no conversation persistence. The key is no longer used
after this validation; remote revocation remains with the user.

## Withdrawn artifact and remaining limits

**Withdrawn:** the following ARM64 artifact was incorrectly copied from a stale
output directory. It does not contain this repair and must not be used for acceptance.
The earlier emulator checks ran a different, current x86_64 build. The signature,
ABI and permission checks did not establish source freshness. See the follow-up
[artifact acceptance record](ai-artifact-acceptance.md) for its replacement.

Incorrect ARM64 Debug APK:
`app/build/outputs/test-apk/20261007-103406/zero-fish-input-0.4.0-debug-arm64-v8a.apk`

SHA-256:
`daf65e416b6761d5f38b312d607a95ec9ac92643b4cd0c8f8fd25f206a7c1d79`

Notices, corresponding handwriting sources and `SHA256SUMS.txt` accompany it.
This is a debug-signed test build. No signed Release or new three-ABI release
validation was performed; the change does not modify native code.

An additional standalone `:ime-ui:lintDebug` run found nine existing findings in
unchanged UI files: NotifyDataSetChanged, two ViewConstructor findings, five UseKtx
findings and ClickableViewAccessibility. Its report is
`ime-ui/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.
These were not suppressed or repaired as part of provider work. Required App Lint
passed. Physical ARM/OEM device networking, media-model interoperability and other
providers' dialects still need separate validation. The live test establishes the
supplied provider's text/model-list behavior at the time of the test.
