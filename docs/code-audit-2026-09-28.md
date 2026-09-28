# Code audit and repairs - 2026-09-28

## Scope

Reviewed the current working tree, including the existing uncommitted AI
workbench. Existing changes were preserved. Review covered the input/editor
boundary, engines and preparation, AI request/history/settings state, encrypted
repositories, language-pack activation, UI validation, and build scripts.
The review prioritized data loss, privacy, lifecycle races and observable input
errors. It is not a proof that every execution path is defect-free.

## Repairs

| Area | Reproduction and correction | Regression evidence |
| --- | --- | --- |
| Editor backspace | Selecting the middle of `a<selection>z` then pressing Backspace deleted `a` and retained the selection. Replace an acknowledged selection with empty text before using surrounding-text deletion. | `AndroidEditorConnectionTest`: real Android EditText; both selection directions and supplementary-character deletion. The original implementation failed on the device before the fix. |
| Language-pack replacement | If moving an existing package to backup failed, rollback deleted the still-valid original directory. Rollback now requires a successful backup move; backup cleanup after activation cannot roll back a valid new package. | `LanguagePackDirectoryReplacementTest`: failed backup move, failed activation and successful replacement. |
| Learned text | Learning accepted malformed UTF-16 that UTF-8 serialization replaced, so memory and persisted words differed. Reject unpaired surrogates before reading or writing the store. | `UserLexiconRepositoryTest`; existing Unicode, corruption and storage tests also pass. |
| Learned frequency | 2,148 valid readings of one word at frequency 1,000,000 overflowed the summed Int and could reverse ranking. Accumulate through Long and saturate at Int.MAX_VALUE. | `UserLexiconFrequencyTest` exercises the actual Android JSON reader and repository. |
| AI history | Continuing a saved chat rebuilt local history from the smaller network projection, prematurely dropping messages. Append to the original conversation and retain the documented last 20 messages. | `AiWorkbenchControllerTest.continuedConversationPreservesMessagesExcludedFromNetworkContext`. Network context limits remain in force. |
| AI completion | Empty/whitespace terminal output was rendered as completed although it could not be inserted or saved. Invalid completion now renders failure and never becomes usable. | `AiWorkbenchControllerTest.invalidTerminalOutputIsRejectedBeforeItBecomesInsertable`; includes oversized output. |
| AI settings | A rejected save replaced the UI's configuration with defaults, allowing the next edit to lose the saved endpoint, model and key. Preserve these fields while showing both activation switches off. The repository also rejects ports and key control characters already rejected by transport. | `AiSettingsFailureTest` exercises dialog rejection, reopening and saving with the original key; `AiRepositoryTest` checks invalid values leave stored bytes/cache intact. No network request is sent. |
| UI module Lint | Full `check` failed on indexed theme-color access and quantity heuristics applied to hexadecimal diagnostic flags. Use MaterialColors and narrowly annotate the diagnostic resource as a non-quantity. | All module Lint tasks pass; preparation UI passes theme/size and attached-window checks. No global Lint rule or privacy check was relaxed. |
| Bootstrap | Boost extraction ignored a failing tar exit status and could promote a partially extracted directory. Abort before moving that directory. | PowerShell syntax validation; the existing pinned dependency build passes. A fresh Boost download/extraction was not performed. |

No persistent format/version, permission, engine contract, network path or
authentication policy was changed.

## Verification

Run from the repository root with the existing Gradle wrapper:

```powershell
./gradlew.bat check privacyCheck :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat check privacyCheck :app:assembleRelease -PrequireRime=true --no-parallel
```

Both commands passed. The final reports contain 292 JVM/Debug unit tests with
zero failures, errors or skips. The 262 Android-module unit tests also pass in
the Release variant; these are the same tests and are not counted twice. All
module Lint tasks and `privacyCheck` pass. Logs are local generated files under `build/`:
`audit-final-check.log`, `audit-release-check.log`, `audit-clean-platform.log`.

The signed x86_64 Debug APK and unsigned Release APKs for arm64-v8a, armeabi-v7a
and x86_64 were inspected with Android build tools. Package names and native
ABIs match the build variants, permissions match the approved allowlist,
`allowBackup` and cleartext traffic are disabled, and Release is not debuggable.
The Rime build uses the pinned native sources with `requireRime=true`.

The following targeted device command passed 34 tests on a fresh Android 36
x86_64 emulator:

```powershell
./tools/test-input-experience.ps1 -Serial emulator-5582 -TestClass 'dev.zeroinput.ime.input.AndroidEditorConnectionTest,dev.zeroinput.ime.UserLexiconFrequencyTest,dev.zeroinput.ime.UserLexiconBoundaryTest,dev.zeroinput.ime.EncryptedUserStoreTest,dev.zeroinput.ime.SecurityStorageBoundaryTest,dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.AiSettingsFailureTest,dev.zeroinput.ime.EnginePreparationUiTest,dev.zeroinput.ime.KeyboardEditorIntegrationTest,dev.zeroinput.ime.ReconversionTest'
```

Use the serial of the connected test device when repeating the command. A first
attempt on an existing AVD passed 30 of 33 tests; its Digital Wellbeing suspension
blocked three activity launches. An isolated fresh AVD resolved that environment
failure, and the rerun included the additional AI settings regression.
Preparation-bar screenshots were also inspected in light and dark themes; text
and controls remain legible. The temporary audit emulator was stopped and
deregistered afterward. Automatic command policy blocked recursive cleanup of
the remaining `build/audit-avd` directory; roughly 1.2 GB of generated test disk
files remain there.

## Remaining limits

- Real AI provider interoperability and latency were not tested; all test text
  and credentials were public fixtures, with no real provider request.
- No physical ARM device, OEM editor, hardware biometric flow or power-loss
  durability test was run. The device suite above is targeted, not every
  instrumentation test in the repository.
- Release packages are unsigned validation artifacts, not signed releases.
- Existing Gradle deprecation warnings remain; this audit did not migrate the
  toolchain or change dependency versions.
