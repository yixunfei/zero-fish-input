# Code audit follow-up - 2026-09-28

## Scope and preserved work

This review covers the current working tree, including input and engine failure
paths, asynchronous authentication, private clipboard dialogs, AI response
validation, settings work, and language-pack parsing. Existing uncommitted work
and the earlier `code-audit-2026-09-28.md` were preserved. This report describes
the additional repairs from this review; it does not claim exhaustive absence
of defects. No commit was created.

## Confirmed defects and repairs

| Area | Defect and correction | Regression evidence |
| --- | --- | --- |
| Authentication | Cancellation could not revoke a successful callback already queued to the main thread. Keep the pending request until delivery and atomically claim delivery; cancellation and duplicate completion cannot deliver a stale grant. | Three `AuthenticationBrokerTest` platform cases; queued-success cancellation failed before the repair. |
| Private clipboard UI | Add/delete dialogs did not inherit the activity's secure-window protection. Protect each dialog, disable view-state saving, autofill and content capture, clear dismissed draft fields, and close dialogs with the page. | Three `SecureClipboardManagerPrivacyTest` cases; protection assertions failed before the repair. Dismiss/finish assertions wait for actual asynchronous lifecycle completion. |
| AI response | Truncated/filtered/tool completions could become usable text; Android JSON string coercion accepted non-string content. Reject non-stop finish reasons and require string deltas. | JVM `AiSseReaderTest` and Android `AiSseReaderPlatformTest`. |
| AI conversation title | Title truncation could split a supplementary Unicode character. Truncate at a valid UTF-16 boundary. | `AiWorkbenchControllerTest`. |
| Chinese composition | Declined input or an engine exception could replace partially selected composition with its raw code, losing selected text. Preserve the visible composition for these fallback paths. | Three `PartialCompositionFallbackTest` cases cover Enter, literal/unconsumed input and engine failure. |
| Model ranking | Executor-factory/runtime failures could leave the drain flag set and retain pending buffers. Clear pending work and restore a retryable state on scheduling failure. | `AsyncCandidateRankerTest` factory-failure, buffer-clearing and retry case. |
| Nine-key Rime | Numeric pinyin input bypassed the 128-character composition bound. Apply the existing bound to nine-key digits. | Real Rime `NineKeyPinyinTest`, including rejection at the limit and backspace/retry. |
| Language packs | Recursive JSON parsing was unbounded; lenient tokens could bypass a naive depth scan. Preflight depth and lexical tokens, bound strict UTF-8 dictionary input, reject invalid JSON without text fallback, and validate remaining JSON even after the entry limit. | Eleven `LanguagePackJsonBoundaryTest` cases plus two Android `LanguagePackJsonPlatformTest` cases using real ZIP installation, preserved prior packages and usable valid replacements. |
| Settings work | Background exceptions/rejected execution could crash or leave misleading success state. Use a lifecycle-aware runner, preserve interruption, report generic failure and treat failed enable/delete operations as failures. | Six `SettingsTaskRunnerTest` cases and module tests/Lint. |
| Small-screen settings | The English AI-clear button label needed 286px but had only 232px at 320dp. Shorten both localized labels to Clear AI data while retaining the complete deletion scope in the confirmation dialog. | `SettingsPanelTest` passes English/Chinese and light/dark combinations with the original width assertions. |

Architecture and security documentation were updated in `architecture.md`,
`threat-model.md` and `../SECURITY.md`. No new permission, network path, dependency,
persistent format/version or compatibility layer was introduced. No test, Lint
or privacy rule was weakened.

## Final verification

Executed from the repository root:

```powershell
./gradlew.bat check privacyCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease -PrequireRime=true --no-parallel
```

- Build successful: `build/review-complete-check.log`.
- 328 JVM/Debug unit tests passed: 30 JVM-only and 298 Android-module Debug tests.
  The corresponding 298 Release unit tests also passed; these are not counted
  twice. XML reports contain no failures, errors or skipped tests.
- All module Lint and `privacyCheck` passed. Gradle reports existing deprecated
  feature usage that will need attention before a future Gradle 9 upgrade.
- Debug and unsigned Release APKs built for arm64-v8a, armeabi-v7a and x86_64
  with required real Rime sources. Each Release APK contains its matching
  `libzeroinput_rime.so`. Merged manifests retain disabled backup/cleartext,
  approved permissions, and a non-debuggable Release application.
- 54 selected Android instrumentation tests passed on a fresh Android 36
  x86_64 emulator: `build/review-platform-verified.log`. The final run installed
  APKs from `app/build/outputs/apk`, not stale intermediate artifacts.

The platform selection covers authentication delivery, private dialog privacy,
AI parser/panel/settings, real nine-key Rime, editor deletion, reconversion,
paste consent, import validation and vault, encrypted storage failures,
clipboard-guard runtime, small-screen settings and language-pack installation.
The exact final class selection is:

```text
dev.zeroinput.ime.auth.AuthenticationBrokerTest
dev.zeroinput.ime.SecureClipboardManagerPrivacyTest
dev.zeroinput.ime.ai.AiSseReaderPlatformTest
dev.zeroinput.ime.NineKeyPinyinTest
dev.zeroinput.ime.AiWorkbenchPanelTest
dev.zeroinput.ime.AiSettingsFailureTest
dev.zeroinput.ime.input.AndroidEditorConnectionTest
dev.zeroinput.ime.ReconversionTest
dev.zeroinput.ime.clipboard.SecurePasteConsentTest
dev.zeroinput.ime.clipboard.ClipboardImportIntentTest
dev.zeroinput.ime.clipboard.ClipboardImportVaultTest
dev.zeroinput.ime.SecurityStorageBoundaryTest
dev.zeroinput.ime.clipboardguard.ClipboardGuardRuntimeTest
dev.zeroinput.ime.SettingsPanelTest
dev.zeroinput.ime.LanguagePackJsonPlatformTest
```

## Limits and remaining validation

- The entire instrumentation suite was not executed. This run selected tests
  for the reviewed risks and affected behavior.
- No physical ARM/OEM device, hardware biometric or device-credential end-to-end
  flow was exercised. Before distribution, repeat authentication cancellation,
  app/editor switching, rotation and private-window preview checks on devices.
- No real AI provider request was sent. Network-provider interoperability and
  timeout behavior against real servers remain unverified in this review.
- The language-pack engine can scan up to its bounded dictionary size per key.
  No latency measurement demonstrated a defect, so no speculative performance
  rewrite was made; benchmark maximum-size packages on target devices.
- No fresh third-party download/bootstrap or formal signing/package-test script
  run was performed. Existing pinned native sources were built. Release outputs
  remain unsigned and are not described as publishable artifacts.

The temporary review emulator was stopped after validation. Automatic approval
review rejected the AVD directory cleanup command with `blocked by policy`;
`build/review-avd` and its `ZeroInputReview20260928` AVD registration remain.
Build logs and reports remain in the ignored build directory.
