# Experimental RoBERTa-Mini integration

The separately controlled short-word ranking switch is off by default. Enabling
it uses the evaluated Mini INT8 graph to score existing Chinese candidates, fully
offline. It does not generate text, decode pinyin, or replace Rime.

## Behavior

- At most 16 contiguous Chinese characters successfully committed by this IME in
  the current editor session are retained in a wipeable buffer. No editor-text
  query, clipboard, phrase-history read or file is used to obtain model context.
- Score eligible two-character words within the first eight visible candidates;
  the first candidate must be eligible and at least two must qualify. Reading
  metadata must match the full lowercase pinyin. Unsupported tokens fail closed.
- Promote only the winner whose mean masked log-probability leads the runner-up
  by at least 1.25. Preserve all other relative order, IDs and native routes.
- Personal priorities, related readings, partial selections, later pages, nine
  keys, explicit browsing and ongoing touches preserve original ordering.
- Touching candidates freezes asynchronous changes until typing resumes. Tap,
  Space and Enter select the displayed word through its original engine route.
- Password/PIN/unknown/email/URI fields, no-personalization flags, incognito
  and learning-disabled sessions cannot supply model context.
- Cursor changes, deletion, reconversion, panel/view/session/settings changes and
  service destruction cancel requests and clear context. Punctuation starts a new
  contiguous Chinese context. Private snippets and emoji never enter the buffer.

The model is lazy: disabled input creates no model session or executor. Enabled
input keeps at most one CPU worker and one replaceable pending request. Stale
requests and results are discarded; an 80 ms total deadline includes waiting and
cold load, so the first cold request may preserve the baseline. Already running
native inference may finish before cancellation is observed; its workload is
bounded to 16 masked rows of at most 20 tokens. Original candidates remain usable
throughout initialization and inference. There is no synchronous main-thread wait.
Disabling or ending the view closes the session on its owner worker; idle worker
threads expire. Requests and token arrays are wiped and native tensors closed.

This is an experiment with a known quality tradeoff: the frozen 94-case held-out
set improved from 36 to 47 correct first choices, with one previously correct
first choice made incorrect. The original regression gate failed; the maintainer
explicitly accepted this default-off experiment. These constructed contexts do not
establish population accuracy or parity with mainstream keyboards.

## Reproduce assets and build

Use a local Python environment with PyTorch 2.12.0+cpu, ONNX Runtime 1.26.0,
ONNX 1.19.0, ml_dtypes 0.5.3 and NumPy. Developer downloads occur only on the host;
the Android application never downloads a model or code. From the repository root:

```powershell
python -B tools/evaluate-small-model.py --model mini --download
python -B tools/export-model-benchmark.py --model mini --int8
./gradlew.bat :model-scoring:prepareModelAssets :app:assembleDebug -PrequireRime=true --no-parallel
```

The build deliberately fails if pinned assets are absent or differ. The graph is
read from `build/model-evaluation/android-assets/mini-int8/model.onnx`, and the
vocabulary from `build/model-evaluation/mini/vocab.txt`. Only those two files are
staged into `mini-int8/` in the APK. Generated graphs and host dependency caches
remain untracked. Exporter/library changes require an independent numerical and
quality review before changing hashes, not merely an updated checksum.

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| Mini INT8 | 14,898,764 | `5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446` |
| Vocabulary | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |
| ONNX Runtime Android 1.26.0 AAR | 43,596,581 | `09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032` |

Model plus vocabulary is 15,008,304 bytes, separate from runtime/native/APK and
resident-memory costs. Runtime initialization validates both assets, then copies
only the public graph atomically to `noBackupFilesDir/public-model`. Cached graph
corruption triggers replacement from verified APK assets. No input or optimized
model containing input-specific state is stored there.

Run `testDebugUnitTest :engine-api:test :engine-english:test :engine-dictionary:test`,
`privacyCheck`, `:app:lintDebug`, `:app:assembleDebug`, and
`:app:assembleDebugAndroidTest`. Run `ModelIntegrationTest`, `ModelNativeRankingTest`
and `ModelSessionPrivacyTest` on Android 16 along
with the existing engine, privacy, reconversion and keyboard suites. Build all
three supported ABIs with real Rime and inspect Debug/Release manifests and APKs.
See [evaluation](small-model-evaluation.md) for the frozen research measurements
and [ADR 0011](adr/0011-experimental-model-ranking.md) for ownership boundaries.
Production measurements, test results, artifacts and the resolved clipboard
fixture failures are in [integration validation](model-integration-validation.md).
