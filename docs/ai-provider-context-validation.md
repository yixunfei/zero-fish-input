# AI providers and explicit context validation

Date: 2026-10-04

## Implemented behavior

- Built-in Chinese uses Rime. The reference dictionary engine, resources and
  engine-choice settings are removed. Public glide/association helpers,
  language packs, personalization and controlled Rime fallback remain.
- The AI entry opens the isolated draft in eligible editors. Disabled networking,
  missing configuration and transport errors are visible; results require insertion.
- Provider CRUD, active-provider/model selection, manual model lists, per-model
  image/audio capabilities and optional conversation saving are available.
- Explicit SEND/PROCESS_TEXT imports open a protected review. OpenDocument accepts
  UTF-8 TXT, JPEG/PNG/WebP and WAV/MP3. At most two attachments, 1 MB combined;
  each text file is limited to 16,384 UTF-16 units and rejects malformed UTF-8.
- Confirmation creates one expiring memory transfer (two minutes). Returning to
  an eligible keyboard and tapping Use selected content starts a new conversation.
  The import component has no network or editor-write port. The session owns
  imported attachments; request copies are wiped on completion/cancellation/rejection.
- AI actions show their selection, Send stays at the start of the action row,
  attachment height participates in keyboard sizing, and settings switch rows
  can grow with text size. Protected dialogs clear fields on dismissal/destruction.

## Verification performed

From the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-dictionary:test :engine-english:test :ai-api:test `
  privacyCheck :app:lintDebug -PrequireRime=true --no-parallel
```

Passed. The latest module unit-test XML reports contain 493 tests, zero failures
and zero errors. Debug APKs were built for arm64-v8a, armeabi-v7a and x86_64;
each contains its ABI's libzeroinput_rime.so and none contains reference-pinyin.tsv.
privacyCheck inspected both Debug and Release merged manifests. No check was waived.

On the local x86_64 Android emulator, these 25 instrumentation tests passed:

- AiWorkbenchPanelTest (1): bounded layout across portrait/landscape, light/dark,
  explicit insertion and privacy revocation.
- AiImeInteractionTest (3): physical AI entry, local draft isolation, disabled
  request feedback, explicit imported-content claim and editor-change clearing.
- AiSettingsFailureTest (5): invalid endpoint, key draft lifecycle, background
  retention without saving, cancel/destruction clearing and disable-both-switches.
- AiWorkbenchFlowTest (2): five actions with constructed streaming responses,
  explicit insertion, cancel and stale-session rejection.
- AiContentImportTest (2): action/MIME/length/scheme rejection, protected confirmation
  and one-time delivery without network or sender result.
- AiAttachmentReaderPlatformTest (1): public document fixture, actual byte cap
  despite false size metadata, invalid UTF-8 and unsupported file type rejection.
- AiProviderManagementTest (1): model/provider selection and deletion down to zero.
- ChineseInputAvailabilityTest (3), RimeReadinessTest (1), SettingsPanelTest (6):
  live Rime handoff, controlled failure/fallback and settings regressions.

## Remaining validation and decision

- Live HTTPS requests were not exercised with a real provider account. Image and
  audio JSON shape, capability gating, size limits and cancellation are covered;
  each provider's streaming/audio compatibility still needs account/device testing.
- ARM APKs were built and inspected but not installed on ARM hardware. No final
  Release APK was built for this change; the Debug packages are test artifacts.
- Configuration format 4 stores only the provider list and selected provider/model.
  Formats 1 through 3 are cleared, including their encryption key, on first read;
  no compatibility migration or legacy field fallback is retained. If that purge
  fails, configuration reads remain fail-closed until an explicit clear succeeds.
- This change preserves the existing service orchestration file (over 1,500 lines,
  below the project's 2,500-line complex-file ceiling). New AI import, provider UI,
  transport and transfer responsibilities are separate classes.

## Manual acceptance

Configure two test providers and several model IDs; select one text-only model and
one image/audio model. Confirm per-model capabilities do not leak when switching.
Submit public fixture text, TXT, image and WAV/MP3, then cancel and switch editors.
Try disabled networking, invalid key, empty/truncated stream, wrong MIME, more than
two files, over 1 MB and malformed UTF-8. Only completed current-session output
should be insertable. Close the AI panel and verify attachments disappear.

Share selected public text into Add AI context, confirm, then open a password,
email, URI or no-personalization field. It must not offer AI. Return to a regular
field and explicitly claim within two minutes; do not expect automatic insertion.
Repeat with cancel, expiry, rotation, settings change and data clear. No draft,
attachment or key should appear in recents or restored instance state.

## AI interaction repair validation: 2026-10-05

The user reported a gray, inactive AI icon in QQ chat and approved independent
AI drafts in ordinary text editors that only request NO_SUGGESTIONS. The shared
privacy policy now permits this case. Password, unknown, identifier,
no-personalized-learning, incognito and user-disabled learning restrictions
continue to reject AI, including when combined with NO_SUGGESTIONS. No app-name
exception or editor-content read was introduced.

Draft engine preparation retains a ready engine while composition is active and
adopts it after composition finishes. Rime readiness can trigger preparation of
the isolated draft. Sending completes all remaining candidate segments and uses
the authoritative local draft, preserving committed text and converted segments.
Draft conversion continues to disable personal reads and learning.

The panel identifies draft/result state and Chinese/English input mode. Send,
Edit/Preview and Insert remain in a fixed command row; additional commands use
the More menu. Selecting Translate requires a separate Send click. Completed
results remain visible and require explicit insertion. The selected action uses
paired theme colors, including in the input-method dark theme.

Settings provide Detect current model for the saved selected provider/model.
The probe uses the sole HTTPS transport with a fixed public prompt, no history
or attachments, a 16-token output limit and a timeout of at most 15 seconds.
Failure feedback uses fixed categories without reading the server error body.
Retry uses the current saved selection; dismiss, background, configuration
change and data clearing invalidate outstanding results.

### Checks performed

```powershell
$env:JAVA_HOME = 'D:\env\jdk17'
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass `
  'dev.zeroinput.ime.AiImeInteractionTest,dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.AiProviderManagementTest,dev.zeroinput.ime.AiSettingsFailureTest,dev.zeroinput.ime.ai.AiWorkbenchFlowTest,dev.zeroinput.ime.input.LocalDraftInputTest,dev.zeroinput.ime.InternalSearchInputTest,dev.zeroinput.ime.ChineseInputAvailabilityTest'
./tools/package-test-apk.ps1 -Abi arm64-v8a
```

All 23 instrumentation tests passed on emulator-5554 (x86_64). After the final
theme color adjustment, both AiWorkbenchPanelTest tests passed again, including
selected-action text contrast, command bounds, full touch targets, untruncated
command labels and visible More-icon pixels. Eight public-fixture screenshots
cover draft/result states at 320dp portrait and 800dp landscape in light/dark
themes. They were pulled and visually checked under
`app/build/ai-interaction-fixtures/`.

The packaging script passed unit tests, privacyCheck, Debug Lint, native builds
with requireRime, and APK signature/ABI/permission validation. It now reads
current Gradle metadata from `app/build/intermediates/apk/debug`, preventing
selection of stale APKs from the old output directory. The current ARM64 Debug
artifact is `app/build/outputs/test-apk/20261005-205222/zero-fish-input-0.4.0-debug-arm64-v8a.apk`.

### Remaining manual acceptance

QQ is not installed on the test emulator. Its actual EditorInfo flags and ARM
device behavior are not verified. The regression fixture models an ordinary
chat field with NO_SUGGESTIONS; it does not establish a QQ-specific pass. Live
provider requests were not made with real account credentials. Detection UI and
transport behavior use constructed responses. No final Release APK was built.

1. On an ARM64 device, open QQ chat with learning enabled and incognito disabled.
   Open AI, enter `nihaoshijie` one key at a time, choose candidates, then enter
   another phrase. Confirm complete Chinese text remains in the independent
   draft and the QQ message remains untouched before explicit insertion.
2. Save a provider/model, enable AI and networking, and click Detect current
   model. Check success and invalid-key/model feedback, then retry, cancel,
   background the page and change the selection. Late results must not replace
   the current status. Detection may incur a small provider charge.
3. Send public fixture text through each AI action. Check streaming, cancel,
   retry and explicit insertion, then switch editors while a request is active.
   Check password/email/URI/no-learning fields and combinations with
   NO_SUGGESTIONS remain blocked. Repeat in portrait/landscape and both themes.
