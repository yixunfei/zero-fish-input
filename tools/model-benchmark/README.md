# Android model benchmark

## OCR research (2026-10-31)

`OcrBenchmarkInstrumentation` is a separate public-fixture experiment. It draws
fixed text locally; it cannot capture a screen, read an editor or access the
clipboard. It compares single-line CTC decoding of the already pinned optional
PP-OCRv5 recognition graph and kernel-only detection at two page resolutions.
It does not implement detection postprocessing, page segmentation, ordering or
a production OCR entry. Queue-delay probes are NOT measured keyboard latency.

```powershell
python tools/prepare-ocr-evaluation.py
./gradlew.bat -p tools/model-benchmark assembleDebug lintDebug verifyBenchmarkPrivacy -PocrResearch=true -PreducedRuntime=true --no-parallel
adb install -r build/model-benchmark/outputs/apk/debug/ZeroInputModelBenchmark-debug.apk
adb shell am instrument -w dev.zeroinput.modelbenchmark/.OcrBenchmarkInstrumentation
```

Omit `-PreducedRuntime=true` to compare the pinned full runtime. The research
asset set replaces the language-model assets only for `-PocrResearch=true`.
The detector is fixed to RapidAI/RapidOCR v3.10.0 with SHA-256 validation;
recognition/dictionary files reuse the existing pinned PaddlePaddle resources.
These locally staged public models are evaluation artifacts, not production
dependency additions or approved redistribution. Results and limitations are
tracked in `docs/clipboard-product-repair-plan.md`.

On this host the global recovery Gradle init script incorrectly injects project
repositories into this nested build. Use `-g K:/zeroInput/build/verification-gradle-home`
for isolated verification; no repository enforcement rule was weakened.

This is a separate developer application, not an IME module. It uses only the
repository's constructed public fixtures, has no permissions or editor APIs,
and is not included in root `settings.gradle.kts`. Model files/results live in
ignored `build/model-evaluation/`. Do not publish this evaluation APK as a keyboard.

## Reproduce from the repository root

The measured host has PyTorch 2.12.0+cpu and ONNX Runtime 1.26.0. Export also needs
ONNX 1.19.0 and ml_dtypes 0.5.3; these can be installed in the ignored tool folder:

```powershell
python -m pip install --target build/model-evaluation/python-deps --no-deps onnx==1.19.0 ml_dtypes==0.5.3
python tools/evaluate-small-model.py --model mini --download --fp16
python tools/evaluate-small-model.py --model tiny --download --fp16
python tools/export-model-benchmark.py --model mini
python tools/export-model-benchmark.py --model tiny
python tools/export-model-benchmark.py --model mini --int8
./gradlew.bat -p tools/model-benchmark assembleDebug lintDebug verifyBenchmarkPrivacy --no-parallel
adb -s emulator-5580 install -r build/model-benchmark/outputs/apk/debug/ZeroInputModelBenchmark-debug.apk
adb -s emulator-5580 shell am instrument -w -e model mini-int8 dev.zeroinput.modelbenchmark/.ModelBenchmarkInstrumentation
```

Set `JAVA_HOME` and `ANDROID_HOME` to the local JDK 17 and Android SDK if they are
not already configured. The device ID is an example; use the Android 16 / API 36
emulator shown by `adb devices`. `-e model mini` and `-e model tiny` run the FP16
storage comparisons in fresh instrumented processes. Native CPU execution stays
FP32 for FP16 graphs; the INT8 graph quantizes eligible MatMul/Gather operators.

The build pins and checks the ONNX Runtime 1.26.0 AAR SHA-256:
`09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032`.
It verifies Debug/Release manifests contain no permissions or application
components and disable backup. The evaluation APK currently selects only x86_64.
All inference runs on the instrumentation worker, not the Android main thread.
Native model sessions, outputs, tensors and session options are closed explicitly.

The normal benchmark uses 16 context characters and eight two-character choices;
the stress case uses 24 context characters and eight eight-character choices.
The latter is outside the proposed product inference workload and retains Rime
ordering if a model is eventually integrated. Timing includes Java tensor
creation, inference and result copying, but excludes live IME dispatch/rendering.
PSS includes the complete benchmark process/runtime, allocator residency and
prior fixture checks. It is not an incremental measurement inside the IME.

## Compare actual Rime candidates

The source file `tools/model-quality-fixtures/cases.json` was fixed before model
evaluation: 24 calibration cases and 94 test cases with disjoint pinyin groups.
The device test captures only these public candidates, without personalization:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" --no-parallel
adb -s emulator-5580 install -r -t app/build/intermediates/apk/debug/app-x86_64-debug.apk
adb -s emulator-5580 install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -e class dev.zeroinput.ime.ModelCandidateFixtureTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 pull /sdcard/Android/data/dev.zeroinput.ime.debug/files/model-research/rime-candidates.json build/model-evaluation/rime-candidates.json
python tools/evaluate-rime-model.py
```

The scorer promotes only one full-reading, two-character candidate if its mean
masked log-probability exceeds the runner-up by the calibrated margin. Others
keep their native relative order. Unsupported tokens/readings retain the native
first choice. Missing expected candidates remain failures in the denominator.
The test partition must not be reused to tune a threshold and then claimed as an
independent test. The current quality gate fails; see the linked project report.

Model and runtime source/license decisions, measured results and limitations are
in [the evaluation report](../../docs/small-model-evaluation.md). The benchmark
contains generated public model assets for local research only; it is not a
production model distribution or a complete candidate-reranking feature.
