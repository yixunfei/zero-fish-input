# AI provider protocol validation (2026-10-14)

## Findings and scope

The reported blanket HTTP 404 was not reproducible with the supplied `/v1` URL.
Before the user corrected the temporary credential's backend settings, direct
catalog and Chat Completions requests both returned 401. After that correction,
the same endpoint/key/model passed Android live acceptance. The original `/v1`
Chat Completions resolution was already correct; it is not identified as the cause
of those authentication failures.

Confirmed code defects and gaps addressed:

- All generation used Chat Completions. A full `/responses`, `/messages` or
  `/models` address could be mistaken for a base and receive an additional
  `/chat/completions` suffix. Protocol selection and matching-route validation now
  prevent this; the editor shows actual resolved destinations.
- Catalog and generation 404/405 shared an ambiguous category. Catalog failure now
  explicitly permits manual model entry and independent generation testing.
- The fixed 16-token probe could exhaust reasoning/output budget, while parsers
  reported the result as generic invalid data. The probe now uses a bounded 512
  tokens and distinguishes exhausted output from malformed responses.
- Forced streaming and a single wire schema prevented interoperating with other
  formats. Explicit streaming, three request schemas, appropriate authentication
  headers and bounded JSON/SSE readers replace that limitation.

The local backup commit is `bc09c45e3ea671df9ee3223ee7f3ae7da5a9ab54`, on branch
`xunfei/backup-ai-provider-20261014-113559`. It snapshots the pre-task working tree
without replacing the checkout or original index. Unrelated existing work remains.

The user explicitly chose to clear prior AI Provider configuration. Format 5 is
the only new written format; old AI configuration and its dedicated key are
deleted on load, networking returns to disabled defaults, and conversations are
not migrated or deleted. See [ADR 0024](adr/0024-ai-provider-protocols.md).

## Automated checks

This command passed, including required native source verification:

```powershell
./gradlew.bat :ai-api:test :app:testDebugUnitTest :user-data:testDebugUnitTest `
  privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  --no-parallel "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

314 tests passed with zero failures/errors. A separate `:ime-ui:testDebugUnitTest`
run passed another 42 tests, for 356 total. `privacyCheck` includes Debug and
Release merged-manifest checks. Lint passed without suppression or relaxed rules.
Build log: `build-ai-provider-validation.log` (local, ignored).

## Emulator verification

Android 36, x86_64, `emulator-5554`; production Debug APK plus instrumentation APK.

- 26 tests passed: `AiProtocolPlatformTest`, `AiProviderSetupTest`,
  `AiProviderManagementTest`, `AiSettingsFailureTest`,
  `AiConnectionDialogLifecycleTest`, `AiWorkbenchFlowTest`,
  `AiImeInteractionTest`, `AiSseReaderPlatformTest`.
- Another 8 settings/management tests passed at 720x1280 physical size, landscape,
  dark theme. Portrait/light and landscape/dark public-fixture renders were visually
  inspected. The provider form scrolls; Save/Cancel remain fixed. Device size,
  rotation and night mode were restored afterward.
- Deterministic Android transport fixtures cover all three protocols, JSON and SSE,
  exact request routes, streaming preference, catalog-vs-generation 404, no retry,
  unread error bodies, queued protocol changes, truncation and redirect rejection.
- Workbench regression covers no automatic insertion, explicit insertion, cancelled
  and stale results, editor/session isolation and configuration revocation.

Logs: `build-ai-provider-device.log`, `build-ai-provider-device-landscape.log`.
Public-only renders are under `build/ai-provider-validation/`; live credentials or
responses were not captured in screenshots.

## Live service verification

Endpoint: `https://yibei-apc.com/v1`; model: `deepseek-v4.1-flash`.
The temporary key was supplied only at runtime and is absent from source, logs and
this report. Fixed public prompts were used, with conversation persistence off.

| Scenario | Result |
| --- | --- |
| Chat Completions catalog, probe, streamed generation, full URL JSON generation, selected history, invalid-key rejection | Passed on Android |
| Responses catalog, probe, streamed generation, full URL JSON generation, selected history, invalid-key rejection | Passed on Android |
| Actual settings editor: opt-in, discovery, model selection, draft probe, encrypted save, saved-model probe | Passed on Android |
| Actual IME: generation, follow-up, model switching/new chat, no automatic insertion, explicit insertion | Passed on Android |
| Anthropic Messages using the same vendor/model | Remote generation returned HTTP 403; not passed |

The last row is a material limit: the Messages codec passed deterministic Android
tests, but this vendor/model rejected the live request. Host reproduction returned
403 for both standard `x-api-key` and Bearer authentication, with no redirect and
no response body recorded. Status alone does not identify the vendor's reason.
No claim is made that this account supports Messages, and no fallback hides it.
Use Chat Completions or Responses for the verified vendor/model.

Logs: `build-ai-provider-live.log`, `build-ai-provider-live-responses.log`,
`build-ai-provider-live-anthropic_messages.log`, `build-ai-provider-live-settings.log`.
The live settings fixture restores original encrypted configuration and IME/privacy
settings in `finally`; the transport fixture never persists credentials.

To rerun the live transport fixture with an explicitly supplied temporary key:

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" shell am instrument -w `
  -e class dev.zeroinput.ime.ai.AiLiveProviderTest `
  -e aiTestEndpoint https://yibei-apc.com/v1 `
  -e aiTestModel deepseek-v4.1-flash `
  -e aiTestProtocol CHAT_COMPLETIONS `
  -e aiTestKey $env:ZEROINPUT_AI_TEST_KEY `
  dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## Remaining limits

No physical-device test, ARM APK build or signed Release build was run for this
change. JNI, ABI and permission definitions were not modified. The APK is a Debug
test artifact, not a signed release. Arbitrary gateways, models and multimodal
vendor behavior are not established by these fixtures. Immutable JVM serialization
strings retain the existing best-effort memory-erasure limitation.
