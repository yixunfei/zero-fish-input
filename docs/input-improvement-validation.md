# Input improvement validation

Status: original input improvements verified; physical-device measurements remain
open. The subsequent default-off Mini integration has separate verification in
[model integration](model-integration.md).
Date: 2026-09-08.

## Baseline

The unmodified application passed 109 instrumentation tests in 137.112 seconds
on the attached x86_64 emulator. The APK used the pinned real librime sources.

Command: `./tools/test-input-experience.ps1 -Serial emulator-5580`.

| Public fixture measurement | p50 | p95 | Maximum |
| --- | --- | --- | --- |
| Native key processing | 0.638 ms | 4.298 ms | Not reported |
| Strict pinyin key processing | 0.304 ms | 0.574 ms | Not reported |
| Candidate layout | 0.074 ms | 0.141 ms | Not reported |
| Attached key dispatch | 5.371 ms | 9.341 ms | 12.554 ms |
| Editor observation | 2.956 ms | 7.367 ms | 7.655 ms |
| Next frame | 14.869 ms | 29.667 ms | 30.745 ms |

These are synthetic emulator measurements, not physical touch or ARM results.
The existing fullscreen fixture passed; the reported small-height/rotation
failure still needs expanded regression coverage. No CPU or memory improvement
is established by this baseline.

## Implemented behavior and regression evidence

- Exact Rime paging continues into labelled alternative segmentation/near-reading
  sources. Device tests verify crossing that boundary, returning to the original
  page, and committing the selected related candidate with canonical reading.
  The core tests enumerate 80 synthetic candidates through a six-page window and
  reject a candidate whose restored page changed identity. Resident pages are
  deduplicated; results from pages already evicted may recur in another source.
- The immediate fallback supports all its prefix results, segment selection,
  segment undo and full-reading commits. Unit tests reach beyond eight entries.
  The reference dictionary now preindexes every bundled result on its worker,
  removing its former 64-result cutoff without scanning the dictionary per key.
- Unknown constructed phrase completion is tested through the controller and user
  repository: partial choices do not write, final completion learns the whole
  reading, and a reloaded repository/controller offers the new phrase. The test
  uses an isolated in-memory encrypted-store port; existing platform encryption
  and clear/write-race tests remain in the full suite. Personal paging enumerates
  117 entries without duplicate or missing IDs, beyond the former 3/50 limits.
- Native composition tests verify smaller-segment selection, undo and canonical
  readings for corrected input. Reopening is tested with an actual EditText and
  native engine, as well as the attached IME service. Changed text, cursor and
  connection reject reopening. Core tests cover session/privacy/reset/close and
  canonical-learning rejection.
- Small-height landscape regression covers 540/592/800dp widths and 280/320/360dp
  heights. Attached tests rotate the same fullscreen editor four times, check
  retained editor space and exercise input/deletion. Existing theme/height/font,
  numeric, nine-key, expression and clipboard-panel tests run in the full suite.
  Public fixture screenshots were pulled and visually checked; no real editor
  text was captured. A very narrow split-search keyboard necessarily has narrower
  key widths; row height is 48dp and host space takes priority.
- Full device command: `./tools/test-input-experience.ps1 -Serial emulator-5580`.
  Final runner result: **OK (121 tests)**, 177.624 seconds. Device: x86_64 emulator,
  Android 17 / API 37, 1080x2400 at 420dpi. This is not physical ARM verification.
  A subsequent fallback input-limit change was covered by its dedicated unit
  regression during packaging; it returns the extra key to the controller.
- Final review also routes Space/Enter through the visible candidate after
  browsing, and invalidates personal pages on data revision changes. New unit
  regressions cover both and immediate hiding before queued clear executes.
  This records the earlier behavior; current Enter commits raw composition,
  while Space still selects the visible candidate.
  A focused **28-test device run passed** after those production changes, in
  62.510 seconds (input panels/pipeline, native composition, reconversion and
  user-lexicon boundaries).

## Comparable final latency

The same public baseline fixtures and settings were used. Emulator scheduling
varies; these runs do not establish statistically significant improvements.

| Measurement | Baseline p50 / p95 | Final p50 / p95 | Final maximum |
| --- | --- | --- | --- |
| Native key processing | 0.638 / 4.298 ms | 0.679 / 4.323 ms | Separate resource run below |
| Strict pinyin | 0.304 / 0.574 ms | 0.338 / 0.621 ms | Not reported |
| Candidate layout | 0.074 / 0.141 ms | 0.077 / 0.174 ms | Not reported |
| Attached dispatch | 5.371 / 9.341 ms | 5.998 / 9.324 ms | 9.878 ms |
| Editor observation | 2.956 / 7.367 ms | 3.725 / 7.610 ms | 8.346 ms |
| Next frame | 14.869 / 29.667 ms | 14.852 / 18.257 ms | 19.366 ms |

Native latency remains roughly at baseline. Some median/UI values regress
slightly, while frame-tail timing improved in this run; neither should be
generalized to every phone. The concrete resource changes are bounded candidate
retention, recycled/differential list updates, no redundant editor rewrite on
paging, indexed reference lookup, and reuse of native dictionary data.

The final 28-test focused run reported dispatch p50/p95 6.397/10.429 ms, editor
4.433/8.577 ms and frame 15.133/17.531 ms. This variability reinforces that a
general speedup is not established; the native/UI functionality changes should
be evaluated further on the user's target phone before claiming lower latency.

## Resource and experimental quality measurements

`InputResourceTest` runs 1,900 timed synthetic keys per mode after warm-up.
PSS includes the application, test runner, previously used panels and native
resources; it must not be described as standalone keyboard memory or a leak test.

| Measurement | Default | Experimental typo rules |
| --- | --- | --- |
| Key p50 / p95 / p99 / max | 0.684 / 4.370 / 4.852 / 8.241 ms | 2.455 / 7.389 / 7.843 / 14.847 ms |
| Process CPU for measured key loop | 2,943 ms | 6,450 ms |
| Java allocations during loop | 5,144,576 bytes | 5,242,880 bytes |
| GC count / total GC time | 2 / 29 ms | 3 / 34 ms |
| PSS before / after loop | 129,929 / 132,967 KiB | 136,708 / 128,373 KiB |
| Native allocated heap after paging | 40,076 KiB | 37,985 KiB |
| Paging p50 / p95 / max | 0.190 / 0.506 / 0.506 ms (8 pages) | 0.321 / 0.532 / 0.715 ms (80 pages) |
| Preparation in this run | 35 ms | 15,080 ms including prism compilation |

A separate focused process run had default PSS 78,301 -> 80,644 KiB and a warm
experimental preparation of 152 ms. Compilation is substantially slower than
warm session creation and remains on the worker; the lightweight fallback stays
usable while it runs. The experiment roughly doubled CPU in this sample, so it
remains default off. There is no measured before/after CPU or PSS baseline from
the original app and no claim of reduced overall resource use.

`MatchingQualityTest` uses 16 clean phrases and eight constructed misspellings:

| Mode | Clean top-one / top-five | Misspelling top-one / top-five |
| --- | --- | --- |
| Default | 16/16 / 16/16 | 0/8 / 4/8 |
| Experimental | 15/16 / 16/16 | 8/8 / 8/8 |

One correct input's first candidate changed undesirably. These small selected
fixtures are regressions/screening, not population accuracy, false-positive
rates or evidence of parity with another IME. Further quality work should expand
held-out ambiguous, domain and long-composition sets before changing defaults.

## Remaining validation limits

- No physical ARM phone or Android 8/OEM editor was attached. Three-ABI packaging
  does not replace device testing, especially for vendor rotation/inset behavior.
- No exhaustive long-duration battery/leak soak, real touch-to-photon benchmark,
  competitor binary comparison or held-out production corpus evaluation was run.
- Follow-up Mini INT8 evaluation uses the user-selected Android 16 emulator and
  the user-confirmed UER Apache-2.0 licensing basis. Short-word size/latency gates
  pass, but the held-out quality gate still fails (1.06-point clean regression
  against a 0.5-point limit); see [small-model-evaluation.md](small-model-evaluation.md).

## Final build and artifact checks

- `./tools/package-test-apk.ps1` passed without `-SkipChecks`: full Gradle `test`,
  `privacyCheck`, app Lint, actual Rime Debug builds for arm64-v8a, armeabi-v7a and
  x86_64, and APK signature/ABI/permission checks. Final reports contain 155
  Debug/JVM test cases with zero failures; Release unit-test tasks also passed.
- `./gradlew.bat :ime-ui:lintDebug :app:assembleRelease -PrequireRime=true --no-parallel`
  passed, including all three native ABIs and Release shrinking. Release APKs
  are unsigned. Merged Debug/Release manifests both have `allowBackup=false`;
  Release has `debuggable=false`.
- No checks, allowlists, dependency pins or import limits were relaxed. The first
  full run found an unattached-button dispatch regression and an oversized setting
  label; both were fixed. DiffUtil replaced full adapter notifications to satisfy
  Lint and avoid unnecessary list rebinding.
- Final Debug universal APK: 29,860,949 bytes, at
  `app/build/outputs/test-apk/20260908-074905/zero-finish-input-0.1.0-debug-universal.apk`.
  SHA-256: `3bf273b307c817bc7702a08f17e3d7d419eb6841fa645f887db11300e9fcfec3`.
  The same directory contains `SHA256SUMS.txt` and the license-notice archive.
  This is a Debug-signed test artifact, not a signed production release.
- Source files remain below 1,500 lines; new methods remain below 100 lines.
  Research-model downloads and test images live only in ignored build directories.
