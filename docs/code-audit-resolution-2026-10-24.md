# Audit remediation: 2026-10-24

This follow-up covers the code findings in [the audit](code-audit-2026-10-24.md).
The owner approved full-display-name contact renaming, explicit rejection of
multi-raw contact mutations, stable keyboard key positions, and serial validation.
Existing uncommitted personalization and settings changes were preserved.

## Changes

| Finding | Resolution |
| --- | --- |
| Stage 6 provider reads ignore cancellation | Operation-owned `DeviceStage6Queries` passes a registered Android cancellation signal to SMS, call-log, contact and calendar queries, checks validity before each lookup and unregisters on completion. |
| Unclear write/sensor cancellation | Microphone uses non-blocking reads and a cancel-wakeable wait; camera cancellation clears its transient buffer and wakes completion. The worker owns cleanup. ADR 0026 documents non-cancellable native write/Binder behavior, no retry and no rollback guarantee. |
| Contact rename retains old surname | Creation and update write the full display name; update clears obsolete structured/phonetic components. Android ContactsProvider tests verify lookup by the exact new name. |
| Aggregated contact account ambiguity | Update/delete reject multiple raw records explicitly before writing. Provider tests verify both records remain unchanged. |
| Header changes when candidates appear | Idle, candidates and tools use the same viewport header budget; compact landscape policy remains. Tests cover height and key coordinates. |
| Narrow settings labels | Long switches wrap; navigation, commands and mode controls grow with text. ToggleGroup's forced single-line configuration is overridden after adding children; grouped buttons share the tallest child's height and use narrow padding. Tests inspect complete rendered lines at 320 dp, English/Chinese, both themes and font scales 1.0/1.5. |
| Threshold dialog lifecycle | MainActivity owns and dismisses the dialog. Recreation/closure tests verify detachment, field clearing and the independent dialog window's FLAG_SECURE. |
| AI test contract drift | Tests use the existing insert-result ID, explicitly enter section mode where needed, and retain no-auto-insert, privacy, revocation and storage-failure checks. Full-answer action text wraps in a correctly aligned fixed action row. The thinking switch sizes to its content and uses SwitchMaterial, matching the IME's MaterialComponents theme so its thumb and track render. |
| Unstable dictionary rendering test | The synthetic viewport uses a detached hierarchy without unrelated original-window IME insets. Screenshots are saved before assertions; controls must fit their bounds and ancestors, remain usable and avoid truncation. The search field has a 48 dp minimum touch height; disabling baseline alignment prevents the search button from extending below its row. |
| Window/emoji test instability | Window assertions use the IME window's own navigation inset, which may differ from the editor's. Emoji readiness and pixel/click assertions use the same main-thread turn so RecyclerView cannot detach the observed cell between them. |
| Combined lint/test build failure | Android-test lint consumers now depend on the pinned handwriting fixture asset-generation task. No checks or hash requirements were relaxed. |

## Verification

All Gradle checks use JDK 21, the configured JDK 17/21 toolchains,
`-Pandroid.injected.build.abi=x86_64 -PrequireRime=true --no-parallel`.
Logs and XML snapshots are kept under `.tmp-evidence/audit-fix-*`; these
generated local artifacts are not source files and are not intended for commits.

```powershell
./gradlew.bat testDebugUnitTest :ai-api:test :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  "-Porg.gradle.java.installations.paths=D:/env/jdk17,D:/env/jdk21" `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./gradlew.bat :app:connectedDebugAndroidTest `
  "-Porg.gradle.java.installations.paths=D:/env/jdk17,D:/env/jdk21" `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

| Check | Result |
| --- | --- |
| JVM/Debug unit tests | 878 cases across 157 reports: 876 passed, no failures/errors, two explicitly credential-gated live-host tests skipped. Debug results and the four explicitly invoked pure JVM suites are counted; stale Release unit reports are excluded. |
| Privacy, lint and build | `privacyCheck`, `:app:lintDebug`, Debug app/test APKs and Release main-manifest processing passed. Lint reports `No issues found`. See `audit-fix-complete-checks-final.log`. |
| Merged manifests | Debug and Release main manifests retain `allowBackup=false` and `usesCleartextTraffic=false`; Release has no `debuggable=true`. This is a manifest check, not a Release APK build. |
| Focused device regressions | 43 tests passed for cancellation, keyboard positions/window insets, AI, dictionary and emoji behavior. After the final UI refinements, all 17 settings/AI device tests passed again (`audit-fix-visual-final-acceptance.log`). These runs do not replace the full-suite result. |
| Full API 36 emulator suite | 404 XML test cases: 392 passed, no failures/errors, 12 skipped. Gradle completed successfully in 24 min 22 sec. The archived result is `audit-fix-device-final.xml`; the matching log is `audit-fix-device-final.log`. The runner's console footer says 416, but the XML contains exactly 404 cases, including 12 skips; the XML is the counting authority. |
| Visual review | Saved public-fixture screenshots were reviewed for 320 dp settings in English/Chinese at font scales 1.0/1.5, light/dark themes, dictionary portrait/landscape, and AI result actions at font scale 1.3. The screenshot helper verifies that its requested setting is actually in view. |

The 12 device skips are not passes. They comprise two live-AI credential-gated
tests, two optional public-resource download/fixture tests, two secure-clipboard
tests requiring an explicitly configured disposable-device PIN, and six
`SystemClipboardPlatformTest` cases with default-IME, monitoring and untouched
clipboard-fixture prerequisites. These prerequisites were not enabled or met in
this serial run. The device XML records empty skipped elements rather than the
specific failed assumption; the complete skipped-case list is archived in
`audit-fix-device-final-summary.json`. No test gate was weakened to run them.

The five initial provider/contact regression tests failed before the fix and
passed afterward. The in-flight non-cancellable-write test additionally checks
that cancellation does not retry or start the fallback insert after the write
returns.

## Remaining boundaries

- Android cannot forcibly cancel or roll back a dispatched provider write,
  SMS/call request, or a provider that ignores cancellation. The bounded worker
  can remain occupied; the task is invalidated and old results are discarded.
- Physical-device camera/microphone cleanup latency, vendor provider behavior,
  real account synchronization and accessibility screen-reader behavior still
  require device acceptance. No personal records or live credentials were used
  by the new provider tests.
- `ZeroInputService` remains approximately 1,988 lines, within the documented
  2,500-line exception. Its broad decomposition is deferred to avoid unrelated
  lifecycle changes in this defect fix.
- This work does not configure release signing or establish release readiness.
  This remediation did not repeat a three-ABI build or `assembleRelease`; it
  changes Kotlin/UI/provider adapters rather than JNI/CMake or third-party code.

## Physical-device acceptance still required

1. With disposable test records, repeat full-name rename and exact-name lookup;
   verify multi-account update/delete rejection leaves all account rows intact.
2. Exercise permission revocation and cancellation during slow provider queries;
   verify stale results never reach the model and the next task can run when a
   cooperative provider returns. Do not assume cancellation undoes writes.
3. Cancel one-shot camera/microphone capture and measure resource release on
   vendor hardware; test late callbacks, service teardown and permission loss.
4. Repeat narrow-screen, font-scale, rotation, split/floating-window and screen
   reader checks on a physical device. Synthetic screenshots and emulator
   assertions do not prove every vendor window/inset or accessibility behavior.
