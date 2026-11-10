# Project assessment and hardening

Date: 2026-09-06

## v0.7.2 test prerelease (2026-11-10)

The release includes the current workspace snapshot, including the input
fixes, dictionary management, optional AI extensions and packaging changes.
The same Debug-signed universal APK passed installation over v0.7.1 and 56
emulator cases. See the [release notes](releases/v0.7.2.md) and
[validation record](releases/v0.7.2-validation.md).

## Mixed pinyin and symbol keyboard (2026-11-10)

The first candidate page for `nmeng` includes both “柠檬” and “你们”.
The literal mixed spelling stays first. The engine preserves the typed code
for raw commit and uses the canonical reading for learning a recovered phrase.
The memory fallback covers both phrases during native preparation.

The symbol keyboard opens to 27 common symbols. Both paging directions are
available through buttons and swipes, and `123` goes directly to the numeric
keyboard. The existing default-on pairing setting controls insertion of the
closing symbol. Caret placement uses the current editor connection and its
tracked commit range.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :ai-api:test :engine-api:test :engine-english:test :engine-dictionary:test `
  privacyCheck :app:lintDebug -PrequireRime=true --no-parallel --max-workers=2
```

The Gradle build, privacy checks and Debug Lint passed. 1,030 JVM tests passed,
and two live-provider tests were skipped for lack of explicit temporary
credentials. All three Debug APKs
passed package, signature, native ABI and resource checks.
On the isolated API 37 x86_64 emulator, 58 input and UI tests and five native
readiness and recovery tests passed with both installed APK hashes verified.
The 240dp light, 320dp dark and 720dp landscape symbol panels were inspected.
Physical ARM acceptance remains pending.

## Full-pinyin matching and personal ranking (2026-11-09)

Full-pinyin candidate ordering now favors exact readings in the initial native
window. Short unseparated inputs can surface a validated alternate split when
the dictionary yields a synthesized sentence, a partial result or a fuzzy match.
The public `kanga` fixture covers both `kang a` and `kan ga`. The `ng` fixture
covers the collision between a literal syllable and initials. The composition
editor shows the selected reading's boundaries and maps edits to raw offsets.
The memory fallback covers common abbreviated phrases during native preparation.

The personal promotion threshold defaults to 1 and uses any explicitly saved
value. Space follows the visible Chinese candidate after a promotion. Learning
from Space still follows the separate opt-in. Personal pages append without
shifting earlier results, and previously selected segments retain their native
routes with dictionary suppression applied.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :ai-api:test :engine-api:test :engine-english:test :engine-dictionary:test `
  privacyCheck :app:lintDebug -PrequireRime=true --no-parallel
```

The build completed with 1,028 JVM tests passed and two credential-dependent
skips. Privacy checks and Debug Lint passed. All three Debug APKs under
`app/build/outputs/apk/debug/` passed package, signature, native Rime and
resource checks. On the isolated API 37 x86_64 emulator, 58 input and UI cases
passed with both installed APK hashes verified against the current outputs.
Five native fault and retry cases passed, including a missing dictionary with
fallback conversion. Eight rotated pinyin and window-theme cases passed.
The 320dp portrait light and dark composition previews were visually checked.
The separate landscape palette screenshot run stopped at the idle toolbar's
pixel color assertion. Physical ARM acceptance remains pending.

## ARM64 test package (2026-11-06)

Built the current workspace as a full ARM64 Debug test package using
`./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 95000000`.
The resulting `app/build/outputs/test-apk/20261106-093552/zero-fish-input-0.7.1-debug-arm64-v8a.apk`
is 91,622,157 bytes; SHA-256
`18bbec4ad14da0474d1fc73fe9225ed428403afe0d8a10aab89dcf2b4af3c44d`.
The selected delivery limit is 95,000,000 bytes, while the separate 80,000,000-byte
target remains unmet. The size report, provenance, notices, corresponding
handwriting sources and checksums are beside the APK.

The packaging flow completed the Gradle tests, `privacyCheck`, Debug Lint and
the real-Rime ARM64 build. The current JVM result files contain 1,963 cases
across Debug and Release variants: 1,959 passed and four live-provider cases
were skipped for lack of explicit temporary credentials. The APK signature,
package identity, ABI, bundled resources and delivery checksums were verified.
No physical ARM device installation or runtime acceptance was performed.
The earlier management-validation x86_64 APK was preserved at
`.tmp-evidence/management-final-x86_64-before-arm64-20261106/` before the
script reset the reproducible Debug packaging output. Next: install and test
the new package on an ARM64 device when available.

## Management pages and offline association quality (2026-11-05, locally verified)

The owner confirmed four management surfaces: personal phrases, public/local
dictionaries, custom kaomoji and private clipboard. Implemented localized
loading/empty/failure feedback and explicit list reload, shared bounded worker
delivery with generation revocation, scrollable controls, adaptive row heights,
two-line dictionary entries and localized dictionary sources/weight labels.
Clipboard timestamps follow the page locale instead of the system language.
Dictionary controls scroll with the list; expression feedback uses a RecyclerView
header so large failure messages cannot hide reload actions in short windows.
Reload never replays a mutation/import/download; private clipboard reload needs
fresh authentication and clears old metadata after failure. Personal phrase
pages clear stopped rows/search/forms and reject retired row clicks.

Fixed expression rows disappearing when an unchanged refreshed snapshot was
scrubbed without rebinding. The original adapter fails the new regression; the
updated adapter passes. Added worker rejection, late delivery, cancellation,
interrupted worker, localized feedback and stale row tests. A small-screen
platform check caught overflowing dictionary button text; entries now use
plain, multiline list rows. Negative reset/save rejection remains enforced.

The public association corpus grows from 1,728 to 2,002 pairs, with 843 keys.
A separately frozen, project-authored 100-case bilingual scene suite measured
before curation improves positive Top-1 from 19/80 to 78/80 and Top-3 from 25/80
to 80/80; all 20 abstention cases stay empty. The original 156-case suite keeps
its accepted results, and eight explicit continuation chains have regressions.
These visible synthetic labels are development acceptance, not independent
natural-input accuracy. Details and pinned reports: [quality evaluation](word-association-quality.md).

Verification: the broad JVM selection covers 1,024 cases (1,022 passed, two
credential-dependent skips, zero failures/errors), excluding historical Release
unit XML reports. After final dictionary navigation/revocation review, all 13
focused management cases passed again. privacyCheck includes Debug/Release
merged manifests; Lint reports no issues. Real-Rime x86_64 Debug main/test APKs
build successfully. Commands from the root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :ai-api:test :engine-api:test :engine-english:test :engine-dictionary:test `
  :engine-dictionary:evaluateWordAssociations :engine-dictionary:evaluateAssociationScenarios `
  privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/test-input-experience.ps1 -Serial emulator-5556 -TestClass 'dev.zeroinput.ime.ModelSessionPrivacyTest,dev.zeroinput.ime.ManagementFeedbackPlatformTest,dev.zeroinput.ime.PersonalSuggestionManagerTest,dev.zeroinput.ime.DictionaryManagementUiTest,dev.zeroinput.ime.DictionaryEntryInteractionTest,dev.zeroinput.ime.DictionaryManagementPlatformTest,dev.zeroinput.ime.ExpressionManagerTest,dev.zeroinput.ime.SecureClipboardManagerPrivacyTest,dev.zeroinput.ime.WordAssociationIntegrationTest'
```

The final-source selection passed all 28 platform cases on isolated API 37
emulator-5556, including Chinese/English light/dark layouts, 200% font public
row fixtures, portrait/landscape editor layouts, stopped phrase draft scrubbing,
actual dictionary/expression reload controls in 320 x 600 and 800 x 320 viewports
with doubled text sizes,
rejected list reads, fresh authentication broker requests on clipboard retry,
dictionary edit/reset rejection and actual IME association chains. The final
public fixture images were inspected. An earlier combined run timed out waiting
for the existing model fixture's ranking; its production request deadline is
80 ms. Isolated model checks and two later complete selections passed without
changing that deadline or weakening assertions. The timeout is retained as
diagnostic evidence, not accepted success or a resolved latency guarantee.
The landscape editor fixture also exposed a missing wait for Android's rotation
configuration. It now waits at most five seconds for the actual landscape state
and keeps the original layout assertions; the final selection passes.

Final APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`, 93,500,960
bytes; SHA-256 `0d5832d45a688df9560b8d8c69492bb3984f7809bc2775e04450b9634adf5430`.
The installed APK hash was checked, the bundled corpus matches source bytes and
association test fixtures/reports are absent. Evidence: `.tmp-evidence/management-final-source-checks.log`,
`management-final-app-checks.log`, `management-revocation-final-checks.log`,
`management-scroll-final-checks.log`, `management-scroll-final-lint.log`,
`management-scroll-platform-final.log`, `management-apk-audit.json` and
`management-accepted-*.png` in the same folder. The original-adapter failure is
`management-expression-before.log`; the frozen scene measurements are
`association-scenarios-before.log` and the checked-in baseline report.

The task-owned read-only emulator was stopped; emulator-5554 was not operated.
Physical ARM/OEM/API 26 and real biometric/credential acceptance remain unverified.
No Release build or new ARM64 delivery was produced here; this is an x86_64
Debug testing artifact. Existing user changes are preserved and no commit was
created. No persistent format, network path, permission or runtime dependency
is changed. Independent public-corpus evaluation remains future work.

## APK size optimization (2026-11-05, lossless changes verified; target gap remains)

The owner approved the [APK size work plan](apk-size-optimization.md): prioritize
the full ARM64 package at <=80,000,000 physical bytes while preserving all sixteen
Wanxiang tables, complete Emoji, bundled Mini and accepted prebuilt startup
acceleration. Measure Debug/unsigned Release/universal separately and add a
repeatable delivery budget gate. Existing uncommitted work is preserved.

Delivered baseline: ARM64 Debug 113,717,612 bytes (20261102-131603); earlier
universal Debug 168,017,805 bytes (20261031-100310). Extracted-copy experiments
identified about 22.4 MB / 66.4 MB of avoidable native symbols respectively;
these are estimates, not new accepted APKs. First Release attempt found an old
broken `.cxx/RelWithDebInfo` junction to absent D:/ZeroInputReleaseNativeCache.
It was renamed in place, preserving the junction and other native caches.

Implemented app NDK alignment, native symbol stripping, narrow generated Debug
ZIP-state reset, physical-size/ELF/component reports and the default 80 MB gate.
Reset rejects links and preserves native caches and historical deliveries.
Final-ZIP verification additionally binds prebuilt startup assets and optional
model hashes; checks remain active with Python optimization. Custom delivery
limits keep the separate fixed target and `targetMet` status.

Full ARM64 Debug is now 91,601,352 bytes, down 22,116,260 bytes (19.45%) from
the last delivered build. The smallest verified full ARM64 Release is
83,248,958 bytes (79.392 MiB), still **3,248,958 bytes above** the strict target.
ARMv7/x86_64/universal unsigned Release measure 82,587,674 / 83,465,578 /
92,487,766 bytes. All full resources and prebuilt startup acceleration remain.
No 80 MB achievement is claimed. Bounded stronger XZ experiments were not useful;
the extra Debug R8 experiment broke test-runner loading and was reverted.

Verification: 1,934 JVM cases (1,930 passed, four live-provider skips), 13 Python
gate/integrity cases, PowerShell locator/reset/link regressions, privacyCheck
(Debug/Release merged manifests), Lint, three-ABI native and Release builds.
Fifteen final-source platform tests passed on isolated API 37 emulator-5556:
cold/reuse/corruption deployment, native pinyin, Mini ranking and editor input.
Cold deployment was 6,751-7,363 ms, reuse 730-778 ms. The task-owned emulator
was stopped; the owner's emulator was not operated. Each final APK retains all
3,972 reviewed Emoji image hashes; Release is non-debuggable. Full Pillow image
decoding was not repeated because the host Python lacks PIL; this iteration did
not change artwork. Physical ARM/OEM acceptance and production signing remain
unavailable; Release APKs are unsigned.

Commands: `python -m unittest discover -s tools/resources -p 'test_*.py'`,
`./tools/test-test-apk-artifact.ps1`, native Release builds with `-PrequireRime=true`,
and `./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 95000000` (runs full
`test`, privacy and Lint gates). The 95 MB limit was explicit only to produce the
still-over-target test artifact; its report/provenance records `targetMet=false`.
Default 80 MB rejection was verified separately, including with Python `-O`.

Delivery: `app/build/outputs/test-apk/20261105-191408/zero-fish-input-0.7.1-debug-arm64-v8a.apk`;
SHA-256 `008c57dc4906937a44be85712ccb52d706ba2d191635c2a669a44b7759289f43`.
Notices, corresponding sources, APK-SIZE.json and provenance are beside it.
Unsigned Release copies/reports/checksums: `build/apk-size-analysis/release-full/`.
Evidence: `.tmp-evidence/apk-size-*`, including `apk-size-arm64-delivery.log` and
`apk-size-final-platform.log`; detailed measurements and next options are in
[the work plan](apk-size-optimization.md). Next: owner-managed ARM/OEM validation;
further size work must demonstrate useful lossless savings or separately confirm
a resource-delivery tradeoff. No personal data or resource contract was changed.

## Keyboard continuity audit (2026-11-05)

Scope: current input-page ownership, editor restart lifecycle, numeric/symbol
navigation, existing keyboard integration tests, and repository-wide JVM/privacy/
Lint checks. This is a bounded audit, not proof that every project defect is absent.
Existing uncommitted AI expansion work is preserved.

Fixed two confirmed page-reset paths: letter-layout changes during engine state
refresh previously forced the letters page; Android input restarts unconditionally
reset the manual numeric/symbol page. Layout refresh now retains the chosen page.
Restarts retain it only for an existing same-application session, ordinary keyboard
mode and unchanged editor options. New sessions/options reset normally. Every
restart still retires the old controller, connection, authorization and pending
work. Numeric deletion, space, Enter, symbol pagination and explicit return have
regression coverage. A pre-fix regression failed when a layout refresh removed
the numeric keys; all three new JVM regressions pass after the fix.

Verification commands (from the repository root):

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :ai-api:test :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --console=plain
```

JVM report inventory: 1,008 cases, 1,006 passed, two live-provider skips, zero
failures/errors (Debug Android-module suites plus the four platform-free suites;
unchanged tasks may be UP-TO-DATE). Privacy checks include merged Debug/Release
manifests. Lint reports no issues. Evidence is under `.tmp-evidence/keyboard-audit-*`;
the pre-fix failure is `keyboard-continuity-before.log`. Initial platform restart
testing raced cross-process connection replacement; a bounded wait was added to
the fixture test, and the editor integration class then passed all four cases.
The complete keyboard platform selection passed 45 tests on the isolated
read-only emulator-5556 (API 37): editor/toolbar/panel/navigation/interaction/
experience/nine-key/window/appearance/material classes. Evidence:
`keyboard-audit-platform-final.log`. Rotation and floating-window checks passed;
public light/dark screenshots (`keyboard-audit-light.png`, `keyboard-audit-dark.png`)
were visually inspected. The user's emulator-5554 was not changed.
Final-source checks passed (`keyboard-audit-final-checks.log`), and the rebuilt
APK passed all four editor integration cases again (`keyboard-audit-final-editor.log`).
The task-owned emulator-5556 was stopped after verification. No Release build,
physical-device acceptance or new ARM64 package was produced in this iteration.

Remaining work, prioritized:

1. Repeat continuous numeric/symbol input on physical ARM64/OEM devices and real
   target apps, including host-triggered restart, focus changes, theme/rotation
   and rapid typing. Measure key latency and native memory on hardware.
2. Complete real biometric/device-credential and delayed-callback acceptance;
   finish the clipboard P1 source/baseline matrix before separately authorized
   P2/P3 production work. No production system-clipboard body import is added.
3. Validate live model/MCP/Brave interoperability, native assistant integration
   and background service behavior with real credentials and OEM devices.
4. Management localization and worker-failure feedback are now covered above;
   continue independent public-corpus and physical-device association evaluation.
5. Before release: rerun all required ABI/native and Release gates, document
   external document-provider overwrite behavior, and arrange owner-managed
   production signing. This iteration builds an x86_64 Debug APK only.

Public-API virtual-screen support remains a private public-sample preview;
third-party background observation/control is an explicitly unsupported boundary,
not a feature to enable by weakening security checks.

## Assistant capability expansion (2026-11-02, accepted; E1-E7 locally verified)

The owner requested background virtual screens, tools, MCP, Skills, role/memory
management, context compaction, run notifications, web/file tools, native digital
assistant integration and page improvements. Reviewed current sources, module
configuration, existing boundaries and Eta's pinned virtual-display implementation.
Its trusted-display backend depends on root/LSPosed; ordinary display creation
does not establish third-party background automation support.

A concrete seven-stage proposal, bounded data/network/permission contracts,
page flows, acceptance criteria and official reference links are in the opening
section of [the existing AI device-agent plan](ai-device-agent-plan.md).
The owner confirmed the recommended phased implementation and public-Android-API
virtual-display route. No privileged-backend investigation is authorized.

Proposed default: public-API production path, separate encrypted opt-in context
stores without existing-format migration, declarative Skills, selected-URI files,
explicit public-HTTPS MCP/search, generic run notifications and optional native
assistant entry. Any privileged virtual-display investigation remains a separate
choice and cannot imply authorization to root a device or ship privileged code.

E1 implementation now includes platform-free role/memory/projection contracts,
strict atomic encrypted context storage with an independent key, revision/data
generation checks, private management/review UI, explicit per-chat selection and
structured user-context encoding for the existing protocols. AI clear includes
the new store/key. ADR 0030 records the exact boundary; existing persisted formats
are unchanged. E1 full JVM verification passed: 1,842 cases, 1,838 passed,
4 credential-dependent skips, no failures/errors. privacyCheck, Lint and x86_64
Debug/main test APK builds passed with requireRime=true. The isolated API 37 AVD
passed all 41 non-live AI platform cases; after final preview/localization fixes,
the 6 affected context/activity/layout/IME cases passed again. Public fixture
layout screenshots were inspected. Evidence: `.tmp-evidence/ai-expansion-e1-complete.log`,
`ai-expansion-e1-platform.log`, `ai-expansion-e1-final-platform.log` and
`ai-expansion-e1-visuals.log` in the same directory. Stale migrated DEX transforms
for ai-api/user-data were moved to scoped evidence backups; native caches and
the user's emulator were preserved.

E2 now implements a separately encrypted declarative Skill library, secure
management/import/selection, bounded UTF-8 SAF reads and tool-prerequisite
rejection in ordinary chat. Compaction has source preview, explicit generation,
summary review/apply and transient undo without rewriting original messages.
ADR 0031 records imports, storage, lifecycle and summary boundaries. Final E2
verification passed: 1,870 JVM cases, 1,866 passed and 4 credential-dependent
skips; privacyCheck, Lint and x86_64 Debug/main test APK builds passed with
requireRime=true. The isolated API 37 AVD passed all 44 non-live AI platform
cases, including bounded Skill reading, independent Keystore storage, private
manager entry, layouts and actual IME insets. Evidence:
`.tmp-evidence/ai-expansion-e2-accepted.log` and `ai-expansion-e2-platform.log`.
Review also fixed retention of a dismissed summary proposal and replaced the
tool-list parser with strict shallow JSON reading to reject nested/lenient input.
E3 now has an independent task page, native tool protocol encoding through the
existing provider, a bounded approval/cancellation loop, selected-URI read/search
and reviewed Save As. ADR 0032 records this boundary. E3 verification passed:
1,896 JVM cases, 1,892 passed and 4 credential-dependent skips; privacyCheck,
Lint and x86_64 main/test APK builds passed with requireRime=true. All 48 non-live
AI platform cases passed on the isolated API 37 AVD. Public light-landscape and
dark-portrait fixtures at 200% font scale were visually checked. Evidence:
`.tmp-evidence/ai-expansion-e3-accepted.log`, `ai-expansion-e3-platform-build.log`
and `ai-expansion-e3-platform-accepted.log`. An initial platform assertion ran
before Activity destruction; it now waits for the actual lifecycle callback.
The final run verifies draft clearing after destruction.

E4 implements independently encrypted MCP/Brave connections, explicit metadata
discovery/review, bounded JSON/SSE, public-only socket DNS, per-call catalog
revalidation, text resource/prompt tools and cited search snippets. The exact
new transport and dependency review are recorded in ADR 0033. No redirects,
cookies, proxies, background discovery or automatic retries are enabled; the
network interceptor also rejects response-driven follow-ups such as 503 retries.
Final checks passed: 1,914 JVM cases, 1,910 passed and 4 credential-dependent
skips; privacyCheck, Lint and requireRime x86_64 main/test APK builds passed.
All 51 non-live AI platform cases passed, including actual Android-client mixed
DNS rejection, independent Keystore storage and cancelled manager reads.
Evidence: `.tmp-evidence/ai-expansion-e4-accepted.log` and
`ai-expansion-e4-platform.log`. Initial Lint findings were fixed in source and
resources, without suppressions. Live MCP/Brave/provider credentials were not
available, so real-service interoperability remains unverified.

E5 adds an app-owned transient task runtime, nonce-bound generic notifications,
optional 120-second non-GUI background work, foreground-only approvals and a
system-bound private text assistant. Debug unit tests, privacyCheck, Lint and
requireRime x86_64 main/test APK builds passed; all 54 non-live AI Android cases
passed. Actual system ASSIST-key invocation on the isolated API 37 AVD opened
the selected session with disabled context flags 3, empty assist/screenshot
queues and FLAG_SECURE. The original assistant role was restored. Evidence:
`.tmp-evidence/ai-expansion-e5-accepted.log`, `ai-expansion-e5-platform-accepted.log`,
`e5-native-invocation.txt`, `e5-native-window.txt`. Earlier test-environment
permission and Lint findings were corrected; failed/old-APK runs are not accepted
evidence. Native OEM behavior and actual credential-backed background work are
not yet verified.

E6 implements an explicit ordinary-API private virtual-display public sample,
local bounded preview and release lifecycle; no third-party launch/observation/
input path exists. E7 collapses task configuration during execution and brings
approval controls ahead of the timeline with a separately scrollable parameter
preview. Integrated acceptance passed: 1,928 JVM cases (1,924 passed, four
credential-dependent skips, zero failures/errors), privacyCheck (including Debug
and Release merged manifests), Lint and requireRime x86_64 main/test APK builds.
All 59 non-live AI platform cases passed on the isolated API 37 AVD, including
actual public virtual-display rendering, foreign test-Activity launch eligibility
rejection, invalid surfaces, explicit/surface-loss release and exiting the actual
production capability dialog. Public 640 x 360 preview and 200% font-scale theme/
orientation layouts were inspected. Background notification revocation now
cancels outstanding work on its next one-second check, with a regression test.

Final evidence: `.tmp-evidence/ai-expansion-e7-final.log`,
`ai-expansion-e7-platform-final.log`, `ai-expansion-e7-arm64.log`,
`e7-virtual-public.png`, `e7-tool-dark-portrait.png` and
`e7-tool-light-landscape.png`. Initial failed runs are retained as diagnostic
evidence, not acceptance. The final run fixes the English quantity label and
validates the notification monitor included in the ARM64 package.

Current tested ARM64 artifact:
`app/build/outputs/test-apk/20261102-131603/zero-fish-input-0.7.1-debug-arm64-v8a.apk`.
SHA-256: `1dd59ce44c5979c00958ba3437939822e809ea251ae0665290145c48d3ba2d5a`.
The standard packaging script passed signature, permission, ABI, dictionary and
requireRime checks; notices, corresponding handwriting sources and checksums are
beside the APK. This is a Debug test build, not a signed production release.
The 20261101 artifact below is retained as historical evidence and predates this
expansion. Existing uncommitted user work was preserved; no commit was created.
The task-owned emulator-5582 was stopped after acceptance; emulator-5554 was
left untouched. The original system assistant selection had been restored.

Remaining external validation: real model/MCP/Brave credentials, physical ARM64
and OEM assistant/background-service behavior. Third-party background virtual
screen observation/control is unavailable by design on this public-API route;
the delivered preview does not establish that capability. Next work requires
real-service/device evidence, not relaxing the existing gates.

## Expanded AI platform acceptance (2026-11-01)

Continued the accepted AI interaction work by closing local acceptance gaps,
without expanding permissions, tools or network paths. Added
`AiAssistantImeInsetsTest`: it opens the real standalone Activity, enters only a
fixed public draft, requests the configured soft keyboard, requires actual IME
visibility, and verifies that the full draft and Send control remain above its
reported insets. It does not call a provider or capture the private window.

The isolated read-only API 37 AVD passed all 39 non-live AI instrumentation cases
in one run. This extends the earlier 21-case selection with Android protocol/SSE,
attachment/content-import, page-reference and Stage 6 tests. The new actual-IME
case passed with Gboard; a separate repeat also passed with ZeroInput selected
(`OK (1 test)`). Thus there were 40 passing executions, covering 39 distinct tests.
The fixture temporarily allowed the software keyboard with an attached hardware
keyboard and restored the original setting and input method afterwards.

Stage 6 coverage includes cancellation propagation for synthetic SMS/call-log/
contact/calendar providers, no retry/fallback insert after an in-flight write is
cancelled, full-name replacement against the Android contacts provider, and
rejection of aggregated-contact update/delete. Contact cases create and clean up
only their own generated local records. This does not establish real SMS/call,
notification, calendar, camera/microphone or OEM permission/hardware acceptance.

`assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` with x86_64 and
`-PrequireRime=true` passed (exit 0). No production code changed in this follow-up;
the previously verified ARM64 Debug artifact at `20261101-113722` remains current.
No new ARM64 packaging, full JVM rerun or Release assembly was needed for this
test/documentation-only change. No real-provider call was made: the explicitly
opt-in live class was excluded because temporary test credentials were not supplied.

Evidence: `.tmp-evidence/ai-acceptance-build.log`,
`.tmp-evidence/ai-acceptance-platform.log`,
`.tmp-evidence/ai-acceptance-own-ime.log` and the exact class list in
`.tmp-evidence/ai-acceptance-classes.txt`. Build command from the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

For platform reproduction install the matching main/test APKs with `adb install
-r -t` on the isolated AVD, run the recorded class list with `am instrument -w -r
-e class`, and require `OK (39 tests)`. For the second keyboard repeat select the
already enabled `dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService`, run
only `dev.zeroinput.ime.ai.AiAssistantImeInsetsTest`, then restore the original
input method. Set `show_ime_with_hard_keyboard` to 1 only for this isolated fixture
and restore its original value. The read-only AVD was shut down after verification.

Next: connect an ARM64 physical device for OEM/layout/permission/hardware acceptance
and provide explicit temporary provider test configuration for live transport.
Only an emulator was connected this round. Those external acceptance items remain
open; additional capability expansion still needs its separate scope decision.

## AI interaction follow-up delivery (2026-11-01)

The owner requested further Eta-inspired AI/device-agent interaction and
capability improvements. Reviewed current uncommitted standalone-entry work,
runtime/panel/settings sources, relevant architecture/threat boundaries and Eta
baseline `e90922a4490389f524a10c6acebbcca336883935` runtime/tools designs.
The concrete proposal and acceptance matrix are in the opening section of
[the existing device-agent plan](ai-device-agent-plan.md).

The owner confirmed all three stages on 2026-11-01. Implementation now includes
fixed chat/task controls, bounded ordinary-chat queue/edit/interrupt, persistence
settlement before queued continuation, local app search, capability overview,
draft-only examples and structured task outcomes. Existing uncommitted changes
remain. No new network path, permission, dependency or persisted format is added.

Local implementation and acceptance are complete. The controlled old cancellation
behavior reproduces the new regression failure (one test, one expected assertion
failure); final source is restored. Failure/expiry/cancellation now have distinct
labels, terminal replay is deduplicated, and completed-task reattachment carries
only generic outcome metadata. Narrow screens at large font sizes stack navigation
controls; chat and device actions stay reachable with reduced viewport height.

Final checks: 1,814 JVM test executions, 1,810 passing and four credential-dependent
live skips, zero failures/errors. The ARM64 package script ran with checks enabled
and passed JVM tests, privacyCheck, Debug/Release merged manifests, Lint, requireRime
assembly and APK signature/ABI/permission validation. Negative scanner self-tests
also passed in the full x86_64 verification. Earlier Lint runs caught obsolete
resources and KTX issues; these were fixed without relaxing checks.

All 21 selected API 37 x86_64 instrumentation cases passed, including the new
queue/interrupt presentation, local app search, no-grant capability overview,
fixed device controls, 2x-font portrait/landscape chat layouts in both themes,
entry/recreation and existing AI/storage/page/accessibility regressions. Eleven
fixed-public-sample renders were exported and visually checked. These are public
View fixtures, not live private screen captures. Real IME/OEM inset behavior still
needs physical-device acceptance; the reduced-height fixture is not a claim that
all OEM keyboards were exercised.

Evidence: `.tmp-evidence/ai-interaction-cancel-before.log`,
`.tmp-evidence/ai-interaction-verified-checks.log`,
`.tmp-evidence/ai-interaction-final-layout-build.log`,
`.tmp-evidence/ai-interaction-verified-platform.log`,
`.tmp-evidence/ai-interaction-arm64-package.log` and `interaction-*.png` renders.
The final outcome-dedup/navigation edits passed targeted x86_64 tests/build,
the final 21-case platform run and the full ARM64 package checks.

Artifact:
`app/build/outputs/test-apk/20261101-113722/zero-fish-input-0.7.1-debug-arm64-v8a.apk`
(112876302 bytes). SHA-256:
`8c425b7b66ba959c086699a18c7f40aaa29aacc2c7f26e693b47d916838c5976`.
Independently matched the APK to BUILD-PROVENANCE.json and SHA256SUMS.txt.
The same directory includes license notices and handwriting corresponding sources.
This is a Debug test package. No Release APK assembly, release signing, publication
or Git commit was performed. Existing native-library stripping warnings remain.

Verification commands (from repository root):

```powershell
./gradlew.bat test testPrivacyBoundary privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/package-test-apk.ps1 -Abi arm64-v8a
$interactionClasses = @(
    'dev.zeroinput.ime.ai.AiAssistantActivityTest',
    'dev.zeroinput.ime.ai.AiAssistantLayoutTest',
    'dev.zeroinput.ime.ai.AiInteractionPanelTest',
    'dev.zeroinput.ime.ai.device.AiDeviceAgentActivityLifecycleTest',
    'dev.zeroinput.ime.ai.AiWorkbenchFlowTest',
    'dev.zeroinput.ime.ai.AiStorageFailurePanelTest',
    'dev.zeroinput.ime.ai.page.PageReferenceBindingTest',
    'dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityPlatformTest'
) -join ','
adb -s emulator-5582 shell am instrument -w -r -e assistantVisuals true -e class $interactionClasses dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Install the matching x86_64 main/test APKs with `adb install -r -t` on an isolated
AVD before instrumentation; require `OK (21 tests)`. The read-only verification
AVD is shut down after the run. Next: manual ARM64/OEM use, a real configured
provider, and Stage 6 hardware/category acceptance. Those remain unverified.
Additional network/privileged tool extensions require their own boundary decision.

## Standalone AI workbench and device-agent entry (2026-11-01)

Goal: improve the existing AI workflow using Eta as a design reference and
provide an independent entry. The owner confirmed one APK with a separate
desktop AI entry, rather than another application ID. [ADR 0029](adr/0029-standalone-ai-entry.md)
records that decision and the content-free exported launcher boundary.

Implemented `zero fish AI`, a private full-screen chat with existing model,
reasoning/action/translation selection, reviewed context and optional encrypted
conversation controls. Settings and the keyboard AI More menu can open it.
The host uses an explicit standalone request policy and a separate request
coordinator; it has no editor, insertion or clipboard read port. Existing
privacy/network gates remain; leaving clears transient state without replay.

Device tasks now offer a bounded local launcher-app selector, readiness hints,
and Stop for both pending launches and process-owned tasks. The ten-second
single-use launch guard binds elapsed realtime, configuration, policy and data
generation. Settings/configuration changes and return/destruction revoke pending
starts. Attachment metadata work no longer captures the Activity on its worker.
No provider/storage format, permission, Stage 6 consent or input-engine change
is introduced. Local launcher visibility adds no application-list upload.

The original handle-dependent Stop predicate was restored in a controlled build:
the new platform regression failed with the expected disabled-Stop assertion.
The repaired final source passes all 19 selected API 37 x86_64 platform cases,
including the new entry/recreation, Stop and layout checks plus existing AI,
storage-error, page-reference and device-accessibility regressions. Fixed public
layout samples cover 360x720 portrait and 600x320 landscape at 2x font, both
themes; top/composer renders were inspected. These are detached public Views,
not captures of live private content. An initial optional image-export test
failed on the test-package cache path; the repaired test uses a dedicated target
test cache and writes images off the main thread.

Evidence: `.tmp-evidence/ai-assistant-stop-before.log`,
`.tmp-evidence/ai-assistant-entry-before.log`,
`.tmp-evidence/ai-assistant-final-platform.log`, the `assistant-*.png` public
renders and `.tmp-evidence/ai-assistant-verified-checks.log`. The initial full
JVM/privacy/negative-scanner/Lint/x86_64 Debug main/test build passed. Final
`./tools/package-test-apk.ps1 -Abi arm64-v8a` passed with all checks enabled
(exit 0): 1,780 passing JVM test executions, four credential-dependent live
skips, zero failures/errors; privacyCheck with Debug/Release merged manifests,
negative scanner checks, Lint, requireRime assembly and APK signature/ABI/permission
validation passed. Log: `.tmp-evidence/ai-assistant-arm64-package.log`.

Artifact: `app/build/outputs/test-apk/20261101-091903/zero-fish-input-0.7.1-debug-arm64-v8a.apk`
(112843214 bytes). SHA-256:
`abf0cf33493422c2c6f786a92cbdc70182833636b5696b9868e85ddef0922cd5`.
The artifact was independently matched to BUILD-PROVENANCE.json and SHA256SUMS.txt.
The same directory contains license notices and handwriting corresponding sources.
The existing native-library stripping warning remains; no size improvement is
claimed. No Release APK assembly, release signing or publication was performed.

Current state: implementation and the stated local checks are complete. Next:
manual ARM64/OEM acceptance and real-provider validation; those remain unverified,
as do the broader Stage 6 hardware/provider cases outside this 19-case selection.
The dedicated read-only emulator was shut down after validation. AI navigation
and AI settings bindings were separated by responsibility so their owning methods
remain below 100 lines.

The new default-launch regression also reproduced the initial equal-priority
entry selecting the AI gateway instead of keyboard setup. The AI filter now
uses priority -1, pinned by privacy checks; the final platform test and package
query verify that settings at priority 0 remains the primary launch destination.

Verification commands:

```powershell
./gradlew.bat test testPrivacyBoundary privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/package-test-apk.ps1 -Abi arm64-v8a
$aiClasses = @(
    'dev.zeroinput.ime.ai.AiAssistantActivityTest',
    'dev.zeroinput.ime.ai.AiAssistantLayoutTest',
    'dev.zeroinput.ime.ai.device.AiDeviceAgentActivityLifecycleTest',
    'dev.zeroinput.ime.ai.AiWorkbenchFlowTest',
    'dev.zeroinput.ime.ai.AiStorageFailurePanelTest',
    'dev.zeroinput.ime.ai.page.PageReferenceBindingTest',
    'dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityPlatformTest'
) -join ','
adb -s emulator-5582 shell am instrument -w -r -e assistantVisuals true -e class $aiClasses dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The platform invocation requires the matching x86_64 main/test APKs installed
on the isolated AVD. Its verdict must be `OK (19 tests)`, not merely ADB exit 0.

## Dictionary update task repair (2026-11-01)

The user selected preserving all sixteen tables after the upstream source split:
fourteen tables follow the pinned data repository, while lianxiang and renming
retain the existing reviewed source. [ADR 0028](adr/0028-dictionary-source-split.md)
records provenance, license scope and the unchanged Android/category contract.

Fixed candidate resolution writing the lock before preparation, preparation
overwriting verified output before detecting a missing table/hash failure, the
bot subject mismatch, missing prebuilt regeneration and obsolete publication
instructions. Model proposals now use a fixed asset-ID URL with size/hash checks.
Source/license/scope checks and staged publication preserve existing pins on
handled failure. The update job rebuilds and verifies the matching default Rime
artifacts before proposing a bot PR; Android/device acceptance remains separate.

Three regressions failed before the preparation fixes. All eighteen local
failure/provenance and disposable-remote branch ownership regressions pass,
as do three resource integrity regressions; current reviewed
dictionary preparation, dictionary verification and default prebuilt verification
pass. An isolated real-upstream candidate at
`68522110ff3f5c845a7f47cc3ac32793cde5ffef` prepared and verified all eighteen
expected files, including the 418078764-byte model asset 676604065 and the two
retained tables from `26a9efeb18e604c091f4d3890d00bec723fe755f`.
Current main data pins are unchanged. privacyCheck, both merged manifests and
scanner self-tests passed. Logs: `.tmp-evidence/dictionary-task-*.log`.

The repair was pushed as `8294b31c97ffac78a14ddcbf48dc5cf4a728913e`.
[Manual run 41255227389](https://github.com/yixunfei/zero-fish-input/actions/runs/41255227389)
completed successfully, including source/model preparation, deterministic checks,
LTS packaging, pinned native bootstrap, default artifact regeneration/verification
and bot PR refresh. PR #1 now points to
`afd68911f2340d0e24c845b1355d8d60fad03576` with six changed data/artifact files.
Independent local inspection matched its lock to the isolated upstream candidate
and verified all three XZ/raw sizes, hashes and binary formats. The new default
generation is `5fdb4830385efde59685ee04`; other optional pack records are unchanged.
Evidence includes `.tmp-evidence/dictionary-task-refreshed-pr-check.log` and the
downloaded run/job records. [PR follow-up](https://github.com/yixunfei/zero-fish-input/pull/1#issuecomment-6551007777)
records the resolved original blockers and remaining acceptance gates.

Task recovery is verified. New-data Android/Rime contract/device, cold deployment,
package and performance acceptance remain for data-PR review; host success does
not establish those outcomes. No resource/APK publication or data-PR merge was
performed. Next: complete that acceptance before deciding whether to merge #1.

## Remote dictionary PR review (2026-11-01)

The only open PR was [#1](https://github.com/yixunfei/zero-fish-input/pull/1),
the bot's Wanxiang v18.1.2/LTS update at
`a924189d06056184ae9337e65806e052097b22ec`. Reviewed against main
`2530a98869bc5241e0b66aaaef5e90d441db5176`; submitted
[changes requested](https://github.com/yixunfei/zero-fish-input/pull/1#pullrequestreview-5725497704)
with two blocking inline findings. The PR remains open and unmerged.

- The proposed dictionary hashes require default generation
  `adf830f1ce6f1d57a51d6fbb`, while the existing prebuilt manifest contains
  `ae165bd3c065ad102852eda9`. Current main's
  `python tools/rime-prebuilt/verify.py` passed (exit 0); the same verifier with
  isolated metadata constructed from the PR lock failed (exit 1), reporting
  `Invalid default generation`. The PR must rebuild/review matching artifacts.
- The configured mutable upstream model URL returned HTTP 200 with 418078764
  bytes, differing from the PR's 398357548-byte pin. The preparation script does
  not use its recorded asset ID. Retrieval of the historical asset could not be
  confirmed because the upstream API was rate-limited; no replacement digest
  was accepted and existing dictionary/model caches were preserved.

The reviewed head had no check-runs or commit statuses. Its Android/native/device
and performance checks were not run: reproducible inputs and matching prebuilt
artifacts must be established first. Evidence is under `.tmp-evidence/pr-1-*`.

The October 19 and 26 scheduled update logs both show missing upstream
`dicts/lianxiang.dict.yaml`; this is separate from the stale PR's verification.
The workflow's expected bot commit subject also differs from its actual commit
subject, creating another refresh blocker after preparation succeeds. Upstream
table changes require an explicit mapping/coverage review, not silently omitting
one of the required sixteen tables. The proposed resource ZIP returns 404;
publication remains a separate explicit action after reviewed merge, as required
by current ADR 0021. The PR's older before-merge publication text needs correction.
No resources or APKs were published during review. Next: restore reproducible
pinned inputs, rebuild default artifacts and complete native/device acceptance
before reconsidering PR #1.

## Authentication deadline hardening (2026-11-01)

This iteration preserves the existing uncommitted clipboard work and fixes the
shared authentication broker's existing 30-second request limit. Its Handler
timer used uptime, so device sleep or delayed owner-thread delivery could accept
a success after the original request expired. Delivery now checks elapsed
realtime, including sleep, before passing a grant to the caller. Cancellation,
one-use delivery, storage formats and permissions are unchanged.

Four controlled-clock regressions cover completion at the deadline, an earlier
success delivered after expiry, success just before expiry and normal timeout.
The first two failed before the fix (Gradle exit 1); all four passed afterwards.
App Debug JVM regression: 393 passed, two credential-dependent live-provider
skips, zero failures/errors. Module Debug tests, scanner negative self-tests,
privacyCheck including both merged manifests, Lint and requireRime x86_64 main/test
assembly passed (Gradle exit 0). The isolated API 37 x86_64 emulator passed all
16 selected authentication/import/confirmation regressions, with no skips.
The authentication tests use synthetic grants and intercepted Activity launches;
they do not validate an actual biometric or device-credential prompt.

Evidence: `.tmp-evidence/auth-deadline-before.log`,
`.tmp-evidence/auth-deadline-after.log`,
`.tmp-evidence/auth-deadline-final-checks.log` and
`.tmp-evidence/auth-deadline-device.log`. Verification commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests dev.zeroinput.ime.auth.AuthenticationBrokerDeadlineTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat testDebugUnitTest testPrivacyBoundary privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
adb -s emulator-5580 shell am instrument -w -r -e class dev.zeroinput.ime.auth.AuthenticationBrokerTest,dev.zeroinput.ime.clipboard.ClipboardImportIntentTest,dev.zeroinput.ime.clipboard.ClipboardRepeatedImportTest,dev.zeroinput.ime.clipboard.ClipboardImportPanelTest,dev.zeroinput.ime.clipboard.SecurePasteConsentTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The device command requires the matching main/test APKs installed on the isolated
AVD first. Its verdict was `OK (16 tests)`, not merely the ADB exit status.

`./tools/package-test-apk.ps1 -Abi arm64-v8a` subsequently passed with all checks
enabled (exit 0): 1750 passing module test executions, four live-provider skips,
zero failures/errors; privacyCheck, Lint, requireRime assembly and APK
signature/ABI/permission validation passed. Log:
`.tmp-evidence/auth-deadline-arm64-package.log`. Updated manual-test artifact:
`app/build/outputs/test-apk/20261101-014244/zero-fish-input-0.7.1-debug-arm64-v8a.apk`.
SHA-256: `50e973358eedc9e4277804aff35de4c34c968bce408b0f2b903257c738d31f1f`.
The directory includes its hash list, notices and corresponding-source archives.
This Debug package includes the prior clipboard fixes and this broker repair;
it supersedes the previous package for their manual regression. Native stripping
still warns about two packaged libraries; no size improvement is claimed.

Physical ARM/OEM behavior, actual system authentication and the remaining P1
baseline/source matrix are still unverified. P2/P3 production import is pending.
Release APK assembly, formal signing and publication were not performed.
Next: complete the P1 baseline/source matrix before production boundary changes.

## Clipboard self-audit follow-up (2026-10-31)

The user requested a self-audit and fixes before further feature development.
Scope: the P1 acquisition prototype, privacy scan, status layout and adjacent
direct-selection/import lifecycle. Existing uncommitted work is preserved.

Confirmed defects: cleanup ownership was granted by empty preflight without a
write; live acquisition eligibility was not rechecked after read; spaced and
receiver-free payload properties bypassed the scan; direct-selection handoff
expiry relied only on Handler delivery. New regression tests reproduced the
cleanup/eligibility failures, the scanner self-test failure and the overdue-token
acceptance before fixes. The audit also removed payload-printing comparisons,
restored test IME enablement, and bound cleanup metadata to the same verified
public snapshot. Parser-rejection tests now require actual sample acquisition.

Final emulator regression: all 18 cases passed; without opt-in, seven pure cases
passed and four platform cases skipped. App Debug JVM: 389 passed, two skipped,
including three new handoff tests. privacyCheck, negative scanner tests, Lint and
requireRime x86_64 main/test builds passed. The updated ARM64 Debug package was
rebuilt with the full-check script: 1742 passing module test executions, four
skips; privacy/Lint, requireRime assembly and signature/ABI/permission checks
passed. Artifact directory: `app/build/outputs/test-apk/20261031-230720`; the
P1 audit report records its SHA-256. It supersedes the prior package for manual
testing of the handoff-expiry fix; no physical ARM run has been performed.

See the [P1 validation audit section](clipboard-p1-validation.md#self-audit-follow-up)
for evidence. P2/P3 remain unimplemented; no production system-body read, new
permission, vault format/key change or actual-device acceptance is introduced.

## Clipboard strategy development plan (2026-10-31)

The user selected a dual-path strategy for subsequent development: retain direct
private copying, and add explicit import of the current system clipboard followed
by authenticated encrypted storage and best-effort current-item cleanup. Setup
guides users to system permission controls; an ordinary IME cannot change other
apps' clipboard permissions or guarantee atomic capture-and-clear.

The [development plan](clipboard-product-repair-plan.md) now defines requirements,
onboarding, entry flows, operation counts, data and authentication boundaries,
lifecycle states, cleanup races, coordination with the existing automatic guard,
module responsibilities, phased tasks, resource budgets and a 24-case acceptance
matrix. Unattended import after arbitrary clipboard changes remains a separate
extension; it is not silently included in the initial explicit-import scope.

P1 now has an androidTest-only fixed-public-fixture acquisition prototype and an
exact privacy-scanner exception with negative self-tests. Production payload
reads, permissions and vault formats remain unchanged. A pre-existing large-font
clipboard status clipping failure found by the new mdpi emulator was reproduced
and fixed using wrap-content above the existing minimum height.

Seven prototype tests and the final 14-test combined emulator suite passed on a
fresh Android 17/API 37 x86_64 AVD. App JVM tests: 386 passed, two skipped; ime-ui:
48 passed. privacyCheck (both merged manifests), its negative self-tests, Lint and
real-Rime x86_64 Debug/main-test builds passed. Evidence, commands, initial
failures and remaining gates are in [P1 validation](clipboard-p1-validation.md).
No explicit runner opt-in skips the three platform cases (four pure cases pass).

The user requests local Android Studio emulator validation first, then a new
ARM64 Debug package for manual physical-device testing. The packaging script
passed full tests (1736 passed executions, four skips), privacy/Lint, requireRime
ARM64 assembly and signature/ABI/permission validation. The current-stage Debug
APK and hash are recorded in the P1 report; it contains the layout repair but no
new system-import product entry. It was not installed on a physical device.
P1's API 26/27, 28, 29,
33/36 and custom-source matrix remains open; API 37 does not replace it. Next:
complete that coverage, then P2's precise production authorization/cancellation
and cleanup coordination and P3's product flow. Exact target-device/OriginOS/app
versions will be recorded during manual acceptance. No P2/P3 completion, physical
OEM support or performance acceptance is claimed. Earlier accessibility/OCR
entry proposals remain superseded; their dated evidence is preserved.

P0 documentation verification: relative file links, command inputs, requirement/test
IDs and fenced blocks checked; `git diff --check` passed. No build or device test
was run for this documentation-only change.

## Private-copy entry follow-up (2026-10-31)

This subsection records the earlier state before the strategy change above.

The user retains independent private copying and requests confirmation before
enabling incognito mode. System clipboard body collection is not authorized.
Implemented an enable-time disclosure with explicit confirmation; cancellation
or Activity recreation leaves the setting off. Private editor-selection drafts
now deny AI extraction in the request model and hide the AI import action.
The new negative regression failed before the fix.

Immediate repairs validated: 386 app JVM tests passed (two live-provider tests
skipped), privacyCheck including Debug/Release manifests, Lint and requireRime
x86_64 Debug/test builds passed. Seven emulator tests passed, including consent
cancellation/recreation and repeated private import. The consent dialog image
was inspected. Logs: `.tmp-evidence/privacy-copy-validation.log` and
`.tmp-evidence/privacy-copy-device.log`. No physical ARM or OEM app acceptance.

Remaining product gap: noneditable pages with custom
menus that expose neither PROCESS_TEXT nor text sharing have no private-copy
entry. A separate default-off, user-triggered accessibility page-copy proposal
is under design. The user requests local OCR efficiency/package evaluation before
deciding on screenshot/OCR integration. Research uses a separate, permission-free
benchmark app with generated public text; it does not authorize production capture.
The [OCR feasibility study](ocr-feasibility-2026-10-31.md) is complete at emulator
scope: single-line recognition works with the existing runtime; page detection
requires a different operator set. Full-runtime detection has substantial memory
and package costs. OCR has not passed the production-adoption gate; target ARM
typing/page measurements and a bounded capture implementation remain open.
Do not describe this disclosure as resolving that gap. No system-body capture,
new accessibility capability or permission has been added.

Validation command: `./gradlew.bat :app:testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel`.

## Symbol groups and v0.7.1 (2026-10-31)

Approved: named symbol groups, expanded catalog, commit/push and testing prerelease.
Implemented 12 groups with 1336 curated symbol entries and bounded pages.
Packaging tests, privacyCheck, Lint, universal APK signature/ABI/permission checks
and 21 emulator tests passed. Published [v0.7.1 testing prerelease](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.1)
with the universal APK, SHA-256 list, notices and corresponding sources;
see [v0.7.1 verification](releases/v0.7.1-validation.md). Physical ARM/OEM font
acceptance remains open.

## User-reported input repairs (2026-10-30)

Approved scope: symbol navigation/coverage, prominent private selection copy,
custom emoji deletion, Chinese Shift punctuation and personal frequency defects.
Implementation and automated verification are complete: 901 JVM tests passed
(two live-provider tests skipped), privacyCheck, Lint, real-Rime x86_64 Debug/test
APK builds and 40 emulator tests passed. Fixed-fixture layout images were checked
at narrow/regular/landscape sizes in light and dark themes. Earlier uncommitted
audit fixes remain intact. Next: physical-device and real social-editor custom
label acceptance; host-menu ordering and editors that omit spans/ignore Delete
remain platform limits. See [repair validation](input-bugs-2026-10-30.md).

## Current audit (2026-10-30)

Approved scope: repair reproduced input/interaction defects without new features,
data format changes or changes to the security model. Four defect categories
have fixes and six initially failing regression tests. Final checks passed:
890 JVM tests passed (two credential-dependent live tests skipped), privacyCheck,
Lint, requireRime Debug/test APK builds, and 34 x86_64 emulator tests. No Release
or physical ARM acceptance was performed. Next: maintainer physical-device/editor
acceptance; no known reproduced defect remains in this audit scope. See
[audit record](code-audit-2026-10-30.md) for commands, results and remaining limits.

## v0.3.0 testing prerelease (2026-09-23)

The release includes verified Chinese-engine readiness, persistent preparation
feedback, prediction/conversion privacy separation, offline public associations,
storage reliability fixes, hot-path improvements and corrected zero fish input
branding. Version 0.3.0-debug uses code 3 and the existing Debug signing identity.
See [release notes](releases/v0.3.0.md) and [validation](releases/v0.3.0-validation.md)
for current checks and remaining device boundaries.

## v0.4.0 testing prerelease (2026-09-28)

The release packages the AI workbench, stricter streaming and language-pack
validation, input/editor lifecycle repairs, private clipboard hardening, and
the follow-up audit regressions. Version 0.4.0-debug uses version code 4 and
the existing Debug signing identity. See [release notes](releases/v0.4.0.md)
and [validation](releases/v0.4.0-validation.md) for the final checks and
remaining device/signing boundaries.

## Association coverage follow-up (2026-09-21)

The approved priority is association coverage and evaluation. The project-authored
Chinese/English corpus grows from 286 to 1,728 pairs, including traditional-input
aliases and longer scheduling/help-request chains. Single-character Chinese
suffixes no longer trigger inside unrelated words. Public input fixtures expose
and guard both ranked output and complete editor commit chains. The frozen set
is a development/acceptance benchmark, not a blind quality estimate; broader
independent and physical-device evaluation remains open. See
[word-association-quality.md](word-association-quality.md).

## Storage reliability follow-up (2026-09-21)

The approved security-storage batch added ten JVM security tests and dedicated
device regressions for lost keys, corrupt bodies, silent AtomicFile failures,
clear/retry and concurrent metadata/removal. Reads no longer create replacement
keys; incomplete clear blocks access in the current vault instance and attempts
both dedicated resources. Queued management actions carry deletion generations,
and failed index refresh hides stale cached rows. Grant expiry cannot exceed
30 seconds. Authentication flow, aliases and persisted formats are unchanged.

The current verification, reproduced failures and limitations are recorded in
[security-storage-validation.md](security-storage-validation.md). Body/index
atomicity is per file; process-local failure state does not guarantee recovery
after process death. Hardware-bound authentication remains a separate design
decision, with API 26/ARM and physical authentication acceptance outstanding.
The original assessment below remains dated evidence, not the current test count.

## Initial public prerelease

The public name is now `zero fish input`; internal identifiers and encrypted
data formats retain their existing identity. The v0.1.0 prerelease adds the supplied
logo and launcher artwork, installation/contribution documentation, private GitHub
security reporting and packaged license/source notices. Validation and outstanding
device/signing work are recorded in [release-validation.md](release-validation.md).

## Starting point

The repository is a functional Android IME with ten registered modules, real
librime/JNI, full and nine-key Chinese input, an offline English engine, a small
reference dictionary engine, encrypted personalization, a private authenticated
clipboard and data-only language packs. Dependencies already follow stable
engine ports and bounded background queues. This checkout has no Git metadata.

The initial Gradle test/privacy/Lint run passed. There were 84 distinct unit
tests (160 executions including Android build variants), and the existing 32
device tests passed on the connected Android 17 x86_64 emulator. The missing
coverage was concentrated in encrypted storage and user dictionary boundaries.

## Completed scope

1. Unknown editor classes/variations previously reached the permissive default.
   They now use the sensitive path, clear preceding composition/candidates, and
   never instantiate a suggestion engine or access personal data.
2. Manual phrases could exceed the reader's 20,000-entry limit. Updates now enforce
   that limit and a 5 MiB compact JSON limit before writing; overflow cannot create
   an unreadable file or silently evict manually managed phrases.
3. User dictionary import now uses fixed-schema streaming validation. Malformed
   UTF-8, excessive nesting, duplicate fields/IDs/identities, unsupported languages,
   invalid lengths/scalars and trailing documents are rejected before mutation.
   Merges are indexed by phrase identity and cannot alias another local candidate.
4. Persistence now publishes a new memory snapshot only after a successful
   encrypted write. Clear invalidates older waiting updates and waits for active
   writes before deleting the file/key. A failed deletion blocks reads and writes
   until the user retries successfully. Tests inject storage failures through the
   new EncryptedStore port and use separate real Keystore aliases for crypto tests.
5. The phrase page confirms import merge rules and plaintext export scope before
   file selection. It uses localized safe failures, bounded inputs, lifecycle-aware
   callbacks, standard toolbar actions and a stable delete icon. Recycled rows and
   temporary byte buffers release their contents. Successful management changes
   invalidate the IME's personal-suggestion cache.

Format 1, the encrypted envelope, file name and production key alias are unchanged.
No migration, networking, permissions, external components or native algorithm
changes were introduced. See [ADR 0005](adr/0005-user-lexicon-boundary.md).

## Verification

- New failing regression tests reproduced the permissive editor default,
  over-capacity addition and acceptance of nested unknown JSON before the fixes.
- Core tests cover unknown editors following a composing session and password,
  visible password, web password, PIN, email, URI and privacy preference policies.
- The expanded device suite passed all 50 tests: 11 dictionary boundary tests,
  four real Keystore/storage tests, three phrase UI tests and the original 32.
- Phrase UI tests also passed separately in the actual system light and dark
  modes (three tests each), with 320/411/800 dp geometry, portrait/landscape,
  Chinese/English labels, confirmation gating and text contrast assertions.
  Android 17 synthetic night contexts did not consistently select application
  color resources, so actual system theme changes were used and restored.
- Generated public-fixture images were visually inspected for English/light
  export, Chinese/dark export and long phrase rows. No user data was captured.
- `./tools/package-test-apk.ps1` passed: 88 distinct unit tests (168 executions
  across JVM/Debug/Release variants), Debug/Release merged-manifest privacyCheck,
  Lint, real Rime builds for all three ABIs, and signature/ABI/permission checks.
- Artifact: `app/build/outputs/test-apk/20260906-154348/ZeroInput-0.1.0-debug-universal.apk`
  (28,903,783 bytes), with `SHA256SUMS.txt` beside it. SHA-256:
  `f963cf6d519884f6bd5b82ffdd542218a0382681b55b88f31933210d812ce925`.

## Next priorities

- The next-word suggestion baseline now uses bundled public Chinese/English
  phrase pairs, a memory-only prediction port and a bounded editor-local context.
  It supports tap-to-chain continuations with a separate default-on switch and
  conservative privacy gating. Coverage expansion and representative quality
  evaluation remain open. See [verification](word-association-validation.md)
  and [ADR 0013](adr/0013-offline-word-associations.md).

- Language-pack manifests now use bounded byte streaming with strict UTF-8
  decoding both during import and installed-package discovery; declared ZIP
  sizes remain an early bound, while extraction still verifies actual bytes and
  SHA-256 before activation.
- Continue physical biometric/credential and delayed callback acceptance.
  Automated consent, storage generation and grant-race coverage now exists;
  tests requiring a configured device PIN must be reported separately from the
  synthetic authorization tests. See the current storage validation report.
- Measure Chinese typing and touch latency on physical ARM devices and target
  applications. Emulator timings are not evidence of OEM/device performance.
- The full-pinyin secondary Rime session is now created only when related-reading
  expansion is first requested. Lifecycle generation checks close queued native
  work after a session ends; physical memory measurements are still required.
- Continue localizing the remaining management pages and handling their worker
  failures consistently. Avoid adding more engine features before these checks.

No Release APK was built or signed in this iteration. Physical ARM execution,
real biometric/credential UX and external document-provider overwrite behavior
still require maintainer/device acceptance. The delivered APK is a Debug test build.
