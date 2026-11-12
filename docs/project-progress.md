# Project status

Updated 2026-11-12.

## Project audit and improvements

The user approved reviewing existing input, dictionaries, private drafts and settings,
fixing reproducible defects first, then improving missing states and engineering
checks. Each completed item gets its own commit and local backup branch.
New permissions, persistence formats and product capabilities require a separate decision.

- Fixed whole-emoji deletion for minimally-qualified and unqualified sequences,
  including individually omitted presentation selectors in ZWJ sequences. The
  pinned official corpus reproduced the failure before the fix and passes after it;
  ordinary digits and hash characters remain ordinary text.
- Full `testDebugUnitTest`, `:engine-dictionary:test`, `privacyCheck`,
  `:app:lintDebug` and `:app:assembleDebug` passed with
  `-Pandroid.injected.build.abi=x86_64 -PrequireRime=true --no-parallel`.
  The isolated x86_64 emulator passed
  `RgiEmojiTest#deletionKeepsOptionalPresentationSequencesWhole` using
  `tools/test-input-experience.ps1`; the installed APK hash was verified.
- Fixed device validation accepting zero-test summaries. The result gate now
  requires exactly one positive test-count summary and rejects runner failures,
  aborts and crashes. `./tools/test-instrumentation-result.ps1` passed 2 valid
  and 10 invalid fixtures; the real emulator run also passed through this gate.
- Fixed local-draft backspace splitting committed graphemes. Private input, AI
  drafts and expression search now share the bounded deletion boundary; removed
  buffer units are still cleared. A new device regression failed before the fix
  on a combining accent. All 10 `LocalDraftInputTest` device cases passed after
  the fix, covering masked/unmasked drafts, long input, conversion, late engine
  callbacks and personalization isolation.
  Debug and AndroidTest builds, `:app:testDebugUnitTest`, `privacyCheck` and
  `:app:lintDebug` passed with x86_64 and `-PrequireRime=true`.
- Dictionary failure review: the isolated `PublicDictionaryStoreTest` and
  `DictionaryManagementPlatformTest` passed 3 device tests for empty/cancelled
  replacement preservation, publication notifications, disabled-state retention,
  offline restore, encrypted-edit tampering and missing keys. No storage-format
  or download-authority change was needed for these paths.
- Settings review: 5 AI settings failure/lifecycle cases, 5 background storage
  cases and 3 appearance binding cases passed on the isolated emulator.
  The small-screen settings matrix initially reported overflow because its test
  included invisible trailing spaces (217.29 pixels versus 210.88 visible pixels
  in 212 available pixels). The assertion now uses Android Layout's visible
  extent; checks for ellipsis, missing text and clipped height remain intact.
  All 8 settings panel tests passed, including new negative fixtures for actual
  horizontal overflow, ellipsis and insufficient height. The matrix covers
  English/Chinese, light/dark and 1.0/1.5 font scale at 320 dp. Production layout
  did not need changing.
  `:app:lintDebug` and Debug/AndroidTest builds also passed with x86_64 and
  `-PrequireRime=true`.
- Engineering tool audit: 13 resource/APK integrity tests and 18 public-dictionary
  preparation/update tests passed (`python -m unittest discover -s tools/resources
  -p 'test_*.py' -v` and the same command with `-s tools/dictionaries`).
- Fixed flag deletion crossing an existing grapheme boundary: a trailing unpaired
  regional indicator no longer consumes half of the previous flag. The device
  regression reproduced a 4-unit deletion where only 2 units were expected.
  All 16 emoji/local-draft device cases passed after the fix, including odd/even
  runs, adjacent text, masked drafts and repeated deletion.
  All 11 Android editor and editor-failure cases also passed, including actual
  text/cursor preservation after deleting the unpaired indicator.
  Aggregate `testDebugUnitTest`, `:engine-dictionary:test`, `privacyCheck`,
  `:app:lintDebug`, Debug and AndroidTest builds passed with x86_64,
  `-PrequireRime=true` and `--no-parallel`.
  The pure JVM `:engine-api:test`, `:engine-english:test` and `:ai-api:test`
  tasks also passed (up-to-date).
- Completed the draft-editing extension approved on 2026-11-12:
  private input, keyboard AI and expression search support tap positioning, a
  visible caret, middle insertion/deletion and typing inside paired symbols.
  Composition finishes before moving; masked prefixes require explicit reveal.
  Changed renders, closed sessions, long presses and drags reject old gestures.
  No permissions or storage formats changed. See
  [ADR 0037](adr/0037-isolated-draft-caret.md).
  All 40 device tests passed on the isolated API 37 x86_64 emulator, including
  state, actual touch routing, masking, capacity, composition, long-query scrolling,
  stale gestures, AI interactions and panel layouts. The installed APK hash was
  verified before and after the run. Public-fixture renders were visually checked
  at 320 dp light and 640 dp dark with 1.3 font scale.
  Full Android module unit tests plus `:engine-api:test`, `:engine-english:test`,
  `:ai-api:test` and `:engine-dictionary:test`, privacyCheck, Debug/AndroidTest builds
  and `:app:lintDebug` passed with x86_64, `-PrequireRime=true` and `--no-parallel`.
- Fixed AI command truncation in the 640 dp landscape split at font scale 1.3.
  The detail-panel width now grows with the font scale (bounded at 1.5), retaining
  full touch targets and visible command text. The existing panel matrix reproduced
  the failure before the fix; all 10 AI panel tests passed afterward, including
  light/dark, portrait/landscape and 1.0/1.3 font scales.
  Aggregate unit tests, privacyCheck, Debug/AndroidTest builds and Lint also
  passed with x86_64 and `-PrequireRime=true`; Lint reported no issues.
- The approved audit scope is complete: existing input, dictionary failure,
  private draft and settings flows were reviewed; reproducible defects and the
  approved editing gap were addressed. Seven independent commits have local
  backup branches `codex/backup-20261112-01-emoji-deletion` through the named
  `06-ai-landscape` and `07-draft-caret` checkpoints. No remote push or release
  publication was performed.
  `ZeroInputService.kt` has 2169 lines, within the complex-file limit; any split
  should follow lifecycle ownership and include session regressions.
- Existing acceptance gaps remain: physical ARM input, the 80 MB package target,
  and live-provider tests with explicit temporary credentials (see below).

## Project audit follow-up (2026-11-12)

| Area | Correction | Commit |
| --- | --- | --- |
| Editor input | Backspace delegates one key to the current editor when cursor text is unavailable. Rebinding revokes fallback edits. | `4afd815` |
| First use | Open settings reflects changes from the Android keyboard picker. The installation guide follows the two system steps. | `cbfea9e` |
| Public dictionaries | Canceling a task reloads the installed list locally. Resource version checks restore the list after the prompt. | `92381f9` |
| Device evidence | Both APKs are verified before and after the run. Each successful invocation has its own report. | `2e65558` |
| Test results | Device reports count AndroidJUnitRunner passes and skips separately. An entirely skipped run cannot produce a pass report. | `e077e38` |

The five changes have individual local backup branches `08` through `12`
on the audit branch. Verification used the working tree with the existing
settings and draft refinements.

The x86_64 Debug and AndroidTest APKs built with `-PrequireRime=true`.
The JVM suites had 1,043 passes and two opt-in live-provider skips, including
reused Gradle results. `privacyCheck`, Lint and the Debug/Release merged manifest
checks passed. The API 37 x86_64 emulator passed 48 device checks and skipped
three opt-in live dictionary imports. Both installed APK hashes were verified.
The actual system picker was exercised at 320 dp, 1.5 font scale and dark mode.
A live public resource version check returned to the installed list after its
prompt was dismissed. The device runner also recorded a two-pass/one-skip run
and rejected an all-skipped run. PowerShell result fixtures passed with two
valid and 18 invalid cases. The Python resource and dictionary tooling passed
13 and 18 tests respectively.

The ARM64 full Debug APK passed content, ABI and signature checks and measured
92,404,458 bytes, exceeding the 80 MB target by 12,404,458 bytes. The largest
asset groups are the reviewed prebuilt Rime data, dictionary tables, emoji and
Mini model. See the [size optimization record](apk-size-optimization.md).
Physical ARM, earlier Android releases and unsigned Release packaging were not
exercised in this pass. Evidence is under `.tmp-evidence/goal-*` and
`build/device-apk-verification/`.

## Current release

[v0.7.3](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.3) is the current Debug-signed testing prerelease. It includes private temporary input, offline Han shape lookup, keyboard toolbar improvements and public dictionary import fixes. See [release notes](releases/v0.7.3.md) and [release validation](releases/v0.7.3-validation.md).

## Public dictionary import

The catalog now distinguishes download and import from visiting the source
website. QQ resolves the selected identifier directly and upgrades only the
known legacy CDN package URL to HTTPS. Sogou matches the selected item and
keeps its required download parameters. Oversized GitHub files are omitted.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest `
  :engine-dictionary:test :language-pack:testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

The build and checks passed. Isolated API 37 emulator tests passed with local
import fixtures and with real QQ, Sogou and Chinese Wikipedia source files.
The online cases require the explicit instrumentation argument
`-e livePublicDictionary true`.

## Keyboard tools

Private copy uses a 48 dp icon with the existing accessibility label.
The primary toolbar exposes private temporary input and the secure clipboard.
More tools places additional actions ahead of the primary actions.
Debug APKs, unit tests, privacyCheck and Lint passed with `-PrequireRime=true`.
The API 37 x86_64 emulator verified direct access to both privacy actions at
320 dp portrait width, rotation and light/dark layout coverage.

## Private temporary input and Han shape lookup

The confirmed default toggle and clipboard append boundary are recorded in
[ADR 0036](adr/0036-private-temporary-input.md). The private panel starts on
supported ordinary text editors after authentication enables the default.
Composition and candidates stay visible while the draft masks text before
the last three characters. The full-display toggle lasts for the current draft.
Save and close writes an encrypted item. Save and submit also writes a copy
by default, controlled by the setting in Privacy. Submit only goes directly
to the editor.

Shape lookup uses a build-verified index of 41,874 characters. The panel accepts
a radical or a pinyin seed, shows ranked matches in a scrolling candidate list and commits only the
selected result. The pinned sources and generation procedure are recorded in
[Third-party notices](../THIRD_PARTY.md#offline-han-shape-lookup).

The API 37 x86_64 emulator passed 57 release regression tests after upgrading
from v0.7.2, including the real system PIN toggle, private pinyin candidate
selection, masked and full draft display, length-limit handling, submit-only
with copy retention enabled, authenticated paste with separate confirmation,
and scrolling and selection through shape matches. See
[release validation](releases/v0.7.3-validation.md).
The new flows are in `PrivateDraftAndShapeIntegrationTest`. Run the private
draft flow only on a disposable emulator with its fixed test PIN and the
explicit `-e clipboardTestPin 2468` argument.

## Next verification

- Validate installation and input on a physical ARM device. The existing device run used an API 37 x86_64 emulator.
- Continue measuring the [80 MB APK target](apk-size-optimization.md) for the ARM64 package. The v0.7.3 ARM64 Debug split is 92,380,591 bytes.
- Run the live-provider cases when explicit temporary test credentials are available.
- Keep system clipboard text import limited to the test-only P1 prototype. See the [clipboard plan](clipboard-product-repair-plan.md) for pending verification and scope.

## Development checks

```powershell
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

Keep the current status here. Place transient logs, review drafts and one-off validation evidence in the ignored `.tmp-evidence/` directory. Keep public release evidence with the corresponding [release notes](releases/).
