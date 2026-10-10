# Small Chinese model evaluation

Date: 2026-09-08. Preferred candidate: **UER RoBERTa-Mini WWM, dynamic INT8**.
Status: evaluated on the user-selected Android 16 emulator and approved by the
user for default-off experimental IME integration. The original quality gate
failed; the user explicitly accepted the measured regression rate.

The user requested Mini in preference to Tiny and explicitly confirmed the
[UER project's Apache-2.0 license](https://github.com/dbiir/UER-py) as the model
licensing basis. This is recorded as the project licensing decision; the separate
Hugging Face model cards themselves contain no license metadata.

## Pinned source and converted artifacts

The selected source is
[uer/roberta-mini-wwm-chinese-cluecorpussmall](https://huggingface.co/uer/roberta-mini-wwm-chinese-cluecorpussmall/tree/5e567169018f5cf84cf1d85a2b391c8d51211eda):
four BERT layers, hidden size 256, four attention heads and 21,128 vocabulary
entries. The original FP32 checkpoint is 35,184,327 bytes, SHA-256
`47727c489bfa5c57b53149c83b85a35655b5e49f7355202e272ec50c9c4467d6`.

The prior
[Tiny WWM reference](https://huggingface.co/uer/roberta-tiny-wwm-chinese-cluecorpussmall/tree/6a4e70f3f8bcc2a64ce655e92ceeebb10ad1cafa)
has two layers, hidden size 128 and two heads. Its original checkpoint is
12,841,159 bytes, SHA-256
`35f53ca6d452c8d5be3f7886817cdbe898256959086f756bec666200bc72d4de`.

| Evaluated graph | Graph bytes | Vocabulary bytes | Combined decimal MB |
| --- | ---: | ---: | ---: |
| Mini, FP16 weight storage / FP32 arithmetic | 17,613,515 | 109,540 | 17.72 |
| Mini, dynamic INT8 MatMul/Gather | 14,898,764 | 109,540 | 15.01 |
| Tiny, FP16 weight storage / FP32 arithmetic | 6,432,085 | 109,540 | 6.54 |

The preferred INT8 graph SHA-256 is
`5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446`.
All graphs project vocabulary logits only at masked candidate positions.
FP16 preserves tied weights; INT8 needs separate embedding/decoder layouts because
quantized MatMul may transpose weights that Gather also uses. Quantization is
performed on the host, never in the input method.

These models score existing candidate words with preceding context. They do not
decode pinyin or generate arbitrary phrases. Generated binaries remain in ignored
build directories. The IME stages only the pinned Mini INT8 graph and vocabulary;
`model-scoring` provides its offline runtime. Tiny and FP16 stay research-only.

## Android 16 measurements

AVD `ZeroInputModelApi36`, API 36 / Android 16, Google APIs x86_64 image, two virtual
CPUs and 2,048 MB configured RAM. The standalone benchmark uses ONNX Runtime
Android 1.26.0, sequential execution and one intra/inter-op thread, on its
instrumentation worker. No real editor, personal dictionary or clipboard is used.

The normal workload is **16 context characters and eight two-character candidates**,
with 100 timed samples after warm-up. Each character is masked separately; all
16 masked rows are scored in one batch.

| Model | First session load ms | Eight-candidate p50 / p95 ms | Complete process PSS MiB |
| --- | ---: | ---: | ---: |
| Mini FP16 storage | 159.33 | 18.07 / 19.26 | 151.6 |
| Mini INT8 | 96.19 | 6.74 / 7.87 | 96.4 |
| Tiny FP16 storage | 90.42 | 3.36 / 4.74 | 93.3 |

Session load is one observation in a fresh process after the model file has been
copied and verified, not a physical cold-storage benchmark. Timing includes Java
tensor creation, native execution and output copying; it excludes IME dispatch,
candidate rendering and touch-to-photon latency. PSS includes the full runtime,
fixture checks and allocator residency, not just weights or an IME memory delta.

A stress workload with **24 context characters and eight eight-character phrases**
uses 64 masked rows. Its 50-sample p95 is 122.39 ms for Mini FP16, 49.70 ms for
Mini INT8, and 21.72 ms for Tiny FP16. Mini INT8 reaches 113.7 MiB process PSS in
that loop. Long-phrase scoring is therefore outside the proposed product workload:
it must keep the original Rime order. A timeout alone cannot eliminate the CPU
cost of a request already running.

The verified upstream AAR input is 43,596,581 bytes. Its uncompressed native
runtime plus JNI is 33,304,936 bytes for x86_64, 27,520,248 for arm64-v8a and
19,584,296 for armeabi-v7a. Production builds replace those native libraries
with the reviewed reduced CPU runtime; the Java classes and notices remain from
the upstream AAR. These are runtime sizes, not model sizes or compressed APK
overhead. Only x86_64 execution is measured here.

## Quality against current Rime

The earlier 24 constructed two-choice examples remain a screening set: Tiny
scores 21/24, Mini FP32/FP16 scores 20/24 and Mini INT8 scores 21/24. Their simple
character-frequency prior scores 9/24; it is not the Rime decoder.

A separate capture now obtains the current Rime engine's actual first eight
candidates for 118 public fixtures, with personalization disabled. The source
file has 24 calibration examples and **94 held-out examples with disjoint pinyin
groups**. Source SHA-256 (UTF-8/LF):
`010100daf7acbf9366f12c537288923b501f9400bb7827a61c3ad5a659841e79`.

Only full-reading, two-character candidates are eligible. The native first choice
must itself be eligible. Context is capped at 16 characters. The model promotes
only its strongest candidate and preserves every other candidate's relative order.
A minimum mean-log-probability margin over the runner-up is selected using only
calibration data, maximizing correctness with zero regressions and preferring
the larger margin when tied. **The resulting margin is 1.25 and is frozen before
evaluating the held-out split.**

| Measurement | Calibration | Held-out |
| --- | ---: | ---: |
| Cases | 24 | 94 |
| Rime first choice correct | 17 | 36 |
| Mini INT8 policy first choice correct | 18 | 47 |
| First-choice gain, percentage points | +4.17 | +11.70 |
| Originally correct first choices changed incorrectly | 0 | 1 |
| Clean regression, percentage points of all cases | 0 | 1.06 |
| Expected word present in first eight candidates | 24 | 90 |

The regression is unambiguous: with context `小说塑造了鲜明的` and pinyin `renwu`,
Rime's first choice `人物` is changed to `任务`. Missing expected candidates remain
failures in the denominator. The fixed gate requires at least +2 points gain and
at most 0.5 point clean regression. **The quality gate fails.** Increasing the
margin after inspecting these results and retesting the same split would not be
an independent validation.

These are constructed homophone contexts, not a population typing corpus. Their
first-choice rates must not be generalized to normal input or competitor parity.
Pretraining overlap cannot be excluded; long compositions, user-candidate priority,
ongoing typing, all privacy transitions and live asynchronous reordering are not
validated by this scorer benchmark.

## Reproduction and checks

See [benchmark instructions](../tools/model-benchmark/README.md) for exact build,
capture and execution commands. The host tools are:

- `tools/evaluate-small-model.py`: pinned download verification, Tiny/Mini reference
  scoring and optional FP16 storage comparison. Mini is the default.
- `tools/export-model-benchmark.py`: ONNX export, numeric comparison and optional
  INT8 quantization. The Tiny model-card example validates the reference; it must
  not be treated as a published Mini output.
- `tools/evaluate-rime-model.py`: public-capture validation, calibration-only
  threshold selection, frozen-policy output and held-out reporting.
- `tools/model-benchmark`: isolated Android application, absent from root Gradle
  settings. Debug/Release manifest verification and Lint pass; numeric outputs
  for all three variants agree with the host graph within the test tolerance.

INT8 differs from the FP32 reference by up to 1.05 in individual masked log
probabilities across the screening/stress fixtures. Quantization changes one of
the 24 pair decisions, correcting it, but this does not establish general
quantization fidelity or IME accuracy. No model was fine-tuned on these fixtures.

Results are under `build/model-evaluation/`, including `rime-quality-result.json`,
`rime-ranking-policy.json` and `*-android16.txt`. The benchmark APK is under
`build/model-benchmark/outputs/apk/debug/`; it is not an input-method test release.

## Conditional integration decision

Mini INT8 meets the 20 MB weight/vocabulary budget and the 10 ms emulator p95
budget for the bounded short-word workload. It does not meet the original quality
gate, and it cannot score long phrases within the latency limit. The user has
explicitly accepted the measured regression rate for a separately enabled
experiment. The original results and frozen threshold are preserved.
See [the concrete integration plan](mini-model-integration-plan.md) and
[production integration](model-integration.md).
See [integration validation](model-integration-validation.md) for the shipped
experimental implementation and its separate production measurements.

Earlier screening excluded UER Tiny Word (53,538,951-byte checkpoint plus
1,991,738-byte tokenizer) and Conan-embedding-v1 (0.3B parameters, CC-BY-NC-4.0)
because they do not fit the selected weight budget in their supplied form.
