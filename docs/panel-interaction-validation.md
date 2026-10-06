# Panel and internal-input validation

Date: 2026-10-03. All fixtures contain constructed public text only.

## Changes

- Handwriting fullscreen uses the current safe window budget and switches the IME
  host to a full-height container. It no longer depends on the collapsed root's
  previous height. Collapse restores the compact panel and saved placement.
- A fixed header control expands secondary panels to approximately 65% of the
  available window, with the compact minimum preserved. Expanded floating and
  one-hand panels temporarily use the available width. Portrait editing budgets
  include both results and keys; landscape uses adjacent columns.
- Clicking the expression query enters an isolated local conversion session.
  Pinyin candidates, English, deletion, clearing, language changes and paging
  target the bounded draft. AI uses a separate instance of the same local input
  implementation. A final render after editor fallback keeps deletion current.
- Expanded candidate scrolling uses RecyclerView drag/fling with two-row
  prefetch, bounded incremental updates and visible-anchor preservation. Public
  and personal windows each retain at most six pages; initial candidate IDs stay
  stable and selection still verifies the engine page or personal-data identity.
- Both fuzzy master controls select every rule on enable and clear every rule
  on disable. Individual switches remain independent and update the master.
  Existing preference fields and deferred engine configuration are unchanged.

## Automated verification

From the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  privacyCheck :app:lintDebug :app:processReleaseMainManifest -PrequireRime=true `
  --no-parallel "-Pandroid.injected.build.abi=x86_64"
```

Device coverage uses the existing `ZeroInputReview20260928` x86_64 emulator. The
scoped suites cover public fixture sizes, light/dark themes, real orientation
changes, all placement modes, real IME fullscreen growth/collapse, actual query
typing/Chinese selection without an external-editor write, query revocation,
bounded local buffers, candidate prefetch/anchor/fling, and both fuzzy controls.

```powershell
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass `
  'dev.zeroinput.ime.PanelInteractionRegressionTest,dev.zeroinput.ime.InternalSearchInputTest,dev.zeroinput.ime.input.LocalDraftInputTest,dev.zeroinput.ime.InputPanelTest,dev.zeroinput.ime.ExpressionPanelTest,dev.zeroinput.ime.AiWorkbenchPanelTest,dev.zeroinput.ime.AiImeInteractionTest,dev.zeroinput.ime.KeyboardNavigationGestureTest,dev.zeroinput.ime.SettingsPanelTest,dev.zeroinput.ime.HandwritingCanvasTest,dev.zeroinput.ime.KeyboardWindowIntegrationTest,dev.zeroinput.ime.CompactLandscapeTest,dev.zeroinput.ime.KeyboardPlacementAndGlideTest'

./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass `
  'dev.zeroinput.ime.KeyboardWindowIntegrationTest,dev.zeroinput.ime.NativePinyinTest,dev.zeroinput.ime.ChineseOptionsTest,dev.zeroinput.ime.CompositionImprovementTest,dev.zeroinput.ime.CompositionEditingRegressionTest'
```

Results:

- All 389 module unit tests passed, with no failures, errors or skips.
- `privacyCheck`, `:app:lintDebug`, Debug APK/test APK builds, and Release merged
  manifest processing passed with `-PrequireRime=true`. Both merged manifests
  retain disabled backup and cleartext traffic; Release is not debuggable.
- The complete 51-test panel batch passed. The native/window batch passed all
  17 tests, including two window tests shared with the first batch.
- A final language-label isolation regression was added. After that change, the
  common build/checks and all 20 affected panel/search/AI tests passed. Across
  the successful batches, 67 distinct device tests were exercised.
- Public fixture images at 320dp portrait and 800dp landscape were inspected:
  results, keyboard rows and the fixed expansion control remain inside the panel.

Local logs: `build/panel-final-build.log`, `build/panel-device-verified.log`,
`build/panel-native-window-check.log`, and `build/panel-language-final.log`.
The x86_64 Debug APK is at
`app/build/intermediates/apk/debug/app-x86_64-debug.apk`; SHA-256:
`d000d1c2a9c806570ff93d97e42617fe1b0bd9d3ee9c77449b0e6f6a32456f82`.

The floating-window fixture now chooses a point outside both the IME rectangle
and system bars. Its former editor-center point could be inside the floating
keyboard, and an edge-to-edge editor's top-left corner can be under the status
bar. The candidate button fixture is attached to a real window so lifecycle
visibility checks are exercised without bypassing them.

## Manual acceptance and limits

1. Open handwriting from Tools, toggle fullscreen, write a public character,
   select it, then collapse. The canvas must grow and remain within safe bounds.
2. Expand expressions, enter search by tapping its label, type and select a
   Chinese query, delete/clear it, and switch English. Only selecting an expression
   inserts into the host editor. Leaving the panel or restarting input clears it.
3. Type a common syllable, expand candidates, then expand the panel. Drag and fling
   through multiple pages, return to earlier rows and select a candidate. Appended
   pages must not jump the viewport or stop a fling; new composition resets it.
4. Toggle fuzzy settings on/off and verify all 13 switches follow. Enable one
   individual rule and verify only it applies. Repeat using the keyboard shortcut.

No physical ARM device, OEM-specific split-screen environment, release APK build
or frame-time benchmark is claimed. JNI/native sources and third-party assets
are unchanged; x86_64 checks require real pinned Rime sources. The generated APK
is Debug-signed and is not a release artifact. The tests establish continuous
scroll semantics and retained fling, not a universal frame-rate guarantee.

The pre-existing `ZeroInputService` (1666 lines) remains above the general 1500-line target
but below the 2500-line complex-file limit. It retains Android lifecycle/session
ownership; draft conversion is factored into `input/LocalDraftInput` rather than
adding a second conversion implementation to the service. Further service
decomposition should follow lifecycle ownership boundaries in a separate change.
