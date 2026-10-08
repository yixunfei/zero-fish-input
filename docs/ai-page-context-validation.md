# AI page references and conversation management validation

Initial delivery: 2026-10-07 (Asia/Shanghai)

The sections preceding the 2026-10-08 review-fix record describe the original
delivery and its historical artifacts. Their device results do not establish
platform acceptance of the later fixes.

## Delivered behavior

The approved [plan](ai-page-context-plan.md) and
[ADR 0019](adr/0019-explicit-page-references.md) are implemented.

- Reference page requires a separate default-off setting, Android accessibility
  authorization and an explicit action in an eligible editor. A bounded snapshot
  becomes an initially unchecked list. Add attaches only checked blocks without
  replacing the question. Capture and Add never submit a generation request.
- Context displays separate references and selectable history, with removal,
  clearing, a recent-history preset and preview. Send uses the exact selection;
  excessive selections are rejected instead of silently trimmed. Opening a saved
  chat initially selects no history.
- The separate chat list supports new/open/rename/confirmed delete, current-chat
  identification, and temporary/saved/loading/error states. Renaming uses the local
  keyboard draft and restores the question afterward. Saving remains optional.
- Page selection keeps Add and Cancel in the fixed footer. Compact result mode
  preserves reading space. Navigation actions scroll horizontally, and longer
  reference/context content scrolls inside the fixed panel.
- Page changes, permission/settings/session changes and other invalidation events
  revoke references and pending work. Submit, completion, history persistence and
  insertion validate current context before consuming results.
- Raw page references are not persisted. Saved questions and answers can contain
  information from references; the interface explains this retention distinction.
  Configuration format 4 and conversation format 1 remain unchanged.

The sole network provider, editor privacy restrictions and clipboard isolation
remain in place. The content review Activity remains nonexported. Page capture,
context selection and name editing have dedicated classes; the existing IME
composition/lifecycle file remains 1,812 lines, within the 2,500-line complex-file
limit. Further lifecycle additions should continue to use owned collaborators
rather than expand that integration point.

## Automated verification

Executed from the repository root with JDK 17 and the configured Android SDK:

```powershell
./gradlew.bat testDebugUnitTest :ai-api:test privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest `
  '-Pandroid.injected.build.abi=x86_64' -PrequireRime=true --no-parallel
```

Result: **BUILD SUCCESSFUL**, 446 tasks (34 executed, 412 up-to-date).
The command validated the affected Debug/JVM suites, strict privacy checks,
Debug/Release merged manifests, App Debug Lint, required-Rime x86_64 Debug APK
and instrumentation APK. Unchanged suites used Gradle's up-to-date results.

| Module / suite | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| ai-api / test | 10 | 0 / 0 / 0 |
| app / testDebugUnitTest | 192 | 0 / 0 / 0 |
| engine-rime / testDebugUnitTest | 46 | 0 / 0 / 0 |
| ime-core / testDebugUnitTest | 116 | 0 / 0 / 0 |
| ime-ui / testDebugUnitTest | 29 | 0 / 0 / 0 |
| language-pack / testDebugUnitTest | 32 | 0 / 0 / 0 |
| model-scoring / testDebugUnitTest | 20 | 0 / 0 / 0 |
| security / testDebugUnitTest | 10 | 0 / 0 / 0 |
| user-data / testDebugUnitTest | 40 | 0 / 0 / 0 |
| **Total** | **495** | **0 / 0 / 0** |

Counts use only the task-specific XML reports above. Pre-existing Release test
reports are excluded; their presence does not establish a Release test run here.

Regressions cover selection ordering and budgets, excluded unchecked text,
disallowed history roles, stale selection revisions, reference revocation before
the UI observer runs, exact provider projection, absence of raw reference
persistence, collector limits/exclusions/cancellation, broker expiry/disconnect/
executor rejection, rename/delete/clear/write failures, and imported Unicode text.
Privacy checks validate the exact accessibility adapter, service permission and
capabilities while retaining the existing network and clipboard restrictions.

## Emulator verification

Device: `emulator-5554`, Android API 36, x86_64. Installed the current main and
instrumentation APKs with `adb install -r -t`, then ran:

```powershell
adb shell am instrument -w -r -e class `
  dev.zeroinput.ime.ai.page.PageReferencePlatformTest,dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.AiImeInteractionTest,dev.zeroinput.ime.ai.AiWorkbenchFlowTest,dev.zeroinput.ime.ai.AiContentImportTest `
  dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (15 tests)**, 46.272 seconds.

- Two page-reference tests exercise an actual separate-UID fixture containing
  public fixed text. They verify initial unchecked state, explicit addition,
  exclusion of editable/password/invisible/sensitive content, source mismatch,
  actual HOME/source change, disabled opt-in and authorization revocation.
- Five panel tests cover context/page/result/draft interaction, chat separation
  and renaming, 320dp portrait and 640dp landscape layouts, light/dark themes and
  font scales 1.0/1.3. Page footer reachability and a minimum 48dp compact reading
  viewport are asserted.
- Four actual IME interaction tests, two workbench flow tests and two content
  import tests cover draft conversion, Chinese candidates, explicit insertion,
  import confirmation and privacy behavior.

The fixed public source Activity exists only in the instrumentation APK. Tests do
not send generation requests to a real provider. Configuration and preferences
are restored. A malformed quoted-null accessibility setting left by an earlier
failed fixture run was removed after final verification; accessibility remained
off with no enabled service.

Final public-fixture renders were pulled and visually checked, including page
selection at 130% font scale in portrait/light and landscape/dark, context in
landscape/dark, and the compact answer in landscape/light. Add/Cancel stays visible
and content remains scrollable. Earlier layout checks exposed compact answer
starvation and a clipped English action label; both were fixed before the final
successful test runs.

## Local evidence and artifacts

- `build/ai-page-final-checks.log`
- `build/ai-page-final-device.log`
- `build/ai-page-final-visuals/`
- `app/build/intermediates/apk/debug/app-x86_64-debug.apk`
- `app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`

These APKs are the current emulator artifacts. The injected-ABI main artifact is
testOnly and requires `adb install -t`; it is not a normal tap-installable package
or a release artifact. Older APKs under `app/build/outputs/apk/` and packages named
in earlier validation reports predate this change. Build logs and fixture images
are local generated evidence, not committed source files.

## Remaining platform acceptance

The following checks are not claimed as completed in this task: physical ARM
devices, vendor-specific accessibility behavior, older Android versions, a matrix
of real third-party applications, live-provider generation using page references,
three-ABI packaging, and Release assembly. Release merged-manifest inspection did
pass as part of privacyCheck; it is distinct from a Release build.

For device acceptance with public nonsensitive text:

1. Enable AI and its network option, then open Reference page in an ordinary text
   editor. Follow the separate feature and Android authorization flow. Returning
   from settings must not capture; explicitly request the page again.
2. Verify the list starts unchecked. Select one block, add it, confirm the question
   remains intact, inspect the context preview, and remove/re-add the reference.
   Only Send may submit it; Insert must remain an explicit separate action.
3. Change the source window/editor, lock the screen, revoke authorization, close
   the panel, switch model/chat or disable the feature during capture/generation.
   Old content/results must not attach, persist or insert into the new session.
4. Open a saved chat, choose individual history or the recent preset, and preview
   the resulting selection. Rename and cancel a rename while a question exists;
   verify question restoration. Confirm deletion, reopen the list and check that
   the deleted chat does not return.
5. Repeat at small portrait/landscape sizes, light/dark themes and enlarged fonts.
   Check list scrolling, footer actions and ordinary Chinese/English typing.

Coverage depends on the source application's accessibility tree. Custom drawing,
withheld nodes, keyboard occlusion and platform restrictions may yield partial or
empty captures. A source may incorrectly expose sensitive content as an ordinary
label; the checklist cannot guarantee semantic secret detection. No screenshot,
OCR, gesture or automatic-scroll fallback is introduced. The five-second deadline
cannot interrupt a blocking platform IPC, but late results are rejected and work
remains bounded. Quoting references reduces instruction ambiguity without claiming
that a model will reliably ignore every instruction embedded in source text.

## Review-fix verification (2026-10-08)

The user approved verification and repair of `CODE_REVIEW_BUGS.md`. Twelve entries
were addressed: H1-H3, M1-M5 and L2-L5. L1 was not established in the current code:
the switch is rendered from persisted settings before explanation and on resume.
L6 remains a performance observation without a measured reason to add a cache.
The original review text is preserved, with current dispositions at its beginning.

### Behavior and regression coverage

- While a source is bound, window-state and window-set events conservatively
  revoke review, including navigation that reuses the package/window identity.
  Event callbacks neither enumerate windows nor read nodes/text. Three JVM event
  policy tests cover invalidation and unrelated event types; they do not simulate
  Android event delivery. Explicit capture still checks source identity.
- Explicit request cancellation clears references and selected history and
  revokes page binding. Ordinary draft editing retains choices. A controller
  regression checks cancellation, editing, resubmission and stale completion.
- Unconfirmed snapshots expire after 30 seconds. The binding owns full blocks;
  checklist views retain only numbered previews of at most 160 UTF-16 units and
  original character counts. A new platform regression covers a surrogate pair
  at the preview boundary, unchecked state, original selection indices and clear.
  Existing page fixture assertions accommodate preview labels while keeping exact
  selected-content assertions. These platform tests were compiled, not executed.
- Imports containing lone/malformed surrogates are rejected entirely. JVM tests
  cover lone final high surrogates, lone low surrogates and valid emoji at/across
  chunk boundaries. A valid final pair was not the original defect.
- Failed title import and invalid title submission restore the original question
  and leave rename mode. Four new tests cover failure, blank/oversized/control
  titles, successful rename and repeated rename/cancel.
- Recent history skips SYSTEM messages without charging their size or count,
  retains the most recent eligible suffix, and respects combined reference/history
  budgets. Rename refresh avoids a loading flash; normal loading remains visible.
  JVM regressions cover both behaviors and the selection limits.
- Language and package preferences use settings-observer memory mirrors.
  Keyboard handlers no longer repeat an unconditional reload check after handling
  a key. Warmup identity checks precede request allocation. Deferred language/pack
  changes are still applied immediately after composition finishes. Header margin
  refresh avoids a two-element list allocation. This is code-path verification,
  not a device latency benchmark.
- Static boundary tests reject callable references to all four prohibited page
  action APIs, in addition to direct uses. Privacy checks retain the existing
  network and clipboard isolation rules.

The collector already had a 32768-character total budget; the original report's
128-times-4096 maximum did not describe its actual total limit. Full snapshots and
imported references still use immutable JVM strings that cannot be reliably wiped;
temporary mutable import buffers and attachment bytes are wiped. These residual
limits and revised cancellation/expiry behavior are documented in architecture,
ADR 0019, the threat model and SECURITY.md. Storage formats are unchanged.

The existing `ZeroInputService.kt` integration file is 1,837 lines, within the
2,500-line complex-file limit; focused collaborators retain their own state.

### Commands and results

Executed sequentially from the repository root:

```powershell
./gradlew.bat testDebugUnitTest :ai-api:test privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  -PrequireRime=true --no-parallel

./gradlew.bat :app:testDebugUnitTest privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest -PrequireRime=true --no-parallel

./gradlew.bat :engine-api:test :engine-english:test :engine-dictionary:test --no-parallel
```

All three commands returned **BUILD SUCCESSFUL**. The full command took 1m 11s;
the final affected checks took 14s after the window policy, preview and deferred
reload adjustments. Lint reports `No issues found.`. The engine JVM command used
unchanged up-to-date results; the other commands also reused unchanged suites.

| Module / task | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| ai-api / test | 10 | 0 / 0 / 0 |
| app / testDebugUnitTest | 205 | 0 / 0 / 0 |
| engine-api / test | 5 | 0 / 0 / 0 |
| engine-dictionary / test | 22 | 0 / 0 / 0 |
| engine-english / test | 12 | 0 / 0 / 0 |
| engine-rime / testDebugUnitTest | 46 | 0 / 0 / 0 |
| ime-core / testDebugUnitTest | 116 | 0 / 0 / 0 |
| ime-ui / testDebugUnitTest | 29 | 0 / 0 / 0 |
| language-pack / testDebugUnitTest | 32 | 0 / 0 / 0 |
| model-scoring / testDebugUnitTest | 20 | 0 / 0 / 0 |
| security / testDebugUnitTest | 10 | 0 / 0 / 0 |
| user-data / testDebugUnitTest | 40 | 0 / 0 / 0 |
| **Total** | **547** | **0 / 0 / 0** |

Only these task-specific XML reports were counted. Pre-existing Release reports
were excluded. This total is the checked suite inventory, not 547 fresh executions.
The app regressions added 13 tests compared with the original delivery's 192.

`privacyCheck` passed Debug/Release merged-manifest checks. Both manifests have
`allowBackup=false`; Debug is debuggable and Release does not enable debugging.
All three Debug APKs contain exactly the corresponding `libzeroinput_rime.so`.
There were no JNI or third-party dependency changes. These are Debug artifacts,
not signed release packages.

Local generated evidence and current artifacts:

- `build/review-fixes-validation.log`
- `build/review-fixes-final-validation.log`
- `build/review-fixes-engine-validation.log`
- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`
- `app/build/outputs/apk/debug/app-x86_64-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

### Unexecuted platform checks

`adb devices -l` listed no connected device for this repair. Instrumentation and
visual/runtime acceptance were not run. The earlier 2026-10-07 emulator results
remain historical evidence only. Release APK assembly, physical/vendor/older-API
acceptance and live-provider tests were not run in this repair.

Use public nonsensitive fixture text for the remaining checks:

1. Capture and select a source page, then navigate within its application,
   including a path reusing package/window IDs. Old review and attached references
   must be revoked; reopening an eligible source requires an explicit new capture.
   Opening a dialog or changing the window set may also revoke review by design.
2. Select history and references, submit, explicitly cancel, edit the question and
   resubmit. No old selections may be sent or revived by late callbacks. Verify
   ordinary editing before submission retains explicitly chosen context.
3. Check initially unchecked numbered previews, character counts, intact emoji
   at the 160-unit boundary and exact selected context. Wait 30 seconds without
   adding references; review must expire and Add must not reuse the old snapshot.
4. Rename a saved conversation while a question exists. Submit an empty/invalid
   title: the question must return and Submit must regain question semantics.
   A successful rename must update the list without a loading flash.
5. Change language or the selected package while composing. Complete composition
   and confirm the deferred setting is applied before the next input; ordinary
   typing and warmup failure fallback must remain functional.
6. Repeat the preview and rename flow at small portrait/landscape sizes,
   light/dark themes and enlarged font scales. Check scrolling and footer reach.
