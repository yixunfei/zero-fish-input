# Offline handwriting: quality and reproducibility

This records the single-character simplified/traditional handwriting work on
2026-09-30. Recognition is entirely local. No editor text, clipboard, dictionary,
history, training feedback or handwriting telemetry participates.

## Runtime pipeline

1. The canvas retains at most 48 strokes and 512 coordinate pairs per stroke.
   Once a stroke reaches the limit, it is reduced to a 3/4-sized uniform sample
   that always keeps its first and latest points. This bounds long-press copying
   cost while preserving gesture endpoints. Both axes share a physical scale; a
   new stroke invalidates old candidates on touch-down. Resize, panel/session
   changes and multi-pointer cancellation cannot combine stale coordinates or
   candidates with later input. The writing surface can expand to the measured
   IME content height for full-screen handwriting and remains bounded by insets.
2. One replaceable request passes a 180 ms debounce and bounded worker queue.
   Malformed, nonfinite and oversized strokes fail before copying/model creation.
3. A pure Kotlin stroke classifier evaluates two pinned Tegaki Zinnia models.
   Coordinates are centered and uniformly scaled into a 0.9-wide square. The
   bounded feature tree follows Zinnia's public equations; sparse tables use only
   the features present in this request. Cancellation is checked every 32 feature
   entries and at most every 1,024 coefficient visits.
4. The existing PP-OCRv5 mobile ONNX graph receives a 48-by-96 RGB tensor. The
   48-by-48 character image has normalized-zero right padding. Its decoder sums
   complete CTC paths that produce exactly one character, instead of selecting
   isolated frame maxima. Blank-dominated and non-Han output is rejected.
5. The best stroke-model candidates and visual candidates are interleaved and
   deduplicated, capped at 16. This ranking is deterministic, without language,
   frequency, editor or personal-data ranking. The user selects the character.
   Existing editor binding/generation checks are still required at commit time.

The source graphs/models never change at runtime. Stroke tables are hash checked
while streaming from APK assets, with bounded lengths and validated offsets,
classes, Unicode labels and finite weights. They are data, not executable content.
Mutable coordinates, features, raster pixels, tensor input, decoder scores and
classifier scores are wiped. Native ONNX allocator copies cannot be guaranteed
byte-for-byte erased.

## Preparation and checks

From the repository root, using the existing Python model-evaluation environment
with NumPy, ONNX Runtime, Pillow and PyYAML:

```powershell
python tools/prepare-handwriting-model.py --include-quality-fixtures
python tools/evaluate-handwriting.py --split validation --count 256
python tools/test-handwriting-quality.py
./gradlew.bat :model-scoring:testDebugUnitTest :app:testDebugUnitTest
./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.zeroinput.ime.HandwritingModelQualityTest,dev.zeroinput.ime.HandwritingCanvasTest,dev.zeroinput.ime.handwriting.HandwritingCoordinatorTest"
```

The preparation script obtains immutable, size/hash-pinned public artifacts.
Evaluation is then offline. Its downloads are developer preparation, never an
application network path. The stroke-model packer preserves coefficients and
reorders them by feature; it does not train or quantize a model. The Android test
fixtures are generated under `build/handwriting-evaluation/platform-fixtures`,
only included in the test APK. Missing fixtures fail with a prerequisite message.

Reports are generated under `build/handwriting-evaluation/validation.json`,
including the model hashes, fixture-manifest hash, every public sample ID,
candidate outcomes and host-only times. No user handwriting is collected.

## Evaluation evidence and limits

The public fixture source is [Tegaki's repository](https://github.com/tegaki/tegaki)
at commit `7a74e442c4130cccc226a7e7c2b683ac94c0cccb`, under its model-data LGPL-2.1
license. `tools/handwriting-fixture-sources.json` pins every file's byte length and
SHA-256. It includes 6,763 simplified Tomoe handwritten templates, 39 separately
recorded traditional samples (one exceeds the production per-stroke point limit),
and 20 traditional files under `tegaki-models/data/test/traditional-chinese/`.
The latter contain recorded points and timestamps, not font-rendered images.

There are no exact stroke-sequence duplicates between those 20 test files and
the source XML shipped with the two selected model archives. This establishes
independence from exact training examples; writer identities and broader source
lineage are unavailable, so this is **not** a writer-held-out population benchmark.
The corpus is small and biased toward complex/rare traditional forms.

Desktop report (Python 3.13, ONNX Runtime 1.26.0, CPU, one inference thread):

| Independent traditional test strokes | Top 1 | Top 8 | Top 16 |
| --- | ---: | ---: | ---: |
| Original mobile OCR, 160-wide white padding and frame-maximum ranking | 2/20 | 4/20 | 4/20 |
| Corrected mobile OCR, 96-wide zero padding and single-character CTC | 3/20 | 7/20 | 7/20 |
| Hybrid simplified/traditional strokes plus corrected OCR | 13/20 | 18/20 | 18/20 |

The full traditional stroke model alone reached 14/20 top 1 and 17/20 top 8;
the 16.3 MB light alternative reached 10/20 and 13/20 after normalization. The
full model was selected for candidate coverage. Combining scripts and visual
alternatives trades one first-choice result for broader top-eight coverage.

On a fixed 256-sample simplified subset, the original OCR returned 214 first
choices and 243 top-eight matches; corrected OCR returned 226 and 246. The hybrid
returned 246 and 256. **The simplified samples overlap Zinnia training data:**
the hybrid numbers are only a reproducible regression check, not evidence of
unseen simplified handwriting accuracy. The development/validation SHA split
avoids preprocessing selection on the same sample IDs, but cannot remove that
training overlap. Fonts remain an explicitly separate `--font` sanity check.

The PP-OCRv5 server graph (84,503,027 bytes, upstream revision
`b70df217f4fd99d14f970bad092cebe7d74cc4d1`, SHA-256
`d9dc333c9c7b042c6dffb8e33d72b6f65c9c1d463d0a3c2f78174fea55e94752`)
did not improve the development corpus and took about four times the mobile
graph's host inference time. It is not bundled.

Pillow supersampling approximates Android Canvas antialiasing; these desktop
numbers do not substitute for the Android test. `HandwritingModelQualityTest`
reports content-free load time, Java/native allocation deltas, per-character
median/p95 latency and exact corpus counts through instrumentation status.
It requires at least 12 first-choice and 17 top-16 matches out of 20. These
regression floors allow a small rendering/runtime variation; they are not a
claim that the recognizer is accurate for all Chinese handwriting. Actual device
results belong in the delivery report, not an unrun assertion here.

## Source, licenses and artifact pins

The feature equations are adapted from [Zinnia](https://github.com/taku910/zinnia)
commit `581faa8f6f15e4a7b21964be3a5ec36265c80e5b`, principally
`zinnia/feature.cpp`, `feature.h` and `recognizer.cpp`, under BSD-3-Clause,
Copyright (c) 2005-2007 Taku Kudo. `zinnia/COPYING` has SHA-256
`822856fc8cec7c88e90e182dacd8eca5306836fc09df817c9dbb1d6337f6a1cc`.

The two [Tegaki v0.3 release archives](https://github.com/tegaki/tegaki/releases/tag/v0.3)
include original model coefficients, corresponding source XML, metadata,
Makefiles and the LGPL-2.1 text. The latter is 26,430 bytes, SHA-256
`a190dc9c8043755d90f8b0a75fa66b9e42d4af4c980bf5ddc633f0124db3cee7`.
Packed tables are a modified data layout and retain that model-data license.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `tegaki-zinnia-simplified-chinese-light-0.3.zip` | 7,015,643 | `598787133a4d59fcf3a2fbc5654c68eaf35a8e422274efcea2035cc081f3446c` |
| Archive `handwriting-zh_CN.model` | 9,432,616 | `c652150e428ab1d7e64ba2a16eeb706349c34f0586ef034c564cee0c2c432cd9` |
| Corresponding `handwriting-zh_CN.xml` | 8,751,834 | `969862706c883c89ae6b945549b141e0051847930e04d8fa8a26aad2b3d4db11` |
| `tegaki-zinnia-traditional-chinese-0.3.zip` | 36,773,128 | `f41032e67a4eff056813d243eabc32ea07eea404c714bdb5882a5b0fcda51690` |
| Archive `handwriting-zh_TW.model` | 52,210,768 | `cd47f16b64e7ecaa4c2813d0f98231dfea7b411f48be1ead5c9b2eeb19330b11` |
| Corresponding `handwriting-zh_TW.xml` | 24,523,156 | `85ab37c56bcb14a54a7ae106f2504b6505cf6c9917ca8c636312153fd9d00e53` |
| Generated `stroke-simplified.zsh` | 7,016,330 | `fdd47959e8cb95add75fc1e5bd10ff62e88b5d09fc6c6305d284d8f3df57667f` |
| Generated `stroke-traditional.zsh` | 39,052,454 | `7eaa62001987b03fa0ea24824b1a1203599064db905604026da8bc7e4e3b0288` |

Archive URLs are the v0.3 release download base followed by the exact archive
filenames above. Each archive's top-level directory is its filename without
`.zip`; it contains the listed model/XML/COPYING files. The two packed tables
contain respectively 6,763/11,853 Han labels, 3,721/6,056 feature entries and
1,155,405/6,484,859 coefficients. They add 46,068,784 uncompressed bytes; compressed
APK size is measured by the packaging check. No new runtime dependency is added.

## Remaining acceptance scope

- Use real finger/stylus input on supported Android versions, narrow single-hand
  layouts, resized floating panels, landscape, small screens and dark theme.
- Confirm recognition and undo/clear remain usable with pauses, very long strokes,
  a second finger/palm, cancellation and panel resize.
- Verify session switching, continued input, privacy changes, view destruction,
  delayed completion and recognizer failure cannot deliver an old candidate.
- Collect a larger, clearly licensed, multi-writer simplified/traditional corpus
  with no training overlap before making broad accuracy claims. Dense cursive,
  rare variants, merged strokes and unusual stroke order remain limitations.
