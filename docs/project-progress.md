# Project status

Updated 2026-11-13.

## Personal-frequency review and repair (2026-11-13)

The user approved the review/fix plan, supplied `xz -> 限制`, and later narrowed
its failure to QQ chat/search and some other applications; most editors work.
The initial ordinary-editor source already passed this example. QQ's actual
EditorInfo and the user's physical device have not been observed, so the policy
fix below addresses a reproducible editor-class defect without claiming verified
QQ-version-specific diagnosis.

Delivered:

- Separate Chinese conversion personalization from general learning/prediction
  permissions. The user explicitly approved this scope on 2026-11-13 (ADR 0038).
  Known non-sensitive NO_SUGGESTIONS, AUTO_COMPLETE and FILTER editors can query
  personal composing candidates and learn accepted explicit selections.
  Password/PIN, unknown, email/URI, no-personalized-learning, incognito and disabled
  learning still override it, including combined flags. Implicit commits, English
  personal completions, emoji/kaomoji history, associations, model context and AI
  retain their existing restrictions. No application package exception is used.
- Preserve learned priority independently of candidate IDs. Words deduplicated
  onto native selection routes can no longer be overridden by optional model
  ranking; late results after learning are rejected too.
- Rank dictionary edits and learned rows together before pagination. Low-weight
  edits no longer crowd high-frequency exact matches out of the first page.
  Exact matching, recency ties, duplicate override routes, suppressions and
  disabled-dictionary filtering are preserved; queries normalize consistently.

No persistent format, Android permission, network path or native implementation
changed. Existing uncommitted settings/draft work is preserved. No changes have
been committed or published. AGENTS.md, architecture, threat model, SECURITY,
README and ADR 0015/0038 record the approved candidate-only permission.

Validation:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test `
  privacyCheck :app:lintDebug :app:processDebugMainManifest `
  :app:processReleaseMainManifest "-Pandroid.injected.build.abi=x86_64" `
  -PrequireRime=true --no-parallel
```

The command passed: 1,052 JVM tests passed and two opt-in live-provider cases
were skipped (including reused Gradle results). Lint reported no issues.
Four original sorting regressions and two editor-flag regressions failed before
the corresponding fixes. Negative coverage includes all flag combinations,
privacy tightening, sensitive editors, implicit commits with opt-in enabled,
rejected commits, English isolation, queued writes, clear, corruption and keys.
Evidence: `.tmp-evidence/learning-review-red-*.log`,
`.tmp-evidence/learning-review-editor-flags-red.log`,
`.tmp-evidence/learning-review-editor-flags-unit.log` and
`.tmp-evidence/learning-review-editor-flags-full.log`.

The isolated API 37 x86_64 emulator verified 34 distinct platform cases. The last
broad run passed 33; a multiline fixture's hidden action label prevented the
restart acknowledgement assertion in one case after its learning and encrypted
persistence assertions had passed. An opt-in Debug fixture action declaration
preserves multiline and privacy flags while exposing the new editor action.
The final dedicated fixture uses AppCompat and preserves the ordinary fixture's
platform widget. All eight real-IME learning cases and four Chinese availability
cases then passed, with installed main/test APK hashes verified before and after.
An earlier restart fixture race
was also corrected by waiting for the replacement editor action, not the old
READY indicator; no production session safety checks were weakened.

Coverage includes `xz -> 限制` at thresholds 1 and 3, per-click encrypted counts,
a fresh repository read, session restart, promoted Space selection, real screen
touches with model ranking enabled, no-suggestions chat, autocomplete, filter and
combined flags, full/initial pinyin, later-page single characters, nine-key,
double pinyin, model privacy, dictionary integrity, candidate-window continuity,
no-suggestions Chinese/English behavior and editor spans.
Evidence: `.tmp-evidence/learning-review-editor-flags-device.log`,
`.tmp-evidence/learning-review-final-fixture-device.log` and
`build/device-apk-verification/20261113-011523-2c938f021c5342c981646350708d1ccf.json`.
The intermediate fixture Lint failure and AppCompat fixture regression are retained
in `.tmp-evidence/learning-review-fixture-lint-red.log` and
`.tmp-evidence/learning-review-appcompat-fixture-red.log`.

Final fixture privacyCheck and Lint passed with no issues. The ARM64 Debug test
APK was built and verified with:

```powershell
./gradlew.bat privacyCheck :app:lintDebug -PrequireRime=true --no-parallel `
  "-Pandroid.injected.build.abi=x86_64"
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000 -SkipChecks
./gradlew.bat :app:testDebugUnitTest -PrequireRime=true --no-parallel `
  "-Pandroid.injected.build.abi=arm64-v8a"
```

`-SkipChecks` reused the equivalent full unit/privacy/Lint checks above and the
final fixture checks; it did not replace them. Contents, signature, ABI and
permissions passed validation. The final artifact is
`app/build/outputs/test-apk/20261113-091952/zero-fish-input-0.7.3-debug-arm64-v8a.apk`:
92,406,962 bytes, within the 180 MB test-package limit but above the 80 MB target.
SHA-256: `a84bada4815433ae59e424ba7f6f496976e6b4726f8a8e8886ab074d5eec4a3b`.
Evidence: `.tmp-evidence/learning-review-final-privacy-lint.log`,
`.tmp-evidence/learning-review-final-arm64.log`,
`.tmp-evidence/learning-review-final-app-unit.log` and the artifact's
`APK-SIZE.json`, `BUILD-PROVENANCE.json` and `SHA256SUMS.txt` companions.
The final app unit-suite rerun passed after the Debug fixture changes. The
isolated emulator started for this review was stopped after platform checks.

Next: physical-device confirmation in the affected QQ version when available.
Physical QQ/ARM devices, earlier Android versions and Release packaging are not
verified in this review; merged Release manifest checks are not a Release build.
On the affected device, keep learning enabled and incognito disabled, check the
promotion threshold, explicitly choose `xz -> 限制` that many times, then type
`xz` again in both chat and search. No private text, account data or application
logs are required for this check.

## UI refinement follow-up (2026-11-12)

User requested another optimization pass within the approved UI scope.
Implementation and acceptance complete: Debug/AndroidTest builds,
testDebugUnitTest, privacyCheck, Lint (no issues), and 19 device checks passed.

- Category descriptions and trailing arrows now belong to the same clickable,
  keyboard-focusable row, with one accessible button label and native ripple.
- Appearance recreation restores both the selected section and scroll position.
- Reselecting the current appearance section retains the existing controls,
  checked state and focus instead of rebuilding the section.

Before-fix device tests reproduced summary taps staying on HOME and appearance
scroll restoring to 0 instead of 813 pixels. No preference formats, permissions,
input rules or data/authorization flows changed. Prior uncommitted work remains.
Evidence is recorded in `.tmp-evidence/ui-refine-*.log`.
The device suite covers SettingsPanelTest, SettingsNavigationTest,
AppearanceNavigationTest, AppearanceInteractionTest, AppearanceSettingsLayoutTest
and KeyboardAppearanceTest on the isolated API 37 x86_64 emulator. APK identity
was verified before and after the run. Home screenshots were reviewed in Chinese
light mode and English dark mode at 1.5 font scale.
Final APK SHA-256:
`e527e777fd6184eefa8194ebfd8fd6ea30c788e746fe20ad76a22b015bd0f76e`.
Final evidence: `.tmp-evidence/ui-refine-final-checks.log` and
`.tmp-evidence/ui-refine-final-device.log`. Changes remain uncommitted;
physical ARM devices and Release builds were not tested in this pass.

The preview geometry test now waits for pre-draw and verifies desired row heights
and full visibility in short landscape windows, retaining the portrait height
requirement. The old unconditional minimum incorrectly rejected a valid 640x320
landscape layout; no keyboard compression or container behavior was changed.

## UI optimization goal (2026-11-12)

Status: implementation and acceptance complete. Final Lint reports no issues.
User confirmed the plan on 2026-11-12. Existing uncommitted draft-caret fixes
are preserved; no changes have been committed or published for this UI work.

Delivered:

- Settings home now has six focused destinations: input, appearance,
  dictionaries/languages, privacy/clipboard, AI, and general/about. IME activation
  remains on the home page. A persistent heading/back control and per-category
  scroll restoration survive Activity recreation. Fuzzy-settings entry opens
  the input category. Navigation retains pending persistence locks and does not
  write preferences.
- Appearance uses Style, Keys, Candidates and Background sections, compact
  wrapping choices, explicit checked states and four coordinated presets with
  color swatches. Presets reuse existing preference fields and preserve height,
  candidate settings and user backgrounds. The default preset matches defaults.
  The isolated keyboard preview remains visible in portrait and landscape.
- Settings headings and rows wrap at large font scales; language-pack names
  and their actions have independent rows. Chinese and English resources agree.
  Existing authentication, destructive confirmations, opt-outs and permissions
  remain unchanged. No engine, network or persistence-format changes were made.

Validation:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" `
  -PrequireRime=true --no-parallel
```

The full command passed. Both preset unit tests passed. Thirty device tests
passed on the isolated API 37 x86_64 emulator across SettingsPanelTest,
SettingsNavigationTest, AppearanceSettingsLayoutTest, IncognitoConsentTest,
PersonalRankingSettingsTest, AiSettingsFailureTest, AppearanceInteractionTest,
KeyboardAppearanceTest, KeyboardMaterialTest, InputWindowThemeTest and
KeyboardAppearanceBindingTest, using tools/test-input-experience.ps1.
Installed APK SHA-256 was verified before and after the run:
`3b7c951ba6a11434a66a78f13e9fec25fa6f3abc2766e4449998ce27ac631dd7`.
The final device run includes a test-only fix restricting checked-state assertions
to appearance controls, excluding unrelated buttons in the keyboard preview.
The subsequent `:app:lintDebug` recheck passed with no issues.

The matrix covers 320 dp, Chinese/English, light/dark, 1.0/1.5 font scales,
long language-pack labels, back/recreation/scroll, live preview geometry and
portrait/landscape. Public-fixture images were visually inspected for settings
home, large-font dark settings, fixed previews and attached preset controls.
Physical ARM devices and a Release APK were not tested in this UI-only change.
Transient evidence: `.tmp-evidence/ui-acceptance-*.log`, `.tmp-evidence/ui-preview/`
and `build/device-apk-verification.json`.

The existing palette/night resources and preference schema are reused.
SettingsPanelTest now enters each relevant category and checks reachable controls
instead of relying on a single tree containing all settings.

Category ownership:

| Category | Existing controls/actions |
| --- | --- |
| Input | Chinese modes, fuzzy rules, ranking, haptics, sounds, associations, paired symbols |
| Appearance | Palette/material presets, key shape/height, candidate layout, backgrounds and isolated preview |
| Dictionaries/languages | Personal/public dictionaries, expressions, language-pack import/selection/removal |
| Privacy/clipboard | Learning, incognito, private drafts, private clipboard, clipboard guard, personal/private data clearing |
| AI | Enable/network/thinking, providers, assistant, page references, device agent, AI data clearing |
| General/about | IME enable/switch/status, interface language, engine status and version |

## Recent-change review (2026-11-12)

Reviewed the seven commits after `e1b1fec` through `c2a1e29`, with a clean
working tree at entry. Fixed two P2 regressions:

- Scrolled draft text applied the view scroll offset twice when drawing its
  caret. Content-coordinate drawing now stays aligned with the visible text.
- Local backspace truncated its prefix at 64 UTF-16 units, which could leave
  an unmatched surrogate in a long grapheme and mispair long regional-indicator
  runs. It now uses the full prefix within the existing draft size limit.
  External-editor lookbehind, storage and permission boundaries are unchanged.

Both new device regressions failed before the fixes. Afterward, 52 tests passed
on the API 37 x86_64 emulator across LocalDraftCaretTest, LocalDraftCaretPanelTest,
LocalDraftInputTest, AndroidEditorConnectionTest, RgiEmojiTest, AiWorkbenchPanelTest,
AiInteractionPanelTest and SettingsPanelTest. APK hashes matched before and after
the run. The instrumentation-output checker passed 2 valid and 10 invalid cases.

Validation passed:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  privacyCheck :app:lintDebug :app:processReleaseMainManifest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/test-instrumentation-result.ps1
```

Lint reported no issues. Debug/Release merged manifests retain disabled backup;
Release is not debuggable. Unchanged unit-test tasks reused Gradle outputs.
Physical ARM devices and older Android ICU versions were not tested in this
review; a full Release APK was not built. Changes remain uncommitted.
Transient evidence is in `.tmp-evidence/review-*.log`.

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
