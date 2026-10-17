# AI draft conversion and continuous candidate scrolling

## Confirmed behavior

AI drafts must convert Chinese independently of the host editor. Expanded
candidates must scroll continuously, load near either edge and retain the visible
position without replacing the viewport with a page. No manual paging mode,
personal-data access, network request or stored-format change was added.

## Causes and repairs

- `LocalDraftInput` previously discarded a prepared public engine whenever a
  composition was active. It now uses the controller's validated plain-pinyin
  handoff. Partially selected segments reject migration and retry after finishing
  or clearing the composition. Closing/replacing a draft still revokes delivery.
- Rime can change preedit separator formatting when a different candidate page
  becomes highlighted. `CandidateWindow` now retains the current presentation
  during a consumed page change with unchanged raw input and no commit. This
  prevents `zhong guo ren` becoming `zhong guoren` from clearing the cached window
  and resetting the UI. Actual raw-input changes still clear the old window.
- Expanded rows retain complete overlapping row widths through append, prepend
  and eviction. One candidate/pixel anchor survives consecutive deliveries before
  layout. An identical refresh no longer cancels a queued edge request.
- Public and personal candidate windows retain their existing six-page limits.
  Selection continues to restore and verify the engine's original page and item.

## Reproduction evidence

The new delayed-preparation test and four viewport continuity tests were run
against the existing pre-fix APK from `20261016-102839`: all five failed. The
failures showed the fallback engine remaining installed, visible rows moving on
eviction/prepend, consecutive updates losing their anchor, and a cancelled edge
request. The same cases passed on the repaired APK.

A further real-Rime regression reproduced the preedit-separator change after
three explicit page loads. The final implementation retains the first candidate
and selects it correctly after browsing. This also resolved the failure found
by actual AI keyboard touches in landscape.

## Build and automated validation

Run from the repository root in PowerShell:

```powershell
$env:JAVA_HOME = 'D:/env/jdk17'
./gradlew.bat --gradle-user-home .gradle-user testDebugUnitTest privacyCheck `
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" `
  "-Pandroid.aapt2FromMavenOverride=D:/env/AndroidSDK/build-tools/36.0.0/aapt2.exe" `
  --no-parallel --console=plain
./gradlew.bat --gradle-user-home .gradle-user :engine-api:test `
  :engine-english:test :engine-dictionary:test :ai-api:test `
  -PrequireRime=true --no-parallel --console=plain
```

Both commands passed. Across Android and JVM modules, 703 tests passed and two
credential-dependent live-provider tests were skipped. Privacy checks, Lint,
Debug APK and instrumentation APK builds passed. Debug/Release merged manifests
retain `allowBackup=false`; Release remains non-debuggable.

The default Maven AAPT2 failed to open its generated `stableIds.txt` on this host,
including after cache-independent resource regeneration. The installed SDK 36
AAPT2 completed the same resource/build/check tasks. The command-line override is
local validation configuration; project dependencies and checks were not changed.

## Device coverage

Initial Android 16 x86_64 emulator runs, real bundled Rime, public synthetic
fixtures only:

- Landscape: 20 tests passed, including delayed draft conversion with physical
  key/candidate touches, private-draft isolation, partial-selection retry, stale
  callback rejection, forward/backward fling, eviction, prepend and viewport reset.
- Portrait: 25 tests passed, including AI interaction, draft conversion, candidate
  continuity, continuous scrolling, viewport reset, density and Chinese availability.
- Candidate density fixtures exercised 240/360/720 dp widths, light/dark themes,
  list/adaptive layouts, long-word wrapping and appearance changes. Public rendered
  images were inspected at narrow dark and wide light sizes.

Build/device logs are local ignored files named `build-ai-input-scroll-*.log`.
Rendered public fixtures are under `build/validation/ai-input-scroll-20261016/`.
The emulator rotation settings are restored after each orientation run.

## Remaining validation

An additional phone AVD (1080 x 2400 at 420 dpi, gesture navigation) exposed a
separate landscape limitation: the system IME-dismiss control overlaps the centre
of the AI Send button. The existing physical-submit test closes the panel and
fails its result assertion; invoking the same button's callback produces the
expected disabled-network result. The other ten tests in that additional run,
including physical Chinese conversion and candidate continuity, passed. This
overlap remains unresolved; the additional phone run is not a wholly passing
suite. The test retains its physical tap and now waits for a visible result and
reports panel-state metadata on timeout. No artificial delay or callback-only
replacement remains in the final test.

No physical ARM device or full Release/three-ABI packaging run was performed.
No AI provider was contacted; this repair concerns local conversion and browsing.
On a physical device, open AI in an ordinary text editor, immediately type
`zhongguoren`, select the Chinese candidate, and verify the host editor stays
unchanged. Then enter `ni`, expand candidates, fling in both directions and select
an earlier visible result. Repeat in landscape and after closing/reopening AI.
