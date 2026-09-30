# Offline glide decoder validation

## Scope and integration

The Android-free port is `engine-api/GlideDecoder.kt`. `GlideRequest` owns copied,
unmodifiable point and key lists. The caller normalizes both against the same
actual keyboard bounds, including compact rows, floating position, single-hand
width and the Microsoft double-pinyin semicolon key. Coordinates must be finite
and in `[0, 1]`; key dimensions must be at least 0.001 of their corresponding
axis. A request holds 2--256 ordered points, 1--40 unique keys, at most 30 seconds
and 1--16 requested candidates.

Prepare `EnglishGlideLexicon.loadBundled()` and
`RimeGlideLexicon.read(lunaReader, essayReader)` on a bounded worker, then construct
`DictionaryGlideDecoder(rows, isCancelled)`. Both loaders and the index constructor
accept cancellation and publish no partial index. Readers remain caller-owned.
`decode(request, isCancelled)` is also worker-only and returns an empty list after
cancellation or thread interruption. Each request owns its scratch state; the
shared index is immutable and stores only public dictionary data. There is no
I/O, editor read, personal-data port, runtime network, logging or internal worker.

The five layouts are English QWERTY, full pinyin QWERTY, Microsoft and Ziranma
double pinyin, and pinyin nine-key. English candidates carry whole words.
Chinese candidates carry active-layout codes and public pronunciation hints.
After explicit selection, the app revalidates its editor/session/settings/view
generation and replays the selected code into the existing engine. The decoder
never commits its Chinese hint as text and never replaces Rime conversion.
The app must discard results after any invalidation and handle unavailable data,
worker rejection and load failure without delaying ordinary key input.

## Search and bounds

Distances use median actual key widths/heights. Arc-length resampling removes
pointer-event density and dwell-time bias. Endpoint buckets inspect the closest
three start and end keys, at most 4,096 rows per bucket (36,864 total). A bounded
96-row shortlist uses path and endpoint distance; banded dynamic time warping
then evaluates 32-point paths with an eight-point window. A public frequency
prior resolves otherwise close paths. Cancellation is checked at entry, every
32 inspected rows, each shortlisted path and before publication.

Consecutive identical letters/digits collapse only in the geometric template;
the original distinct input codes remain selectable. Apostrophes in English
words do not require a punctuation-key detour. The index accepts at most 500,000
public rows, with 48-character codes and 96-character hints. Unknown physical
keys reject that candidate. Distant endpoints and poor path scores abstain.

Luna uses Rime's preset vocabulary mechanism: raw Luna rows alone omit many
ordinary phrases, including `ni hao`. The loader therefore preserves explicit
phrase readings, then derives readings for the 24,000 highest-frequency missing
two-to-eight-character essay phrases from public character readings. It retains
up to three polyphone alternatives using Luna's existing pronunciation weights.
Explicit phrase pronunciations take precedence. Both double-pinyin layouts use
the existing `DoublePinyin` mapping; nine-key shares `NineKeyReadings.digitFor`.
Each Chinese layout is capped at 75,000 distinct codes. Sources are bounded to
12,000,000 characters, 1,024-character lines, 100,000 dictionary rows and 500,000
essay rows. Malformed frequency rows and oversized input fail closed.

## Reproducible checks

From the repository root:

```powershell
./gradlew.bat :engine-api:test :engine-dictionary:test :engine-english:test `
  :engine-rime:testDebugUnitTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

`GlideCorpusQualityTest` writes `engine-rime/build/reports/glide-quality.tsv`.
The report contains only fixed public fixture IDs, layout, perturbation mode,
rank, counts and host elapsed time. The production corpus evaluator uses 37
English targets and 15 Chinese readings in each of four layouts, each with
center, deterministic path noise, compact translated/scaled geometry and held
turn variants: 388 cases. Source fixtures live in
`engine-rime/src/test/kotlin/dev/zeroinput/engine/rime/GlideCorpusEvaluation.kt`.
They are test-only and do not ship in the APK.

Backend validation on 2026-09-30 used the cached Kotlin 2.1.21 compiler and
JUnit 4.13.2 directly, with temporary output outside the repository and a
128 MiB JVM heap. All 21 new tests passed. Gradle, Android instrumentation,
privacy checks and packaging are separate integration responsibilities and
are not claimed by this backend-only run.

| Layout | Public codes | Synthetic cases | Top 1 | Top 8 | Host p95 decode |
| --- | ---: | ---: | ---: | ---: | ---: |
| English QWERTY | 124,083 | 148 | 140 | 148 | 4.86 ms |
| Full pinyin QWERTY | 50,686 | 60 | 60 | 60 | 2.37 ms |
| Microsoft double pinyin | 50,723 | 60 | 60 | 60 | 0.97 ms |
| Ziranma double pinyin | 50,723 | 60 | 60 | 60 | 0.90 ms |
| Pinyin nine-key | 40,140 | 60 | 56 | 60 | 3.67 ms |

One host run loaded all 316,355 rows and constructed the index in 1.24 seconds.
Observed Java heap after a developer-only GC request was 48.6 MB, with both source
rows and the searchable index retained; this is an approximate whole-JVM metric,
not an Android PSS measurement or a memory guarantee. The evaluator's GC call is
not present in application code. Shared public labels and unchanged code strings
avoid redundant storage. Android load time, low-memory behavior, cancellation
under lifecycle churn and end-to-end key latency still require device validation.

These are development regression results on constructed trajectories, **not
real-user recognition accuracy**. The fixtures do not establish performance for
natural curved paths, arbitrary out-of-vocabulary words or every device geometry.
Repeated letters and repeated nine-key digits are inherently ambiguous. English
CMUdict rows have no frequency estimate; only the existing project seed supplies
a common-word prior, and the broader corpus includes proper names and uncommon
spellings. Chinese polyphone expansion is bounded and lacks sentence context;
less common readings and rare phrases may be absent. Endpoint-bucket bounds can
exclude rare entries. The explicit candidate choice and ordinary tap-input path
remain necessary.

## Source and licensing record for notice integration

New data: Carnegie Mellon University CMUdict, BSD-style redistribution license.
Only spellings are derived; pronunciations and pronunciation-variant markers
are removed, ASCII spellings of up to 32 characters are deduplicated and sorted.
The project-authored English seed remains Apache-2.0 and supplies frequency
priors independently. No third-party executable or runtime dependency is added.

```text
Repository: https://github.com/cmusphinx/cmudict
Revision: 74790861f652b15e4ac49015a90074ad62a27690
Source: https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/cmudict.dict
Source SHA-256: 81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22
License: https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/LICENSE
License SHA-256: bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a
Derived resource: engine-english/src/main/resources/glide-english.txt
Derived bytes: 1,050,045
Derived source spellings: 124,082
Derived SHA-256: 44b7f9daf8d99d18eeca42523c3922a140562a6e7ef0a695530942ddf8818948
Bundled license: engine-english/src/main/resources/glide-cmudict-LICENSE.txt
```

Recreate the derived file from an explicitly fetched local pinned source with:

```powershell
python engine-english/tools/prepare-glide-lexicon.py <path-to-cmudict.dict>
```

The preparation script verifies the source hash and never downloads. The runtime
loader bounds the file and verifies its derived SHA-256 before indexing.

Chinese data is unchanged and continues under the repository's existing
LGPL-3.0 source/data notices. No second copy of the source assets is generated:

```text
rime-luna-pinyin: existing pinned revision 46acf031
luna_pinyin.dict.yaml SHA-256: 270b0c6879436d7b0b606aa684507ea7e173c62b6d5df3966804637872d520fd
rime-essay: existing pinned revision 0766c929
essay.txt SHA-256: 196d508a9bb12fc6d711eed83f5ddc4d5c2d382d92d15b4e51175e4d3802cfab
```
