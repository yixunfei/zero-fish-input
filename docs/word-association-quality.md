# Word association coverage and quality evaluation

Date: 2026-09-21

The approved follow-up expands public Chinese/English associations and adds a
repeatable evaluation. The corpus grows from 286 to 1,698 pairs. The frozen
development fixtures improve from 36/136 to 136/136 acceptable first choices;
unwanted suggestions on the 20 abstention fixtures decrease from four to zero.
These fixtures were visible during curation. This is a development/acceptance
result, not an independent estimate of natural-input accuracy.

## Corpus and runtime behavior

The project-authored [public table](../engine-dictionary/src/main/resources/word-associations.tsv)
uses the project's Apache-2.0 license. No external corpus, dependency, permission,
personal-data format or migration is introduced.

| Language | Initial pairs | Current pairs | Current language/prefix keys |
| --- | ---: | ---: | ---: |
| Chinese | 157 | 1,055 | 427 |
| English | 129 | 643 | 296 |
| Total | 286 | 1,698 | 723 |

Counts include traditional Chinese aliases; they are table rows and lookup keys,
not counts of distinct meanings. The UTF-8 resource occupies 33,307 bytes. The
expanded scenes include greetings, arrangements, work, travel, food, study,
health and assistance. Priority follows table order rather than measured usage
frequency. No private input or usage data was used to curate it.

The existing engine worker prepares the immutable index. Each input query uses
at most 32 UTF-16 units, probes a bounded set of suffixes, and returns at most
eight entries. Resource limits remain 4,096 rows and 128 characters per row.
The larger resource is not scanned or loaded on the key path. Context remains
session-local and wipeable; no editor surrounding text, personal history or
association-learning storage is added.

Evaluation and editor tests exposed three concrete gaps:

- Chinese one-character suffixes could trigger inside unrelated words. A
  one-character anchor now requires the complete context; longer suffixes keep
  longest-match behavior. This removes the four embedded-anchor false positives
  in the fixtures. It deliberately sacrifices some valid single-character
  completions in longer contexts until a reliable segmentation policy exists.
- A date-qualified morning phrase could fall back to a greeting. Explicit
  longer time phrases now take precedence over the bare time-of-day anchor.
- An English request had continuations after two words but lacked its first
  entry point. The corpus now supports the complete three-selection chain;
  the device test also checks the resulting word separators.

Selection remains explicit. Space/Enter never accept idle predictions, and
privacy, cursor, editor, language and settings invalidation retain the behavior
recorded in [ADR 0013](adr/0013-offline-word-associations.md).

## Method and results

[association-quality.tsv](../engine-dictionary/src/test/resources/association-quality.tsv)
contains 156 project-authored synthetic cases: 72 Chinese positive cases, 64
English positive cases and 10 abstention cases per language. It was frozen before
the expansion and used to guide it. Expected answers were not changed to obtain
the reported improvement. The file hash is pinned in the regression test.

[AssociationQualityEvaluation](../engine-dictionary/src/test/kotlin/dev/zeroinput/engine/dictionary/AssociationQualityEvaluation.kt)
calls the production `WordAssociationIndex`, requesting eight suggestions per
case. Positive cases specify one or more acceptable continuations. Coverage means
any suggestion was returned, even if incorrect. Top-k success means an accepted
continuation occurs in the first k positions. An abstention false positive means
any suggestion was returned for a case marked as requiring none.

The [recorded baseline](../engine-dictionary/src/test/resources/association-quality-baseline.tsv)
was evaluated before both corpus expansion and the single-character matching
fix. Consequently, the comparison measures the combined change and cannot
isolate the contribution of additional pairs from the matching correction.

| Positive cases | Coverage before | Coverage after | Top-1 before | Top-1 after | Top-3/Top-8 before | Top-3/Top-8 after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Chinese (72) | 18/72 | 72/72 | 16/72 | 72/72 | 16/72 | 72/72 |
| English (64) | 26/64 | 64/64 | 20/64 | 64/64 | 21/64 | 64/64 |
| Combined (136) | 44/136 | 136/136 | 36/136 | 136/136 | 37/136 | 136/136 |

Top-3 and Top-8 happen to have the same counts in this run. Combined coverage
changes from 32.4% to 100%, and Top-1 from 26.5% to 100% on this development set.

| Abstention cases | False positives before | False positives after |
| --- | ---: | ---: |
| Chinese (10) | 4/10 | 0/10 |
| English (10) | 0/10 | 0/10 |
| Combined (20) | 4/20 | 0/20 |

The new regression checks preserve baseline hits and their ranks, require
improved coverage and Top-1 in both languages, and reject every unwanted
suggestion on the abstention cases. A separate exhaustive table check queries
all 723 keys and verifies ordered, unique results within the eight-entry limit.
This table check establishes index consistency, not semantic quality for all
1,698 pairs or arbitrary suffix contexts.

### Reproduction

Run from the repository root:

```powershell
./gradlew.bat :engine-dictionary:test :engine-dictionary:evaluateWordAssociations `
  --console=plain --quiet
```

The report is written to `engine-dictionary/build/reports/association-quality.tsv`.
It records fixture IDs, language/category, result counts and accepted ranks,
without input or candidate text. This task runs in the JVM test source set; it
does not collect data from a running IME. Test fixtures and baseline results are
not runtime resources.

| Input or artifact | SHA-256 |
| --- | --- |
| Frozen fixture | `5e6e564b2021733285c1b77f62cb18afd9f898a1c64654357537919b5b57e40e` |
| Initial 286-pair corpus | `e658a8ef387d183d1db42f8cd8ac3fc308d5a75f6472c80aab9946fcf974ce2c` |
| Recorded baseline report | `981422bcd78ac9d106e6afc4fcfdd8556dbe9186462f196d0087db04985f7b26` |
| Current 1,698-pair corpus | `8d4fd8ebc715b2a5385ff6deef39bba1d78770e72307b3eaf8bf638dc654e1f6` |

Scoped `.gitattributes` LF rules keep these TSV bytes stable across checkouts.
The baseline report is stored in test resources; the initial corpus hash
identifies the measured input. The reproduction command evaluates the current
implementation and corpus, not the earlier implementation.

## Verification

The expanded implementation passed the following with pinned real Rime sources:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-dictionary:test :engine-dictionary:evaluateWordAssociations `
  :engine-api:test :engine-english:test privacyCheck :app:lintDebug `
  -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" `
  --no-parallel --console=plain --quiet
```

After the final time-phrase and English entry-point adjustments, dictionary
tests/evaluation, Debug APKs, `privacyCheck` and Lint passed again. Current
module JVM/Debug XML reports total **222 tests, zero failures/errors/skips**,
including 14 in `engine-dictionary`. Lint reports `No issues found.`
`privacyCheck` checks the Debug and Release merged manifests.

The final APK passed **8 instrumentation tests** on Android 16 / API 36,
x86_64 emulator `ZeroInputModelApi36`:

| Suite | Tests | Checks |
| --- | ---: | --- |
| `WordAssociationIntegrationTest` | 5 | English chains/separators, normal Space/Enter, Chinese expansion, embedded-anchor suppression, traditional output, cursor/settings/privacy/editor invalidation |
| `ModelSessionPrivacyTest` | 2 | Existing model context and editor privacy isolation |
| `InputPipelineTest` | 1 | Existing real-engine input pipeline |

Reproduce with an available API 36 emulator:

```powershell
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass `
  'dev.zeroinput.ime.WordAssociationIntegrationTest,dev.zeroinput.ime.ModelSessionPrivacyTest,dev.zeroinput.ime.InputPipelineTest'
```

`WordAssociationIntegrationTest` requires API 30 or newer. The emulator smoke
test does not establish physical-device latency or energy improvements. No
layout or theme changes were made in this follow-up; the earlier visual
acceptance remains documented in [initial validation](word-association-validation.md).

### Local artifacts

- Debug x86_64 APK: `app/build/intermediates/apk/debug/app-x86_64-debug.apk`.
- APK SHA-256: `e1524c72159909ba4d80c7ebb5d18432c73114da7f91df95f16cd037d65aedbb`.
- Build logs: `build/association-expansion-validation.log` and
  `build/association-expansion-final.log`.
- Final device log: `build/association-expansion-device.log`.
- Packaging/test-count audit: `build/association-expansion-audit.json`.

The APK archive contains the exact current public corpus bytes and no
`association-quality` fixture, baseline or report files. It contains only the
x86_64 native ABI. Generated paths are ignored and may be overwritten by later
builds; this hash identifies the artifact tested here. It is a Debug testing
build, not a signed production release.

## Limits and next evaluation

The development set is small, manually authored and visible while tuning. Its
100% first-choice result does not measure natural-input accuracy, unbiased
coverage, user preference or typing savings. Twenty abstention cases cannot
bound false positives on arbitrary input. Longer Chinese suffixes can still
cross semantic word boundaries, and a longest-match table cannot infer intent.

The next quality checkpoint needs separately licensed, independently selected
public sequential text, frozen before tuning and kept apart from development
fixtures. It should report per-scene/language coverage, Top-1/3/8 accepted
continuations, abstention errors and complete selection-chain behavior, with
manual review of ambiguous labels. Personal text, telemetry and editor-history
collection remain outside scope. Do not repeatedly tune against that evaluation
set and continue calling it independent.

ARM devices and API 26 were not exercised in this iteration because only the
API 36 x86_64 emulator was available. Release and three-ABI packaging were not
rerun; this follow-up changes no JNI, CMake or ABI configuration. Cold-start
memory, physical-device input latency and energy still need measurements. The
broader deficiencies in the [comprehensive review](analysis-2026-09-21-comprehensive-review.md)
remain open where those independent or device-level results are missing.
