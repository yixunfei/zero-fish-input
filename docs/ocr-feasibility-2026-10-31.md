# Private-copy OCR feasibility, 2026-10-31

## Decision

Do not integrate full-page OCR into the IME yet. Single-line recognition is a
plausible candidate for explicitly invoked private text capture, but the current
reduced runtime cannot execute the tested detector. Replacing it with the full
runtime has substantial size/memory costs. This is a measured feasibility study,
not acceptance of real-app OCR or permission to add screenshot collection.

The independent entry remains necessary: the user identifies QQ, WeChat, LINE,
messages, social feeds and web/article bodies as primary targets. A custom menu
must not determine whether the private-copy tool can be invoked. Node reading and
OCR are capture strategies behind that entry, not replacements for solving it.

## Reproducible experiment

Separate permission-free `tools/model-benchmark` application; Android API 37
x86_64 emulator; one CPU inference thread with background priority. No screen,
editor, clipboard or real message data was read. Generated public Chinese,
English, mixed-script, numeric and URL fixtures were drawn with Android Canvas.
Alternate lines use a dark background, explicitly inverted before recognition.
Recognition resizes a known line crop to 640 x 48; this is not page segmentation.
Twelve distinct fixtures are repeated five times (60 samples, including cold
first inference). Detection uses uniform tensors at two resolutions, 12 samples
each. It measures kernel time only, without preprocessing, contour extraction,
line cropping, reading order or recognition. This deliberately cannot establish
end-to-end page latency or accuracy. P95 with 12 samples equals the maximum.

Models are prepared by `tools/prepare-ocr-evaluation.py` with exact length/hash
checks. The recognizer and dictionary reuse the existing optional handwriting
resources; handwriting stroke models are unnecessary for OCR. Recognition uses
standard CTC sequence decoding here, not the product's single-character decoder.

| Resource | Bytes | SHA-256 |
| --- | ---: | --- |
| PP-OCRv5 recognizer | 16,534,782 | `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092` |
| Character table | 74,012 | `d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b` |
| PP-OCRv5 mobile detector | 4,819,576 | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` |

Recognizer provenance: existing PaddlePaddle revision
`ed152b8b495f84de93cda5709d768548a9127622` in `tools/prepare-handwriting-model.py`.
Detector provenance: [RapidAI model catalogue](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml),
fixed [v3.10.0 model artifact](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx).
The checked hash, not the catalogue's moving main branch, identifies the tested
file. PaddleOCR and RapidOCR publish Apache-2.0 licenses; production packaging and
redistribution remain a separate review. ONNX Runtime stays at the already pinned
1.26.0 full/reduced artifacts.

## Measurements

| Measurement | Existing reduced runtime | Full runtime |
| --- | ---: | ---: |
| Recognizer session creation | 98.5 ms | 94.8 ms |
| First line, including fixture preparation/decoding | 101.9 ms | 93.4 ms |
| Line P50 / P95 (60 samples) | 46.9 / 60.7 ms | 45.9 / 49.5 ms |
| Process PSS before model loading | 14.5 MiB | 14.5 MiB |
| Sampled PSS after recognition | 87.0 MiB | 99.6 MiB |
| Detector 480 x 800 P50 / P95 | ORT_NOT_IMPLEMENTED | 59.9 / 84.5 ms |
| Sampled PSS after 480 x 800 detection | unavailable | 175.4 MiB |
| Detector 736 x 1280 P50 / P95 | unavailable | 189.4 / 275.7 ms |
| Sampled PSS after 736 x 1280 detection | unavailable | 326.0 MiB |
| Sampled PSS after sessions close | 55.7 MiB | 82.5 MiB |

Runtime/environment initialization and model copy are outside the session-creation
timer. Closing sessions does not immediately return all allocator pages to the
OS. PSS values are process snapshots, not measured memory peaks or added IME
memory. Recognition and detection sessions are sequential, not simultaneously
resident. Native failure does not establish which operator is missing; it means
the tested production runtime is not a drop-in full-page OCR runtime.

All 12 public fixtures produced readable text; 10/12 matched exactly. Two mixed
Chinese/Latin/numeric lines lost spaces. This small synthetic set is not a real
app accuracy benchmark and must not be advertised as an accuracy percentage.

A main-Looper scheduling probe showed P95 2 ms, maximum 3 ms both idle and during
inference. It demonstrates that this background worker did not stall the mostly
idle research main Looper. It does NOT measure key dispatch, candidate latency,
UI frames, concurrent Rime/handwriting, ARM thermal behavior or a low-memory phone.
No claim that typing performance is unaffected follows from this result.

## Package impact

The model/table payload totals 21,428,370 bytes (20.44 MiB). ONNX files are stored
uncompressed by this project. If a user already has optional handwriting data,
the recognizer/table can potentially be shared; that resource is not currently
bundled in either production APK, so it cannot be treated as universally free.
Do not require downloading the unrelated stroke models merely to enable OCR.

Measured native payload comparison from the verified AARs:

| ABI | Existing runtime bytes | Full runtime bytes | Increase |
| --- | ---: | ---: | ---: |
| arm64-v8a | 10,218,832 | 27,520,248 | 16.50 MiB |
| armeabi-v7a | 6,385,164 | 19,584,296 | 12.59 MiB |
| x86_64 | 10,394,776 | 33,304,936 | 21.85 MiB |

For ARM64, naively bundling both models and replacing the runtime adds about
36.94 MiB of native/model payload, excluding code, alignment and compression
differences. This is a payload estimate based on actual files, not a built ARM64
production APK delta. An optional model download avoids model bytes in the base
APK but not runtime expansion, downloaded storage, RAM or recognition latency.
Rebuilding a reduced runtime for the detector may lower this cost; its size and
performance have not been measured and are not assumed to be zero.

## Next design decision

Keep a user-invoked, menu-independent private-copy entry. Evaluate node text for
pages that expose it because it preserves exact characters with no OCR models.
For self-drawn pages, evaluate bounded region capture and local OCR rather than
unconditionally scanning a full screen. This must retain a usable direct entry;
silently disabling copying when a custom menu is encountered is not acceptance.

Before adopting OCR, complete the detector/runtime adaptation, real line/page
segmentation and public-fixture testing on the target ARM phone. Measure key
dispatch/candidate latency with OCR off and active, cold/warm page-to-review time,
peak memory, small text, dark themes, mixed scripts, emoji and layout order.
The current evidence does not pass that adoption gate. The user has requested
evaluation before deciding; neither OCR nor a node-only final design is approved.
System secure-window restrictions still apply to any eventual screenshot path.

## Verification and evidence

Both reduced/full isolated benchmark builds passed Lint and Debug/Release
manifest checks (no permissions, activities, services, providers or receivers).
Both instrumentation runs produced metric JSON; the detector's reduced-runtime
failure is an explicit result, not a successful full-page OCR test.

Commands and preparation are in `tools/model-benchmark/README.md`. Local evidence:

- `.tmp-evidence/ocr-reduced-build.log`, `.tmp-evidence/ocr-full-build.log`
- `.tmp-evidence/ocr-reduced-device.log`, `.tmp-evidence/ocr-full-device.log`
- `build/ocr-evaluation/package-comparison.json`
- `build/ocr-evaluation/reduced-benchmark.apk`, `full-benchmark.apk` (research only)

No production screenshot capability, OCR dependency, model asset or network path
was added. Independent private-copy repairs passed 386 app JVM tests (two live
tests skipped), seven emulator tests, privacyCheck, Lint and requireRime x86_64
Debug/test builds. Physical app acceptance and ARM performance remain unverified.
