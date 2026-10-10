# Third-round review repair validation

Date: 2026-10-09 (Asia/Shanghai)

## Scope

The user approved verification and repair of N1-N7 from `../CODE_REVIEW_BUGS.md`.
Existing uncommitted work was preserved; no commit, reset or dependency change was
performed. P1-P3 remain observations. No stored format, permission, network path,
exported component, encryption or authentication contract changed.

Storage failures now use a dedicated fixed-message callback or history-list error
state instead of an output failure event. Completed answers retain one-time
insertion only while their original session, context and data remain valid.
Panel reset closes full reference preview. Focus revalidation releases its root
node. Repeated editing still revokes pending work but skips redundant output
clearing. Page entry uses the broker's availability policy. Both startup subtype
selection and `switchTo` update language/package in one preferences transaction.

N6's original scenario was too broad: learning-disabled/incognito sessions normally
fail the editor gate before capture. The real duplication between entry and broker
has been removed without relaxing that earlier gate.

## Regression evidence

Four new controller tests were executed against the old implementation and failed:
save failure/result retention, history failure/result retention, repeated editing
cancellation, and clearing a configuration error only once. After repair they pass.
Further tests cover rejected automatic history refresh, delayed storage failure
after five revocation conditions, and editing while a history read is pending with
no displayed output. Existing atomic language notification and deletion-generation
tests remain in place. Unicode preview tests now include exactly 160 UTF-16 units.

JDK 17 commands executed sequentially from the repository root:

```powershell
./gradlew.bat :app:testDebugUnitTest `
  --tests dev.zeroinput.ime.ai.AiWorkbenchControllerTest `
  -PrequireRime=true --no-parallel
# Before repair: 29 cases, 4 expected regression failures.

./gradlew.bat :app:testDebugUnitTest `
  --tests dev.zeroinput.ime.ai.AiWorkbenchControllerTest `
  --tests dev.zeroinput.ime.settings.LanguageSelectionSettingsTest `
  -PrequireRime=true --no-parallel

./gradlew.bat testDebugUnitTest :ai-api:test :engine-api:test :engine-english:test `
  :engine-dictionary:test privacyCheck :app:lintDebug :app:assembleDebug `
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" --no-parallel

./gradlew.bat :app:lintDebug privacyCheck -PrequireRime=true `
  "-Pandroid.injected.build.abi=x86_64" --no-parallel
```

All post-repair commands succeeded. The full command took 27 seconds; the final
Lint/privacy check after the fixture change took 11 seconds. Lint reports
`No issues found.` Debug and Release merged manifests both disable backup;
Release has no debuggable attribute. Native sources were required, not replaced
with a fallback-only build.

| Module | Unit tests | Failures / errors / skipped |
| --- | ---: | --- |
| ai-api | 10 | 0 / 0 / 0 |
| app | 223 | 0 / 0 / 0 |
| engine-api | 5 | 0 / 0 / 0 |
| engine-dictionary | 30 | 0 / 0 / 0 |
| engine-english | 12 | 0 / 0 / 0 |
| engine-rime | 51 | 0 / 0 / 0 |
| ime-core | 124 | 0 / 0 / 0 |
| ime-ui | 41 | 0 / 0 / 0 |
| language-pack | 44 | 0 / 0 / 0 |
| model-scoring | 20 | 0 / 0 / 0 |
| security | 10 | 0 / 0 / 0 |
| user-data | 44 | 0 / 0 / 0 |
| Total | 614 | 0 / 0 / 0 |

Counts come from the selected task XML reports. Unchanged tasks used Gradle
up-to-date/cache outputs; this is not a claim that every case was newly executed.

## Platform verification

Device: `emulator-5554`, Android API 36, x86_64. Tests use public fixtures and fake
AI providers; no live provider requests were sent.

```powershell
$reviewClasses = @(
  'dev.zeroinput.ime.AiWorkbenchPanelTest',
  'dev.zeroinput.ime.ai.AiStorageFailurePanelTest',
  'dev.zeroinput.ime.ai.AiWorkbenchFlowTest',
  'dev.zeroinput.ime.ai.page.PageReferenceBindingTest',
  'dev.zeroinput.ime.ai.page.PageReferencePlatformTest'
) -join ','
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass $reviewClasses
```

The first combined run passed 17/18 cases; standalone page testing reproduced the
same failure. The test requested capture immediately after opening the AI panel,
before its accessibility window events had settled. Those events can correctly
revoke a just-started capture. The fixture now waits for UI/accessibility idle
after panel opening, as it already did after opening the source Activity. No
production invalidation rule or assertion was removed. Standalone page testing
then passed 2/2; the final combined run passed 18/18 in 22.946 seconds.

| Suite | Passing cases |
| --- | ---: |
| AiWorkbenchPanelTest | 9 |
| AiStorageFailurePanelTest | 2 |
| AiWorkbenchFlowTest | 2 |
| PageReferenceBindingTest | 3 |
| PageReferencePlatformTest | 2 |

Coverage includes save exception and queue rejection with visible insertable
answers, list read exception/rejection with retry and answer recovery, reset to
short preview, disabled/disconnected entry guidance, restricted session rejection,
stream failure visibility, explicit insertion, stale delivery and real external
page filtering/source revocation. Panel layout assertions cover 320dp portrait,
640dp landscape, light/dark and font scales 1.0/1.3. Public fixture PNGs for enlarged
portrait context and landscape page selection were also visually inspected.

The script verified the installed main APK against its local SHA-256 before and
after the final test run:

- APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`
- SHA-256: `f85437577f02860241044a06230b648b0224a4ab0663061875968001a32c896d`
- Package: `dev.zeroinput.ime.debug`, version `0.4.0-debug`.
- This is a Debug test artifact, not a signed release.

Local logs are under `build/review-round3-*.log`; fixture PNGs are under
`build/review-round3-images/`. Generated files are not added to source control.

## Limits

- API 26-32 accessibility-node pool behavior and older platform caches were not
  tested on-device; the finally-based recycling path was inspected in source.
- Physical-device gesture/three-button navigation acceptance from historical L13
  remains outstanding. Vendor accessibility/editor behavior was not verified.
- Only the x86_64 Debug APK was built this round. ARM APKs and Release APK/signing
  were not tested; no JNI, CMake, ABI or dependency changes were made by this repair.
- No key-latency benchmark was run. N5 removes provable redundant rendering without
  claiming a measured performance improvement.
- `ZeroInputService.kt` remains a pre-existing large lifecycle orchestration file
  (1843 lines, below the complex-file ceiling of 2500). This repair adds only local
  callback/transaction wiring; a broader responsibility split is outside its scope.
