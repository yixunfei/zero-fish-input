# Project status

Updated 2026-11-23.

## Comprehensive review follow-up (2026-11-23)

The [review follow-up](audits/2026-11-23-comprehensive-code-review.md) records 19
confirmed findings and their fixes across input, Rime, private data and AI.
Foreground task recreation restores generic interruption feedback. Old task
content, approvals and picker grants are revoked. Authenticated clipboard
management can still remove an unreadable independent private draft.

The x86_64 Debug and instrumentation APKs built with real Rime. Debug unit
tests, privacyCheck, Debug lint and Debug/Release merged-manifest checks passed.
The API 37 emulator passed 48 selected instrumentation cases, including long
Responses output, interrupted Rime deployment, draft limits and accessibility
selection. Physical-device and live-provider validation remain open.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest privacyCheck :app:lintDebug :app:processReleaseMainManifest -PrequireRime=true "-Pandroid.injected.build.abi=x86_64"
```

## v0.7.8 testing prerelease

The universal Debug-signed distribution includes the window punctuation and distributed carrier changes. The release APK passed an in-place upgrade from the published v0.7.7 on API 37. See the [release notes](releases/v0.7.8.md) and [validation and hashes](releases/v0.7.8-validation.md).

## Window observation punctuation (2026-11-22)

The Debug window prose profile now groups observations with Chinese commas and
closes sentences with full stops. Required follow-ups stay attached. Each mark
occupies the original delimiter width, preserving the 833-byte envelope limit.
The profile fingerprint changed; archived all-stop messages require their old
APK. The Python exporter and Kotlin codec agree on the boundary vectors.
Real-Rime x86_64 Debug assembly, unit tests, privacyCheck and Debug lint passed.
Run `./gradlew.bat :app:testDebugUnitTest --tests "*WindowObservationProfileTest"`
for canonical punctuation, tamper rejection and the public clipboard fixture.

## Distributed carriers, settings and import audit (2026-11-22)

The two exact-cover transports write and read the [distributed format](adr/0042-distributed-exact-carriers.md).
Old terminal complete carriers require an older app. Detached ciphertext and
the three Debug wording profiles retain their exchange formats. The settings
sample button was retired, and the send/receive carrier choosers include
usage help. Damaged carrier markers block AI import. Stego dialogs filter
obscured touches and discard a failed window-token presentation.

Real-Rime x86_64 Debug, unit tests, privacyCheck, Debug lint and merged
manifest checks passed. Android API 37 instrumentation passed 10 cases for
the real keyboard, import host, carrier help, settings and session changes.
Two of these cases also passed in the 800x1600 landscape/night configuration.
Physical ARM64 transfer through third-party messaging apps remains unmeasured.

The new [full ARM64 Debug APK](../app/build/outputs/test-apk/20261122-223054/zero-fish-input-0.7.7-debug-arm64-v8a.apk)
is 97,777,830 bytes. The package checks and all three distribution hashes passed.
The full unsigned ARM64 Release passes resource verification. See the
[package size measurements](apk-size-optimization.md#steganography-review-2026-11-22).
Reproduce with the Gradle commands in AGENTS.md and
`python tools/resources/verify-apk.py full <apk> --abis arm64-v8a`.

## ARM64 Debug package and archive cleanup (2026-11-22)

[0.7.7 Debug APK](../app/build/outputs/test-apk/20261122-215247/zero-fish-input-0.7.7-debug-arm64-v8a.apk) · [Distribution checksums](../app/build/outputs/test-apk/20261122-215247/SHA256SUMS.txt).
The full package is 97,773,670 bytes. The real-Rime build, unit tests,
privacyCheck, Debug lint, signature, ABI, permissions and resource checks
passed. All three delivery hashes were verified. Physical ARM64 installation
remains untested for this build. Rebuild with
`./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000`.

Packaging refreshed the Debug APK staging directories. Five superseded
deliveries were pruned of their APK and two ZIPs (15 files, 706,928,734 bytes).
Their metadata and checksum manifests remain. Historical artifact links for
20261121-000415, 20261121-110558, 20261121-133504, 20261121-135354 and
20261122-004322 now refer to retired files. Retained successors with matching
wording resource sets are 20261121-101521, 20261121-121543,
20261121-140758 (for both middle builds) and 20261122-103554 respectively.

## Natural wording Debug trial closed (2026-11-22)

Five-character verse, seven-character verse and window observations are
maintained in the protected Debug keyboard. Both directions use the same style
labels. Starting decryption on unmarked text opens the explicit style picker;
choosing the wrong style preserves the carrier for correction. Preview reports
the original and complete UTF-8 byte counts and their measured ratio. The 20%
benchmark remains in research and in the separately named public five-character
verse sample. The existing profile digests, encodings and wrapped envelope
remain the basis of exchange. Release is still subject to the existing gates.

Baichuan2 + Discop + Basic (top-k 256) is deferred. The estimated saving over
the archived Daily sample is about 22%. Model cost, whole-text naturalness and
cross-device recovery need evidence before reconsideration.
[Candidate evidence and decision](natural-wording-steganography.md#baichuan2-basic) ·
[Keyboard workflow](themed-verse/android-test-build.md) · [ADR 0041](adr/0041-natural-wording-trial.md)

Validation used JDK 21 and the real Rime x86_64 Debug build. The Debug unit
suite passed (1,143 passed, 2 skipped), along with privacyCheck, Debug lint,
Release Kotlin compilation, merged assets and manifest checks. The Debug APK
contained ten wording assets and a private public-sample Activity; the Release
merged outputs excluded both. On API 37, six targeted tests passed in English,
including all three actual IME round trips, the eight-Han budget, and two
public-sample page tests. Simplified Chinese dark mode passed for five-character
verse and window observations at 480×854, 200 dpi and 1.3 font scale, and for
seven-character verse in landscape. The IME test now resolves expected labels
from the keyboard's resources, including when app and system languages differ.
Physical ARM64 validation and independent full-text reading/detection reviews
remain open. [Reproduction commands](themed-verse/android-test-build.md#当前回归)

## Eight-Han passphrase messages in Android prose (2026-11-22)

One independently chosen 24 B UTF-8 fixture was encrypted with a fresh test
passphrase and the standard KDF. Each entry below measures one complete
generated carrier, including punctuation and line breaks. Each was opened
through the APK's own wording receiver and matched the original. The emulator
ran API 37. The current x86_64 build and the archived 20261122-103554 ARM64
build (under translation) both gave the window results.

| Debug prose APK | Single AES-256-GCM | AES-256-GCM + XChaCha20-Poly1305 |
| --- | ---: | ---: |
| Daily, 20261121-140758 | 2,927 B (0.82%) | 3,763 B (0.64%) |
| Daily, 20261121-150811 | 2,201 B (1.09%) | 3,015 B (0.80%) |
| Current window | 3,825 B (0.63%) | 5,271 B (0.46%) |

The current single and double envelopes measured 194 and 268 B. Both are
already in their 64 B minimum inner frame. The 20% goal would allow just
120 B of complete prose. The pinned window grammar has 6,420,585,568,352
complete, nonempty texts up to that length, enough for every five-byte
framed value. The number of variants and actual final bytes constrain short
messages even after removing most of the cryptographic overhead.

A host candidate adds concise clauses only to existing transitions, dropping
ambiguous destinations and matches to normalized source sentences. Its
median complete carriers were 3,687 B (0.65%) and 5,082 B (0.47%) for the same
two envelope sizes. All 408 surface round trips passed across six sizes and
two grammars, including all-zero and all-one endpoints. The four smaller
sizes (48, 53, 64 and 69 B) benchmark transport only. With a 69 B input the
candidate needs 1,314–1,317 B. An authenticated compact **passphrase** format
and an independent prose reading review remain open. The Android keyboard
still uses the pinned grammar and wrapped envelope in ADR 0041.

Rebuild the Debug APKs and run the regression on the dedicated test emulator:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-parallel -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" "-Pandroid.injected.testOnly=false"
# After installing both APKs on the selected test emulator:
adb -s $serial shell am instrument -w -r -e class dev.zeroinput.ime.textstego.ShortProsePlatformTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
# For the two archived Daily APKs above, add "-e archivedDaily true" before the runner name.
$py = './build/model-evaluation/prose-python314/python.exe'
$run = "import runpy,sys; sys.path.insert(0,'tools/themed-verse'); runpy.run_path(sys.argv.pop(1),run_name='__main__')"
& $py -X utf8 -B -c $run tools/themed-verse/short_prose_probe.py build/model-evaluation/corpus-coverage-sources/cmn_sentences_detailed.tsv.bz2
```

## Lossless narrative body compression on the host (2026-11-22)

Five-character sparse same-scene verse now meets the 20% target on 38/40
locked calibration bodies, up from 17/40. Work records improved from 16/20
to 20/20 and free narratives from 1/20 to 18/20. This experiment changed the
body representation. Both runs used identical verse choices, punctuation,
hard limits, two encryption layers and padding. Each receiver independently
rebuilt its public resources and authenticated the complete carrier.

| Verse route | Exact bodies before → after | At least 20% before → after | Capacity rejects after |
| --- | ---: | ---: | ---: |
| Five, free | 40 → 40 | 28 → 40 | 0 |
| Five, sparse same scene | 40 → 40 | 17 → 38 | 0 |
| Five, sparse source couplet | 40 → 40 | 9 → 22 | 0 |
| Seven, free | 38 → 40 | 1 → 7 | 0 |
| Seven, sparse same scene | 28 → 40 | 0 → 0 | 0 |
| Seven, sparse source couplet | 20 → 38 | 0 → 0 | 2 |

The new candidates use the existing Rime Essay frequency file and frozen
ZWC1 model. A dictionary leaf can supply its original or simplified spelling
according to the selected mode. The encoder matches the exact input bytes.
Literals and references to earlier bytes in the same message handle other
text and repetitions. A new mode wins only when the padded envelope shrinks.
39/40 envelopes shrank, one tied, and their total fell from 44,256 to
37,792 B. The host decoded both candidate streams before selection when they
fit. The unchanged modes remain eligible when they win or tie.
[Codec and profile specification](themed-verse/word-context-probe.md#rime-essay-host-body)

| Calibration genre | Original envelope total | With lexical tables only¹ | With same-message copies | Guaranteed five-character same-scene passes at each step |
| --- | ---: | ---: | ---: | --- |
| Work records | 18,736 B | 17,264 B | 16,688 B | 16 → 20 → 20 |
| Free narratives | 25,520 B | 21,744 B | 21,104 B | 1 → 16 → 18 |

¹ The middle column charges the exact token costs for both spellings in the
same tree with copy choices disabled and includes the original body modes.
Its passes use the per-sample worst-case carrier budget. The first and last
arms also ran the full carrier exchange. In the new five-character same-scene
arm, `pg-52238` needs 46 more stream bytes saved and `pg-52267` needs 9 to
guarantee the target. Seven-character sparse same-scene still needs a median
183.5 B saved across the 40 misses. The 60 holdout bodies remain sealed.

All 238 emitted carriers recovered exact originals, with zero authentication
or byte mismatches. An independent Java implementation rebuilt the frequency
tree from the public sources and decoded 232 new-mode packets. The compiled
Android Debug Kotlin codec checked the other six packets and reproduced their
encoding. Additional JVM tests include mixed scripts, combining characters,
CRLF, 1/2,045/2,046/2,048-byte bodies, overlap, invalid references, truncation,
UTF-8 and padding (14 valid and 18 rejected Essay controls). Existing public
vectors, binary prototype, sparse boundary/mixed-batch tests, Debug wording
unit tests and `privacyCheck` passed.

The new modes are host research. The reader packet contains 38 qualifying
whole carriers and 38 ordinary controls matching each carrier's exact length,
layout and scheduled scene groups. It awaits independent scores. The packet
has no length-matched historical human-written control. Further evaluation
needs whole-carrier reading scores and mobile model memory/load measurements
before selecting a new Android profile. Sparse and dense seven-character
capacity and whole-carrier naturalness are separate open issues.

From the repository root, use the prepared assets and build prerequisites in
the preceding sparse study below. The source Rime Essay table is already
pinned by `tools/bootstrap-rime.ps1`.

```powershell
$py = './build/model-evaluation/prose-python314/python.exe'
$toolsDir = 'tools/themed-verse'
$cal = '.tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final'
$tang = 'build/model-evaluation/corpus-coverage-sources/tang-20000.json'
$out = Join-Path '.tmp-evidence' ('essay-verse-' + [guid]::NewGuid())
& $py -X utf8 -B "$toolsDir/scene_roundtrip.py" run --essay-body --sparse-source $tang $cal $out
& $py -X utf8 -B "$toolsDir/check_scene_kotlin.py" --essay-body --sparse-source $tang $cal $out
& $py -X utf8 -B "$toolsDir/p2_theme_reading.py" $out "$out/blind" --sparse-source $tang
```

The recorded baseline is `.tmp-evidence/essay-body-baseline-20261122`.
The new run is `.tmp-evidence/essay-lexical-final-20261122`. Its
`report.json` records both spellings, copy counts, padded sizes and per-sample
shortfalls. `jvm-body.json` records independent decoding.
`blind/readers/scores.md` opens the anonymous full texts.

## Sparse verse capacity and independent byte recovery (2026-11-22)

On the same 40 public P2 calibration bodies, five-character same-scene verse
recovered 40/40 after limiting the shared scene to the first two lines of
each eight. The earlier four-line scene rule recovered 20/40. Seven-character
same-scene recovery rose from 5/40 to 28/40. The six remaining lines in each
block use the full locked five- or seven-character Wanxiang table. A second
arm requires a source-ordered Tang couplet in those two positions. A free-line
control uses the same rank and layout. All arms use the same frozen body
selector, research AEAD and hard limits as the preceding host study.
[Sparse verse codec](../tools/themed-verse/sparse_verse.py) · [Source and attribution](../SOURCES.md)

| Route | Guaranteed envelope (B) | Exact bodies / 40 | Exact bytes / 78,622 attempted | Capacity rejects | 20% passes (work / narrative, each of 20) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Five, free control | 1,984 | 40 | 78,622 | 0 | 20 / 8 |
| Five, sparse same scene | 1,793 | 40 | 78,622 | 0 | 16 / 1 |
| Five, sparse source couplet | 1,652 | 40 | 78,622 | 0 | 9 / 0 |
| Seven, free control | 1,391 | 38 | 74,527 | 2 | 1 / 0 |
| Seven, sparse same scene | 1,267 | 28 | 54,058 | 12 | 0 / 0 |
| Seven, sparse source couplet | 1,152 | 20 | 37,678 | 20 | 0 / 0 |

The receiver rebuilt its tables independently. All 206 emitted carriers
authenticated and recovered byte-for-byte. The other 34 exceeded the carrier
budget. Of the 206, 55 met `V <= 5*S` with all punctuation and newlines
counted. None fit the ordinary 512-unit / 1,536-byte draft. The report gives
each sample's guaranteed envelope budget at `5*S`, the largest compressed
stream fitting that budget with both encryption layers, and the stream bytes
still needed to save. Among misses, the median saving needed is 157 B for
five-character sparse same-scene verse and 225 B for its source-couplet arm.
The 3,691 five-character and 1,412 seven-character ordered pairs retain
source work ID, author, title and paragraph in the evidence index.

The compiled Android Debug Kotlin body codec independently decoded and
re-encoded all 206 authenticated body packets and all 102 in the repeated
baseline. Original bytes and selected packets matched Python in both
directions. Four extra controls per run exercised 1, 2,045, 2,046 and 2,048
original bytes. The Kotlin verse decoder also extracted the identical envelope
from all 68 baseline carriers using the existing frozen five/seven surfaces.
The sparse surface rank and research AEAD remain host-only. The malformed-file
regression reproduced a batch abort on invalid UTF-8 before the fix. Invalid
UTF-8, oversized or truncated carriers now reject individually with no result
file, and a later valid carrier still recovers.
[Cross-language check](../tools/themed-verse/check_scene_kotlin.py) · [Boundary checks](../tools/themed-verse/check_scene_receive.py)

Run from the repository root with JDK 21 and the prepared P2, Wanxiang,
OpenCC and model assets. For first-time source preparation, save the
[pinned Tang JSON](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%85%A8%E5%94%90%E8%AF%97/poet.tang.20000.json)
at the path assigned to `$tang`. Both processes verify its SHA-256.

```powershell
$py = './build/model-evaluation/prose-python314/python.exe'
$probe = 'tools/themed-verse'
$cal = '.tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final'
$tang = 'build/model-evaluation/corpus-coverage-sources/tang-20000.json'
$out = Join-Path '.tmp-evidence' ('sparse-' + [guid]::NewGuid())
$base = Join-Path '.tmp-evidence' ('baseline-' + [guid]::NewGuid())
cargo build --locked --offline --manifest-path tools/stego-vectors/Cargo.toml --bin scene-envelope
./gradlew.bat :ime-core:bundleLibRuntimeToJarDebug :app:syncVerseTestAssets --offline --no-parallel -PrequireRime=true
& $py -X utf8 -B "$probe/check_scene_receive.py" $tang
& $py -X utf8 -B "$probe/scene_roundtrip.py" run --sparse-source $tang $cal $out
& $py -X utf8 -B "$probe/check_scene_kotlin.py" --sparse-source $tang $cal $out
& $py -X utf8 -B "$probe/scene_roundtrip.py" run $cal $base
& $py -X utf8 -B "$probe/check_scene_kotlin.py" $cal $base
```

Reports are in `.tmp-evidence/sparse-capacity-budget-20261122` and
`.tmp-evidence/scene-baseline-sparse-control-20261122`. Nine public vectors,
the binary prototype regression, seven Debug wording unit tests and
`privacyCheck` passed. Whole-carrier reader scoring, the sealed 60-body
holdout and physical Android exchange remain open. The next capacity task
is to compare lossless body encodings against the per-sample shortfall,
especially on narrative text. Release activation still requires the
independent evaluations in the implementation plan.

## Authenticated host scene round trips (2026-11-22)

The prior scene comparison replayed synthetic envelopes sized from the 40
locked P2 calibration bodies. This task sends the actual public bodies through
the frozen word model, a two-layer authenticated research envelope, and the
same six five-/seven-character verse routes. Sender and receiver run in
separate processes. The receiver reloads the locked public dictionaries and
word-model asset, reconstructs each profile, and reads only the generated
carriers and public fixture key; it does not load the corpus or sender choices.
The parent compares recovered files against the locked original bytes.
[Host runner](../tools/themed-verse/scene_roundtrip.py) ·
[Frozen-model reader](../tools/themed-verse/scene_word_model.py)

| Verse route | Exact bodies / attempts | Exact bytes / attempted bytes | At least 20% | Carrier capacity | Recovered but below 20% |
| --- | ---: | ---: | ---: | ---: | ---: |
| Five-character frozen | 40/40 | 78,622/78,622 (100.00%) | 17/40 | 0 | 23 |
| Five-character same scene | 20/40 | 37,678/78,622 (47.92%) | 0/40 | 20 | 20 |
| Five-character same image | 9/40 | 15,651/78,622 (19.91%) | 0/40 | 31 | 9 |
| Seven-character frozen | 28/40 | 54,058/78,622 (68.76%) | 0/40 | 12 | 28 |
| Seven-character same scene | 5/40 | 7,928/78,622 (10.08%) | 0/40 | 35 | 5 |
| Seven-character same image | 0/40 | 0/78,622 (0.00%) | 0/40 | 40 | 0 |

The byte numerator counts whole samples recovered exactly; capacity rejections
remain in the denominator. All 102 produced carriers recovered exactly: there
were no receiver rejections or byte mismatches. The 138 other attempts could
not fit the configured carrier. Of the 102 recovered, 85 exceeded `5*S` and
17 passed, using the complete carrier UTF-8 length. In the frozen five-character
route the passes were 16/20 work records and 1/20 free narratives. All reported
carriers met both the 8,192 UTF-16-unit and 16,384-byte limits. Fresh randomness
can change an individual carrier's length; counts are the observed run.

The reloaded word model matched the original training-derived Huffman and both
rANS streams for each sample. Modes 5/6/7 were selected 15/19/6 times, and the
two-layer envelopes totalled 44,256 B, matching the earlier size calculation.
The public fixture uses fresh per-layer AES-256-GCM and XChaCha20-Poly1305 keys,
the same padded-size budget, and a **host-only** authenticated profile domain and
version; this is not a new Android or release format. The receiver separately
checked mixed Unicode and CRLF, truncated and trailing bodies, wrong lengths and
modes, missing/changed models, wrong keys or profile digests, and edited carrier,
header, metadata, body and outer tag. These controls include the seven-character
same-image route even though no long calibration body fit. Failure cases did not
publish a recovered file.

Corpus lock SHA-256: `e2c4aeaa4daf6f17e2fa2d1a6871f9183cc58a008a34fd9ea50edc1c94a17652`.
Word-model SHA-256: `be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f`.
The dictionary source lock's LF SHA-256 is
`0f04754f84bedce500e965c3f895a444de13136a4d4e3bea68b81f12a2372767`.
Both processes checked it, verified all 16 prepared tables and recomputed all
six profile digests against the values pinned in the host runner. The 60
held-out bodies were not opened.

From the repository root, with the already prepared locked public dictionaries,
P2 calibration archive and local Python 3.14 runtime with NumPy/ONNX Runtime:

```powershell
cargo build --locked --offline --manifest-path tools/stego-vectors/Cargo.toml --bin scene-envelope
$trial = Join-Path '.tmp-evidence' ('scene-real-host-' + [guid]::NewGuid().ToString('N'))
./build/model-evaluation/prose-python314/python.exe -X utf8 -B tools/themed-verse/scene_roundtrip.py run .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final $trial
```

The recorded run is
`.tmp-evidence/scene-real-host-bdbc2594fee6466f9a50da6b0afed1bf/report.json`;
it contains per-sample sizes, outcomes and profile digests. Its decoded files
contain only public fixtures. These are independently reloaded instances of the
same host implementation, not an independent-language or Android interop test.
Naturalness, reader scoring, physical devices and held-out acceptance remain
open. The next research step remains attributed ordered couplets and sparse
scene constraints, followed by independent whole-carrier review.

The existing nine public surface vectors and binary-prototype checks also
passed; the latter reads current working-tree documents and is not the frozen
40-body result above. JDK 21 `privacyCheck` passed for Debug and Release. The
binary-prototype helper was rebuilt offline from the existing
`.tmp-evidence/rmw-cargo` cache before its check.

```powershell
python -X utf8 -B tools/themed-verse/check_vectors.py
python -X utf8 -B tools/themed-verse/check_binary_prototype.py
./gradlew.bat privacyCheck --offline --no-parallel -PrequireRime=true
```

## Natural wording maintenance audit (2026-11-22)

Archived the three superseded window binaries under
`build/model-evaluation/window-observation-history` after rebuilding
each byte for byte. The generators remain available for reproducing
earlier evidence. The current v4 graph and word model retain their
hashes. The standalone public-fixture workbench still provides the
single-layer interop regression.

Debug packaging stages verified public assets and synchronizes an
explicit list. A simulated obsolete file in both staging and the
packaging directory was absent from the APK, which contained exactly
ten wording assets. The Release merged assets and manifest exclude
the Debug fixtures.

The wrapped five- and seven-character surfaces now observe the
worker lease during ranking, parsing and canonical readback.
Cancelled readback clears the recovered envelope. Queued deliveries
are rechecked at handover and any stale plaintext is wiped. The
wording worker fails closed if the selected profile's asset source
is unavailable. Host probes now fail when a guaranteed sample is
rejected or exceeds its bound. Invalid stanza tests mutate a valid
full-length carrier. Prose and verse replay reports were byte-identical
to the prior measurements.

JDK 21 passed the Debug builds, full Debug unit suite (1,143 passed,
2 skipped), privacy checks and Debug lint. Release Kotlin compilation
and asset merging passed. Ten API 37 x86_64 instrumentation tests
passed, covering window and seven-character IME round trips, input
feedback, worker cancellation, the public workbench and the key file.
The maintenance build did not exercise physical ARM64 hardware.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest privacyCheck :app:lintDebug :app:mergeReleaseAssets :app:compileReleaseKotlin --offline --no-parallel -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" "-Pandroid.injected.testOnly=false"
```

## Scene continuity comparison for prose and verse (2026-11-22)

Twelve authored three-sentence window scenes use collocations checked
against the locked Mandarin snapshot and Wanxiang. They add 141
sentences and increase the common-inventory codebook coverage from
440 to 521. The optional paths are nearly always bypassed by the
length-first rank. A required opening scene emits real new terms. At
524 B its 64-carrier vocabulary union rises from 417–422 to 441–450.
The worst carrier grows from 10,302 B to 10,341 B and the guaranteed
envelope decreases from 833 B to 831 B. Each of the frozen, optional,
and required routes recovered all 384 packets, including the holdout.
The frozen and hybrid routes each fit four of the 40 locked bodies and
none reached 20% utilization. The scene-only route fits 178 B but fails
at 524 B. The separate contextual rewrite verified 20 variants and
required exact antecedents. Its best draft carries two bits.
[Prose measurements and language review](natural-wording-steganography.md#21-同一观察点的三句承接)

Five- and seven-character host probes partition the locked verse lines
by a reviewed scene or literal image. A scene persists for four lines.
Both scene pools recruit lines outside the Debug baselines, and all
six comparisons recovered 384/384 packets per route plus boundary
vectors. At 524 B the five- and seven-character scene banks emit
434–463 and 229–244 newly admitted lines per 64 texts. The guarantees
are 1,150 B and 847 B, below the conservative frozen bounds of 1,728 B
and 1,229 B. Full-sentence repeat medians stay at zero, while individual
line repeats increase. Locked body fit with scene banks drops from
40 to 20 in five-character verse and from 28 to 5 in seven-character verse.
The new banks have no 20% passes. Sample stanzas still switch narrative
voices or time periods, even when every line shares a literal image.
[Verse measurements and language review](natural-wording-steganography.md#22-五言和七言的节内承接)

These candidates remain host probes. The existing Android Debug
wording regression and `privacyCheck` passed under JDK 21. Remaining
work is to make short attested links common in the prose rank, and to
compare sparse verse constraints with attributed, ordered couplets.
Both routes still need independent language review before app use.

From the repository root, use the prepared local Python 3.14 runtime
with NumPy and ONNX Runtime, the existing Mini/OpenCC/Wanxiang assets,
and the locked data paths below. The runner adds the probe directory to
the embedded runtime's module path.

```powershell
$py = './build/model-evaluation/prose-python314/python.exe'
$run = "import runpy,sys; sys.path.insert(0,'tools/themed-verse'); runpy.run_path(sys.argv.pop(1),run_name='__main__')"
$mandarin = 'build/model-evaluation/corpus-coverage-sources/cmn_sentences_detailed.tsv.bz2'
$cal = '.tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final'
& $py -X utf8 -B -c $run tools/themed-verse/prose_scene_runs_probe.py $mandarin --calibration $cal
& $py -X utf8 -B -c $run tools/themed-verse/prose_scene_rewrite_probe.py
& $py -X utf8 -B -c $run tools/themed-verse/verse_scene_probe.py $mandarin --calibration $cal
./gradlew.bat :app:testDebugUnitTest --tests '*StegoWordingCodecTest' privacyCheck -PrequireRime=true
```

## Attested window slots and bounded sentence ordering (2026-11-22)

The window graph recruits reviewed noun slots from the pinned Mandarin
corpus and Wanxiang. Only authored templates and reviewed slots enter
the frozen asset. The compiler rejects added sentences identical to a
normalized source sentence. All 100 newly emitted slot terms appeared in
the paired workloads. All 384 host pairs recovered exactly. The 524 B
worst carrier fell from 10,788 B to 10,302 B and guaranteed capacity rose
from 796 B to 833 B. The 896 graph states still use 15 count rows.
The shared vocabulary matched by the full codebook grew from 328 to 440.
JDK 21 passed `test`, `privacyCheck`, and `:app:lintDebug`. The API 37
x86_64 emulator passed window and seven-character round trips, the
public clipboard receive flow, and the key-file round trip.
The verified ARM64 artifact is
`app/build/outputs/test-apk/20261122-004322/zero-fish-input-0.7.7-debug-arm64-v8a.apk`
(97,772,954 B). It passed both the window keyboard round trip and the
public clipboard receive flow under ARM64 translation. Release assets
and the core runtime JAR exclude the Debug implementation.
Physical ARM64 performance remains unverified.

A separate four-topic host probe ranks sentence order and caps exact
repetition. At 524 B, repetition dropped from 65.4%–70.0% to 7.4%–19.9%,
and the 128-carrier vocabulary union grew from 2,297 to 3,079 matches.
The 178 B carrier stays free of repeated full sentences. Direct source
sentence continuity and attribution remain open before any app use.
[Full paired evidence](natural-wording-steganography.md#20-语料词槽与受限重复排名)

```powershell
$mandarin = 'build/model-evaluation/corpus-coverage-sources/cmn_sentences_detailed.tsv.bz2'
python -X utf8 -B tools/themed-verse/prose_window_slot_probe.py $mandarin --holdout --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final
python -X utf8 -B tools/themed-verse/prose_corpus_interleaving_probe.py $mandarin
python -X utf8 -B tools/themed-verse/export_window_observation.py --attested-slots $mandarin tools/themed-verse/android-test-profile/window-observation-v4.bin
```

## Mandarin corpus and vocabulary coverage (2026-11-22)

A host probe compares the existing window graph with four topic banks
compiled from a pinned 89,325-sentence Mandarin export. The shared
measurement inventory contains 29,959 Wanxiang surfaces attested in at
least three source sentences. The frozen window vocabulary reaches 328
under that measurement. The source-based renderer grew the 128-carrier
524 B union from 319 to 2,297, with slower gains from the pass schedule.
The one-pass route had no repeated sentence at 178 B. None of the routes
matched the existing guarantee, repetition and prose continuity together.
The Android binding and APK remain the prior linked window release.
[Corpus provenance and paired results](natural-wording-steganography.md#19-扩大语料和词库覆盖)

```powershell
python -X utf8 -B tools/themed-verse/prose_corpus_coverage_probe.py build/model-evaluation/corpus-coverage-sources/cmn_sentences_detailed.tsv.bz2
```

## Linked window progression (2026-11-21)

The Debug window graph adds seven optional types of adjacent observations
and short syntactic alternatives. There are 6,039 distinct sentences and
896 states with the same 15 count rows. The 384 paired host cases include
a holdout seed and recovered exactly. For 524 B, complete-sentence
repetition fell from roughly 23% to 13%. The exact guarantee rose from
732 B to 796 B. Worst length fell from 11,733 B to 10,788 B. The prior
graph reproduced byte for byte and remains the host baseline.
[Comparison and limits](natural-wording-steganography.md#18-窗边随笔的相邻句承接)

The JDK 21 build passed `test`, `privacyCheck` and `:app:lintDebug`.
The x86_64 API 37 emulator passed actual window and seven-character
keyboard round trips, the public clipboard receive flow, and the
key-file round trip. Release merged assets and the core runtime JAR
exclude the Debug grammar and carrier implementation.

The verified mobile artifact is
`app/build/outputs/test-apk/20261121-234143/zero-fish-input-0.7.7-debug-arm64-v8a.apk`
(97,766,398 B). In the ARM64 translation environment, the first run
timed out waiting six seconds for the send preview. A retry with the
same APK and test deadline passed both keyboard and public clipboard
round trips. Physical ARM64 performance remains unverified.

```powershell
python -X utf8 -B tools/themed-verse/prose_window_progression_probe.py --holdout --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final
New-Item -ItemType Directory -Force build/model-evaluation/window-observation-history | Out-Null
python -X utf8 -B tools/themed-verse/export_window_observation.py --progression build/model-evaluation/window-observation-history/window-observation-v3.bin
```

## Window scene corpus and daily profile removal (2026-11-21)

The Debug picker now offers five-character verse, seven-character verse
and window observations. Daily notes remains a historical host benchmark.
The new window binding requires the current Debug build on both ends.

The host joins an authored scene corpus with all 16 locked Wanxiang
tables. It freezes 4,274 distinct sentences. Both 524 B cohorts reduced
full-sentence repetition from about 45% to 23% and increased median
scene vocabulary coverage from 14 to 69 terms per carrier.
The exact envelope limit is 732 B and the 524 B worst carrier is 11,733 B.
Both directions recovered all 256 paired synthetic packets. Two of the
40 locked calibration bodies fit, and the 20% utilization target remains open.
[Detailed comparisons](natural-wording-steganography.md#17-窗景语料扩展与篇内去重)

The API 37 emulator passed the new window and seven-character keyboard
round trips, the public clipboard receive flow, and the key-file round
trip. The Debug APK includes the current graph. Static checks exclude
the old window and daily assets, and the Release assets and core JAR
exclude the Debug implementation.

The JDK 21 build passed `test`, `privacyCheck`, and `:app:lintDebug`.
The ARM64 Debug APK passed signature, permissions and ABI checks.
It is 97,752,585 bytes at
`app/build/outputs/test-apk/20261121-230949/zero-fish-input-0.7.7-debug-arm64-v8a.apk`.
That APK also passed both the window keyboard round trip and the public
clipboard receive test on the emulator with ARM64 translation.
Physical ARM64 performance remains open.

```powershell
python -X utf8 -B tools/themed-verse/prose_window_scene_probe.py --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final
New-Item -ItemType Directory -Force build/model-evaluation/window-observation-history | Out-Null
python -X utf8 -B tools/themed-verse/export_window_observation.py --scene-corpus build/model-evaluation/window-observation-history/window-observation-v2.bin
```

## Window observation Android Debug integration (2026-11-21)

The keyboard offers Window observations (trial) under Natural wording.
The Debug asset freezes the final host grammar in 32,445 bytes. Android
uses the existing wrapped authentication and exact word-context body.
The worker bounds the complete envelope at 544 B and releases count rows
after readback. The Kotlin codec matches the exact host text hashes. Its
524 B and 544 B all-FF vectors measure 15,777 B and 16,377 B.
[Format and profile binding](adr/0041-natural-wording-trial.md#window-observation-profile)

The 256-sample host report reproduced byte for byte. The API 37 x86_64
keyboard test passed real passphrase round trips with window, daily and
seven-character profiles, including wrong-style recovery. App unit tests
covered the framing boundary, damaged assets, edited follow-ups,
cancellation and authentication failure. The 20% utilization goal and
whole-carrier naturalness and detection checks remain open.

The JDK 21 run passed `test`, `privacyCheck`, and `:app:lintDebug`.
The Release merged assets and runtime library exclude the Debug graph
and its codec. The verified ARM64 Debug APK is
`app/build/outputs/test-apk/20261121-222620/zero-fish-input-0.7.7-debug-arm64-v8a.apk`.
It is 97,728,204 bytes and meets the 180 MB test-package limit.
The APK installed directly on the API 37 emulator with ARM64 translation
and passed the window keyboard round trip. Physical ARM64 performance
and the 80 MB size target remain open.

The host exporter requires the previously pinned public inputs and Mini
environment. Gradle verifies and packages the frozen result.

```powershell
New-Item -ItemType Directory -Force build/model-evaluation/window-observation-history | Out-Null
python -X utf8 -B tools/themed-verse/export_window_observation.py build/model-evaluation/window-observation-history/window-observation-v1.bin
.\gradlew.bat :app:testDebugUnitTest --tests '*WindowObservationProfileTest' --tests '*StegoWordingCodecTest' -PrequireRime=true
```

## Window observation action constraints (2026-11-21)

The final host arm binds object, condition and concrete viewing action. It
keeps the five-cycle requirement and passed the paired round-trip, length,
coverage, repetition and independently reviewed excerpt gates. At 524 B it
saved 450 B at the paired median and lowered the worst budget to 15,777 B.
Earlier arms were rejected for insufficient backlight or obstruction outcomes.

The exact counter folds nonterminal one-step states and shares identical rows.
The window grammar grew from 55 to 145 logical states with 15 materialized
count rows in both arms. Earlier reports reproduce byte for byte. The refined
window arm is the host baseline and the source of the Debug Android graph.
[Observation conditions, tests and paired reviews](natural-wording-steganography.md#16-窗边观察动作与因果承接)

```powershell
python -X utf8 -B tools/themed-verse/prose_window_actions_probe.py --refined --examples
```

## Window continuity non-regression trial (2026-11-21)

Three compact variants passed exact round trips, length bounds, continuity
coverage and both repetition gates on paired synthetic batches. The revised
language arm saved a paired median 471 B at 524 B and lowered the worst budget
from 16,230 B to 15,756 B. The shortest arm saved more bytes. Neither it nor
the other variants met the independent phrase-quality gate. The existing
five-cycle window profile remains the reference. Next prioritize adjacent
lexical repetition and concrete observation progression before further
surface compression.
[Paired results and blinded language decisions](natural-wording-steganography.md#15-窗边简句的防退化验证)

```powershell
python -X utf8 -B tools/themed-verse/prose_window_compact_probe.py --examples
python -X utf8 -B tools/themed-verse/prose_window_compact_probe.py --reviewed --examples
```

## Lexical repairs and explicit object continuity (2026-11-21)

The host probe compares the expanded short grammar, targeted collocation
repairs and adjacent-sentence object binding. Stateful suffix counting includes
the rule that a started pair must finish. Strict binding could not fit 524 B,
and optional binding used under 1% of the designated pair positions. The
shortest guaranteed cadence was one pair every two cycles for outing and
organizing and every five for observation. All 192 synthetic 524 B packets
recovered at those intervals. A shuffled review of 12 public excerpts found
same-object progression in all six periodic excerpts and none of the six
baseline excerpts. Whole-carrier naturalness and the existing 20% target still
need work. The prior finite-capacity report reproduced byte for byte.
[Continuity, length and review results](natural-wording-steganography.md#14-动宾搭配与跨句对象承接)

```powershell
python -X utf8 -B tools/themed-verse/prose_continuity_probe.py --examples --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/calibration-input
```

## Short independent prose branch validation (2026-11-21)

The A/B probe preserved the original grammar and weights and added 942 unique
sentences from 18 short framed rules. The full-budget guaranteed envelopes rose
to 704 B for outing, 567 B for observation and 683 B for organizing. Shortest
enumeration recovered 64/64 at 524 B in each expanded scene. Weighted Huffman
also recovered 64/64 in outing and organizing. All 780 B trials and all 40
locked calibration body estimates still exceed the evaluated prose bounds.

The expanded Huffman routes improved exact-sentence repetition on the 178 B
batch. Shortest enumeration showed higher repetition. An independent review
of 18 shuffled three-sentence excerpts flagged phrasing and progression issues
in both arms. The exercise supports the capacity approach and leaves naturalness,
actual encryption and device integration open.
[Branch contributions, paired results and review](natural-wording-steganography.md#13-新增短分支的配对验证)

```powershell
python -X utf8 -B tools/themed-verse/prose_short_branch_probe.py --examples --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/calibration-input
```

## Finite prose capacity and length optimization (2026-11-21)

Exact suffix counting establishes the finite carrier budget for each public
prose grammar. A reversible shortest-first enumerator recovered all 64 desktop
524 B fixtures under 16,384 B, including work that the previous Huffman codebook
could not fit. Window observation lacks enough legal output at that budget.
Shortening helped the outing grammar and reduced desktop capacity when it merged
too many distinct surfaces. The outing 524 B median reached 14,286 B but repeated
sentences more often.

The verified Debug body model and default two-layer size formula yielded
716–1,612 B on 40 locked public calibration bodies. Those sizes exceed all six
prose grammar limits. Equal-size Daily probes had 39/40 hard-limit fits and
2/40 fits at the 20% utilization goal. Next work should expand short, independently
selectable phrases and count capacity before naturalness and channel evaluation.
[Finite counts, codec comparison and public calibration](natural-wording-steganography.md#12-有限长度容量与编码优化)

```powershell
python -X utf8 -B tools/themed-verse/prose_capacity_probe.py --calibration .tmp-evidence/themed-verse-p2-corpus-20261120/calibration-input
```

## Typed prose grammar with lexicon and Mini (2026-11-21)

The host comparison compiles three original prose grammars, validates
their lexical slots against all 16 locked Wanxiang tables and applies
Mini to weights while preserving every legal branch. The two grammar
variants recovered all 384 synthetic 178 B packets. In the outing scene,
63/64 synthetic 524 B packets fit with Mini weights, compared with 5/64
using grammar and corpus counts alone. The other two grammar scenes had
no fits at that size. The existing Daily control recovered both full batches.

Fact-bound rewrite rules added three bits to each new controlled draft.
The host probe verified 87 indexed rewrites, quoted controls, grammar
ambiguity rejection and malformed carrier rejection. Larger corpora,
independent naturalness, real encryption and device tests remain unverified.
[Grammar design, poetry references and results](natural-wording-steganography.md#11-语法树词库和-mini-的联合主机试验)

```powershell
python -X utf8 -B tools/themed-verse/prose_grammar_probe.py --examples
```

## Mini and Tiny candidate screening and prose n-grams (2026-11-21)

The two pinned ONNX graphs passed 26 reference score vectors each. On
the same 19 public slots Mini kept 89 of 142 candidates and Tiny kept 80.
On five existing pairs of equivalent phrases Mini kept both choices in four
pairs and Tiny in three. This fixed filtering threshold is an experiment.

The new host probe trained word bigrams and trigrams from 72 original
segmented sentences across three scenarios. The counts, Mini and Tiny
variants each write and recover synthetic packets through a frozen table.
All 1,152 trials at 178 B recovered byte-for-byte. The 524 B trials reached
the carrier limit, while Daily recovered both batches. Filtering made all
six scene/order comparisons longer at the median. Examples show grammatical
failures and high repetition, so naturalness and release readiness remain
unverified. Two fresh processes reproduced the report and resource hashes.
[Model and capacity results](natural-wording-steganography.md#10-minitiny-和词级-n-gram-主机对照)

```powershell
python -X utf8 -B tools/themed-verse/prose_model_probe.py --examples
```

## Public lexicon and prose carrier feasibility (2026-11-21)

The host probe verified all 16 locked Wanxiang tables and counted 2,192,245
distinct surfaces. The current Daily asset uses 374 distinct two-Han components
out of 174,826 in those tables. Its independently replayed surface recovered 320
synthetic envelopes byte-for-byte at median densities of 0.705–0.710 envelope
bits per UTF-8 carrier byte. The probe exercised the public surface only.

The requested comparison ran both routes against the same two batches of
synthetic envelopes. Generated prose held 12–15 guaranteed bits per paragraph
across three original scenarios. Exact-sentence rewrites held 0–4 bits across
nine drafts and preserved every frozen span and quote control. Both prose
routes rejected all 178 B and 524 B envelopes for capacity. Daily recovered
64/64 in each batch. Two Python hash seeds produced identical reports. These
are host-only seed measurements. Model inference, independent naturalness
and a production carrier remain unverified.
[Design and capacity results](natural-wording-steganography.md#9-非诗歌载体全量词表专用语料与端侧模型)

```powershell
python -B tools/themed-verse/surface_lexicon_probe.py
python -B tools/themed-verse/prose_carrier_probe.py
```

## Current-only word-context format (2026-11-21)

The Debug keyboard derives a single v3 profile binding from the selected
surface and pinned word model. After authenticating a carrier, it accepts
only the Huffman and two rANS body modes. Earlier trial carriers need to
be regenerated for this build. The standalone public-fixture workbench
retains its independently loaded research assets.
[Current format](adr/0041-natural-wording-trial.md#word-context-sender-and-authenticated-body-modes)

The x86_64 Debug unit checks cover rejection of v2 carriers carrying
either a GB18030 or current word packet. A mode 4 packet under a v3
binding also fails closed. The fixed Daily clipboard test fixture now
uses v3 with the same public body and credential.

The x86_64 API 37 emulator suite passed 75 tests with three opt-in skips.
Both public wording interop cases passed in a separate opt-in run. The
seven-character round trip, system clipboard reception and panel reachability
passed on a narrow dark layout and a wide light layout.

The ARM64 Debug APK passed all unit, privacy, Lint, signature, ABI and
permission checks. It is 97,706,305 bytes, within the 180 MB package limit
and above the 80 MB target. The packaged APK passed upgrade installation,
the seven-character round trip, Daily clipboard reception and the public
workbench under emulator ARM64 translation. Next: repeat the keyboard
round trip and clipboard reception on a physical ARM64 device.
[ARM64 Debug test APK](../app/build/outputs/test-apk/20261121-150811/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ime-core:testDebugUnitTest :security:testDebugUnitTest privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Word-context compression in the Debug keyboard (2026-11-21)

This entry records the preceding trial build.

New natural-wording messages choose among direct Huffman and two word
rANS modes before encryption, using padded size and simplicity as the
tie-break. The per-message context is reconstructed from the decoded
prefix and cleared at operation end. The new modes identify the
hashed Debug public model. The authenticated GB18030 mode remains
readable, and older recipients need the updated APK to open new modes.
[Format and security boundary](adr/0041-natural-wording-trial.md#word-context-sender-and-authenticated-body-modes)

The compiled Kotlin implementation matched all 129 Python reference
packets byte-for-byte. Against the previous body codec, the 40 P2
excerpts used 10.1% fewer padded envelope bytes in total, the short
controls used 12.8% fewer and the nine technical documents used
32.1% more. Holdout excerpts remain sealed.
[Comparison and public model reproduction](themed-verse/word-context-probe.md#android-debug-port-comparison)

The x86_64 API 37 platform suite had 75 passes and three opt-in skips.
The public wrapped GB18030 clipboard fixture and new natural-wording
round trips passed. Unit tests compare all three chosen modes against
independent vectors and reject malformed authenticated bodies, changed
assets, invalid UTF-8, truncation, oversized lengths and cancellation.
The compiled body reader also rejected 5,000 seeded randomized malformed
packets through its format-error boundary.

Full unit checks, `privacyCheck`, Debug Lint and APK signature, ABI and
permission checks passed. The ARM64 Debug APK is 97,705,857 bytes, within
the 180,000,000-byte package limit and above the 80,000,000-byte target.
The delivered package passed upgrade installation, new wording round
trip, prior wrapped clipboard reception and public-fixture recovery
under ARM64 translation. Physical ARM64 validation remains pending.
[ARM64 Debug test APK](../app/build/outputs/test-apk/20261121-144941/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ime-core:testDebugUnitTest :security:testDebugUnitTest privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Word-boundary prediction probe (2026-11-21)

The offline probe compares actual word-token rANS streams with identical
segmentation and literal escapes. On the 40 P2 calibration excerpts,
frozen bigram context saved 135 stream bytes against unigram. The
message-local last-successor cache over unigram saved 1,295 bytes.
A three-way selector including direct Huffman saved 1,766 stream bytes
against Huffman and reduced the padded envelope on 25 of 40 excerpts.
The 80 short controls stayed in the same padding bucket.

Byte-exact recovery and malformed frame checks passed. Distinct Python
hash seeds reproduced the results and fixed payload hashes. The probe
uses no zlib stage in its payloads. Holdout examples remain sealed.
[Models, measurements and reproduction command](themed-verse/word-context-probe.md)

## Wording category and bit-packing audit (2026-11-21)

The keyboard's carrier picker groups five-character verse, seven-character
verse and daily notes under Natural wording for send and receive. The
second selection determines the profile. System paste can keep the full
carrier in its draft while the user chooses both levels.

The Android body format is unchanged. The host probe verifies the exact
word-level Huffman stream and tests byte rANS, contextual rANS and raw LZMA2
with all framing bytes counted. Choosing the shortest Huffman-based
stream saved 8 stream bytes and one 64-byte envelope bucket across 40
independent calibration excerpts. None of the second passes reduced the
80 short controls. Holdout examples remain sealed.
[Audit, measurements and reproduction command](themed-verse/bit-packing-probe.md)

The API 37 x86_64 device suite had 75 passes and three opt-in skips.
The physical paste-to-style flow passed three more runs. Both picker levels
and clipboard reception passed at 240×480 dp in dark mode with 1.3 font scale
and 640×320 dp in light landscape. Closing the first picker before opening
the second and removing attached-window animations kept rapid taps active.

Full unit tests, `privacyCheck`, Debug Lint and package checks passed.
The ARM64 Debug APK is 97,606,501 bytes, within the 180,000,000-byte
budget and above the separate 80,000,000-byte target. It passed upgrade
installation, nested send and receive, public fixture recovery and repeated
physical-tap clipboard tests under ARM64 translation. Physical ARM64 device
validation remains pending.
[ARM64 Debug test APK](../app/build/outputs/test-apk/20261121-140758/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Seven-character verse and public association probe (2026-11-21)

The Debug keyboard's send and receive picker includes seven-character verse
from 42,153 distinct pinned Wanxiang lines. The keyboard now accepts only
wrapped wording envelopes. The single-layer carrier is exercised through
the public fixture workbench. The wrapped five-character and daily-note
assets are byte-identical to the prior build. Two independent generations
matched all carrier and compression asset hashes.
[Format and current receive choices](adr/0041-natural-wording-trial.md#seven-character-verse-and-current-receive-choices)

The public association probe verified byte-exact restoration for all of its
dictionary variants. The best mix for the 40 independent calibration
excerpts saved 32 compressed bytes in total and moved no envelope to a lower
padding bucket. On 80 short controls seen during association curation, it
reduced 48 envelopes. The app uses the existing compression dictionary.
[Measurements and reproduction command](themed-verse/association-probe.md)

The x86_64 API 37 suite completed with 75 passes and three opt-in skips.
The real IME sent and received seven-character verse, checked all four
receive choices and rejected the wrong style before a successful retry.
Seven-character receive and the daily system-paste regression passed at
240×480 dp in dark mode with 1.3 font scale and at 640×320 dp in light
landscape. Codec tests cover the retired envelope, altered punctuation,
truncation and re-encoding ciphertext under a different profile.

The standard ARM64 package checks passed with full unit tests, `privacyCheck`
and Debug Lint. The package is 97,605,393 bytes, within the 180,000,000-byte
budget and above the separate 80,000,000-byte target. The delivered APK also
passed upgrade installation, seven-character send and receive, daily system
paste and the public fixture page in the emulator's ARM64 translation.
Physical ARM64 device and independent poetry-readability validation remain
pending.
[ARM64 Debug test APK](../app/build/outputs/test-apk/20261121-133504/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ime-core:testDebugUnitTest :security:testDebugUnitTest privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Natural wording receive and paste fix (2026-11-21)

In the Debug keyboard, the receiving workspace now keeps bounded unmarked
carrier text pasted from the system clipboard or explicitly imported from an
editor selection. Start decryption opens the style picker when the carrier
requires one. The chosen style is checked on the worker to display the
credential needed before the user submits it. A style mismatch leaves the
carrier available for another choice. Attached pickers keep the original
editor focused, so opening and dismissing the paste menu preserves the active
input session.

The old Debug build rejected the public daily fixture pasted before choosing
its style. The fixed build opened that fixture after physical long-press and
menu taps. The selection round trip also passed after choosing the wrong style
first. Both routes and inline controls passed at 240×480 dp in dark mode with
1.3 font scale and 640×320 dp in light landscape on the API 37 emulator.

Full unit tests, `privacyCheck`, Debug Lint and the merged Debug/Release
manifest checks passed. The x86_64 API 37 device suite had 74 passes and three
opt-in skips for fingerprint and host interop. The ARM64 Debug test APK is
97,168,206 bytes, within the 180,000,000-byte package limit and above the separate
80,000,000-byte target. The same APK passed an upgrade install and three receive
smoke tests under the emulator's ARM64 translation. It also recovered the
previous format's independent public host carrier. Physical ARM64 device
validation is pending.
[ARM64 test APK](../app/build/outputs/test-apk/20261121-121543/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest privacyCheck "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Wrapped natural wording Debug trial (2026-11-21)

The keyboard's carrier picker now offers new five-character verse and daily
notes. The daily sender uses 50 time phrases and 2,118 verb-object phrases in
four topics, compiled from the locked Wanxiang base lexicon. The older verse
format has an explicit receive entry. All three profiles retain exact text
comparison at receive time.

New senders can use a nonempty user-chosen passphrase up to 1,024 UTF-8
bytes, a random shared key, a key file, or either text credential with a
key file. Password modes use the existing three Argon2id cost profiles.
The default cascade is AES-256-GCM and XChaCha20-Poly1305. The cipher editor
can set each layer's width or randomize the permitted widths independently.
The worker draws and wraps a fresh random key and nonce for each layer.
*Settings → Steganography → Generate and save a random key file* saves
32 independent random bytes to a user-selected local document.
The Android picker smoke test confirmed a zero-byte document before
consent and a 32-byte file after confirmation. The provider tests
reopened a generated file as a file factor and rejected a write to a populated file.
[Format and data rules](adr/0041-natural-wording-trial.md#wrapped-debug-trial-2026-11-21)

The x86_64 API 37 steganography suite completed with 51 passes and 11
opt-in cases skipped. A real IME round trip used the daily profile,
three body layers and a one-character passphrase. The same flow and the
input feedback passed at 240×480 dp in dark mode with 1.3 font scale and
640×320 dp in light landscape. The original verse profile recovered the
1,856-byte public fixture in both directions between Android and the
independent host. Two offline builds produced identical carrier and
compression tables. The Android Debug unit suite, full packaging checks,
privacy checks, Lint and the merged
Debug/Release manifest checks passed. The ARM64 test APK includes the new
profile and export entry. Its size is 97,161,886 bytes against the test
package limit of 180,000,000 bytes and the 80,000,000-byte target.
Release assessment still requires independent
readability, detection and held-out capacity results and ARM64 device tests.
[ARM64 test APK](../app/build/outputs/test-apk/20261121-110558/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Natural wording transport design (2026-11-20)

**P0 and P1 host acceptance complete.** The nine excerpts match the frozen
inputs at `b763675`. The original binary and static-position results reproduced
with 2/9 and 4/9 meeting the 20% target. The combined host sender and independent
receiver recovered all nine documents byte-for-byte. The word-code, position
and joint groups passed 2/9, 4/9 and 4/9 respectively. Six envelopes dropped
one 64 B bucket in the joint group. The five misses restored exactly and
exceeded `5*S` by 582–3,384 B. All nine met both whole-carrier limits.
[Commands, per-stream sizes, results and fixed vectors](themed-verse/p1.md)

P1 profile SHA-256 is
`151099ba9459617e0f3af72543fb876f8653ef3ae984b6d1218a2b8f361e33db`.
Two offline builds produced identical assets. The receive process used only
the public profile assets, fixture key and complete carrier as inputs. It
tested actual winners for word codes and position plus fixed dictionary,
unknown characters and CRLF, malformed and overlong inputs, authentication
failures, and absent or mismatched resources. Three public fixed vectors and
the corpus lock travel with the prototype. The security and ime-core unit tests
and `privacyCheck` also passed. [P1 acceptance command](themed-verse/p1.md#运行-p1)

**P2 cost diagnosis.** A fresh pass over the nine frozen documents verified
the static position round trip. The five misses contained 479–965 B of literal
payload before secondary compression. Removing only the fixed row-type bits
could save at most 58–77 whole bytes per miss before paying for types or
recompressing. The clipboard design needs 49 B off its already compressed
842 B stream to reach the 930 B envelope. Measure both stages independently.
The historical key replay already packs each letter into five bits.
[Counts, timing scope and reproduction command](themed-verse/p2-plan.md#拼音词号与位图各能省多少)

**P2 paired regression experiments.** The prefix and bitmap alternatives
preserved the fixed P1 tokens. Sparse bitmaps saved 414 B across the nine
bare streams. Their dictionary-compressed total was 2,352 B above P1.
Frequency codes and bit-cost segmentation produced local bucket reductions.
Native replay selected 25 internal phrases and zero whole sentences out of
18 qualifying spans. Both expanded selectors chose P1 modes for all nine
documents, with byte-exact recovery 9/9 and 20% utilization 4/9. No additional
document crossed the threshold. Host warm end-to-end P50 was 644 ms for P1,
672 ms with new static modes, and 1,654 ms with native key replay.
[Bit accounting, real carriers, cold/warm timings and commands](themed-verse/p2-results.md)

**P2 independent corpus and calibration.** Work records and free narratives
each have 16 training works, 20 calibration works, and 30 held-out works.
The lock records the public sources, credits, work groups, excerpt hashes,
and overlap checks. Frozen P1 recovered 20/20 per genre. Both genres met the
two whole-carrier limits for every sample and had 0/20 at the 20% utilization
target. The visible carriers exceeded their targets by 981–3,384 B for work
records and 1,969–4,766 B for free narratives. [Corpus lock](themed-verse/p2-corpus-lock.json) ·
[Per-sample results and commands](themed-verse/p2-calibration.md)

**P2 training-source ablation.** With the same P1 position layout and 12,773 B
dictionary budget, independent training lowered the actual envelope for
12/20 work reports and 0/20 historical narratives. Both genres restored 20/20
and added zero passes. The 60 held-out excerpts are sealed with zero attempts.
Capacity remains the calibration gate for blind scoring. Formal format,
binding, interoperability, mobile cost and product entry follow the planned
reviews. [Training ablation](themed-verse/p2-calibration.md#仅更换位置字典训练来源) ·
[P2 thresholds](themed-verse/p2-plan.md#预先登记的验收与停止条件)

**P2 GB18030 training-source ablation complete.** The shared 15,021 B GB
dictionary used the frozen independent training set. With the previous
independent position dictionary, changing only the GB training source
lowered the envelope for 16/20 research abstracts and 5/20 sampled fiction
excerpts. Both genres restored 20/20 byte-for-byte and had 0/20 at the
20% target. The tenth-smallest stream gap fell from 171 to 119 B for
abstracts and from 284 to 267 B for fiction. Even with all five-character
lines available, the new fiction envelopes have a theoretical ceiling of
9/20 under the target.

Both variants restored all nine P1 regression documents. The GB trial lost
the two passes from the position-only reference, v0.7.6 and v0.7.7. Frozen P1
reproduced 4/9. This closes the same-budget dictionary trials for the
sampled genres. The 60 holdouts remain sealed, with zero attempts.
[Per-sample results, verification and commands](themed-verse/p2-gb-calibration.md) ·
[Second P2 task](themed-verse/p2-plan.md#下一会话第二张任务卡)

**P2 five-character continuity calibration.** Held the selected GB18030
streams, envelopes and layout fixed for all 20 research abstracts. The
same-character rule had an upper bound of 0/20 and was screened out. The
original linked rule, same-family rule and same-or-next-family rule
recovered 20/20 each and met the 20% target for 0/20, 5/20 and 11/20.
All 60 carriers met both whole-carrier limits. The sampled fiction is
bounded at 9/20 with the fixed envelopes even using the full line pool.

The 11 eligible same-or-next-family carriers have equally long ordinary
controls in an anonymous calibration reading packet. Reader scores and
a length-matched human comparison remain pending. The 60 held-out
excerpts are sealed with zero attempts.
[Rules, per-sample results and reading packet command](themed-verse/p2-theme-calibration.md)

**Android Debug verse workbench.** The frozen same-or-next-family profile runs
with bundled public fixtures in a private Settings page. API 37 x86_64 checks
covered the entry, local generation and recovery, the host vector, and clearing
on recreation. The independent host recovered the APK's 1,856 B public fixture
from its 3,868 B carrier. The Kotlin JVM run restored 20/20 calibration
abstracts and matched the previous 11/20 pass decisions. One sample moved up
one 64 B envelope bucket with the JVM zlib implementation. Independent reading
scores and the sealed holdouts remain pending.

The 97,101,787 B ARM64 Debug APK passed unit tests, privacyCheck, Lint,
native Rime builds and package verification. ARM64 device behavior awaits
physical testing. [Implementation and verification](themed-verse/android-test-build.md) ·
[ARM64 test APK](../app/build/outputs/test-apk/20261120-202507/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

**Android natural wording workspace trial.** Debug now accepts actual drafts
in the keyboard's existing steganography workspace. The sender uses a 256-bit
random shared key and previews the entire five-character carrier, with its
measured UTF-8 utilization, before deliberate insertion. The recipient selects
verse mode, loads the intact text and opens it with the same key. The trial's
authenticated profile is distinct from the public fixture and exact carriers.
Independent Python/Rust and Android each recovered 1,856 public bytes from
the other sender. The same 20 calibration abstracts recovered exactly, with
11 at or above 20% and nine below. Real IME round-trip, masked rendering and
editor revocation passed on x86_64 API 37. Release activation, rated reading,
held-out capacity and ARM64 physical-device testing are pending.
The 97,119,343 B ARM64 Debug trial APK passed full unit tests, privacyCheck,
Lint, native Rime builds and package verification.
[Trial protocol](adr/0041-natural-wording-trial.md) · [Results and timings](themed-verse/android-test-build.md#工作区验证) ·
[ARM64 trial APK](../app/build/outputs/test-apk/20261121-000415/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

**Wording preview touch regression.** On a real IME tap, the empty-key prompt
was clipped by the form scroll area. The trial now places the key generator
and the preview action in a fixed row and shows validation feedback alongside
it. The complete carrier has a read-only preview surface with deliberate
insertion. Actual touch tests passed for missing input, invalid key and full
round trip on API 37, including narrow dark portrait and landscape layouts.
The new ARM64 Debug APK passed full unit tests, `privacyCheck`, Lint, Rime
build and package checks.
[Reproduction and fix](themed-verse/android-test-build.md#生成按钮反馈修复) ·
[ARM64 preview fix APK](../app/build/outputs/test-apk/20261121-101521/zero-fish-input-0.7.7-debug-arm64-v8a.apk)

### Earlier public pilot

The first themed-verse host study on `zn/stego-themed-verse` resumed from
`b763675`, a handoff following local main checkpoint `465ffd7`. All nine public
carriers round-tripped in a fresh receiver process. A five-character line plus
one full stop carries 17 bits with free choice, 14 with the night-landscape
theme, and 13 after the opening 14-bit line with scene transitions. At most 85
lines fit the draft, giving respective maximum whole payloads of **180, 148
and 138 bytes**, assuming the receiver already knows the length. Readability at
these rates awaits independent readers. [Study design](natural-wording-steganography.md)

**Reproduce from the repository root** with Python 3 and the generated
`engine-rime/build/generated/publicDictionaryAssets/rime/dicts/shici.dict.yaml`.
Both commands run offline. The first reads the published surfaces and lengths.
The second builds fresh resources for each side, checks baseline and failure
cases, and regenerates fixtures and blind packets in a separate directory.

```powershell
python -B tools/themed-verse/check_vectors.py
python -B tools/themed-verse/study.py .tmp-evidence/themed-verse-v1
```

**Resource and protocol.** Wanxiang RIME-LMDG v18.1.1, revision
`26a9efeb18e604c091f4d3890d00bec723fe755f`, CC BY 4.0. Each load checks the
prepared 14,571,886-byte table against the locked SHA-256
`0bc14524727ce2a8e9b4ff58f91d275e9a91b65c50b25ca6f0c7578c9ca241f3`.
The generated packets select and repunctuate lines from this table. One local
run took 1.83 s overall, including 0.88 s for a second resource load. These
are host wall times. [Source lock](../tools/dictionaries/sources.lock.json) ·
[Upstream](https://github.com/amzxyz/RIME-LMDG) · [License](https://creativecommons.org/licenses/by/4.0/)

Read column one after the YAML header, keep exactly five or seven BMP Han
characters U+4E00–U+9FFF, deduplicate and sort by code point. Free five/seven
pools reproduced 213,300/92,141 entries. For 162 B they yielded 77/81 lines
(462/648 UTF-16 units). Literal five-character 月/山 filters yielded
3,942/6,404 entries and round-tripped in 118/108 lines (708/648 units).

The main study uses five-character entries. The theme is the union of sky
月星夜霞云, water 江水溪泉波 and hills 山峰岭松林, matching any listed character.
The union has 30,908 entries, its overlapping classes 12,833/9,667/10,582.
Linked mode starts with the union. The rightmost imagery character of the
previous recovered line determines its class. Sky leads to water, water to
hills, hills to sky. This lexical transition does not certify meaning, rhyme,
or grammatical continuity. Every choice uses the first power-of-two prefix
of the sorted pool, so lexicographically late lines are systematically omitted.
An index consumes bits MSB first with zero padding only in the final line.
Concatenate lines with one 。 after each, without whitespace or normalization.
For each length S the common bytes are SHAKE256 of UTF-8
`ZeroInput/public-verse/v1/S` (S in decimal), squeezed to S bytes.
[Public vectors with full text and hex](themed-verse/vectors.json) ·
[Codebook and decoder](../tools/themed-verse/verse.py)

| Mode | Payload B | Lines | UTF-16 units | UTF-8 bytes | Repeated lines beyond first / lines |
|---|---:|---:|---:|---:|---:|
| Free | 162 | 77 | 462 | 1,386 | 0/77 |
| Free | 226 | 107 | 642 | 1,926 | 0/107 |
| Free | 290 | 137 | 822 | 2,466 | 0/137 |
| Theme | 162 | 93 | 558 | 1,674 | 1/93 |
| Theme | 226 | 130 | 780 | 2,340 | 0/130 |
| Theme | 290 | 166 | 996 | 2,988 | 3/166 |
| Linked | 162 | 100 | 600 | 1,800 | 0/100 |
| Linked | 226 | 139 | 834 | 2,502 | 1/139 |
| Linked | 290 | 179 | 1,074 | 3,222 | 2/179 |

| Mode | Exact carriers | Equal bytes | Draft fits / insufficient | Whole-carrier fits / insufficient |
|---|---:|---:|---:|---:|
| Free | 3/3 | 678/678 | 1/3 (33%) / 2 | 3/3 (100%) / 0 |
| Theme | 3/3 | 678/678 | 0/3 (0%) / 3 | 3/3 (100%) / 0 |
| Linked | 3/3 | 678/678 | 0/3 (0%) / 3 | 3/3 (100%) / 0 |

Both limits must hold for each fit: draft 512 units and 1,536 bytes, whole
carrier 8,192 units and 16,384 bytes. Every size was attempted once per mode.
Zero candidate shortages occurred in these nine trials. Each numerator counts
the fixed fixtures only. The whole-carrier column measures space for the
surface, without exercising Android import or extending the public draft.

**Receiver checks.** The receiver has no encoder cursor or candidate list.
It reloads the locked resource, given only the mode, byte count and surface.
Tests cover zero/one candidate, nonzero tail padding, missing/extra line,
punctuation, unassigned text and unknown or incompatible mode. A single
substitution between assigned lines can silently change recovered bytes.
Overlapping codebooks can even accept the same bytes under another mode.
The public expected bytes are a test oracle. Authenticating configuration,
length, envelope and complete visible text remains a prerequisite for releasing
any plaintext. No actual encrypted message, credential or authentication tag
was tested here. [Negative cases](../tools/themed-verse/study.py)

**Blind reading pending.** Give readers only the appropriate packet and blank
score sheet. Use separate readers for short and full packets to avoid
recognition. Obtain independent scores before opening the matching
`*-organizer.txt` key. Short samples mix the first four lines of each carrier
and each matched ordinary control with three complete historical centos. The
full packet pairs all nine carriers with nine independently sampled ordinary
centos using identical pools, transitions, punctuation and line counts.
The historical anchors match meter and display length only in the short
packet. They are Wen Tianxiang's *Collected Du Fu Lines* nos. 164, 167 and
180, shown in simplified script with standardized punctuation. There is no
length-matched historical long poem in this pilot. [Short packet](themed-verse/short.md) ·
[Short score sheet](themed-verse/short-scores.md) · [Full packet](themed-verse/full.md) ·
[Full score sheet](themed-verse/full-scores.md) · [Historical source](https://zh.wikisource.org/zh-hans/集杜詩)

**Decision.** Hold the independent Kotlin codec stage. The constrained modes
miss the draft limit even at 162 B. Longer surfaces meet the numeric carrier
limits, but independent coherence scores, an accepted long-text budget and a
reviewed authenticated binding are outstanding. Next collect blind scores
for adjacent lines and full passages, then decide whether the long-verse use
case merits a binding design and independent implementation. The small host
pilot does not establish channel robustness or detector performance. Android
entry, capacity expansion, compact ciphertext and portable biometric credentials
remain separate decisions.

### Long-text compression probe

Run `python -B tools/themed-verse/compression_probe.py` from the repository
root. An experimental mixed-radix index uses all eligible lines with the
same imagery transitions. Starting with the big-endian payload integer, emit
its remainder modulo the current pool size and continue with the quotient.
Stop when the product of the visited pool sizes covers the entire known-length
byte range, including leading zeroes. The receiver rebuilds the pools from
the text and uses the corresponding place values. All nine published inputs
round-tripped with fresh receiver resources. At 162/226/290 B, free mode took
74/103/132 lines, theme mode 87/122/156 and linked mode 97/135/173.
Even the optimized linked 162 B surface still exceeds the 85-line draft.
Applying zlib level 9 directly to these public pseudorandom bytes added 11 B
at each size.

For a separate pre-encryption size estimate, take three nested UTF-8 prefixes
of Han-initial paragraphs in the public design document (Markdown retained).
All three zlib streams decompressed byte-for-byte. Budget each size using
the existing single-layer AES 64-byte frame buckets, then actually encode and
decode deterministic dummy bytes of the budgeted length with the linked
mixed-radix index. The bytes account for framing overhead but are synthetic.

| Prose UTF-8 B | zlib B | Budget B, raw → compressed | Linked lines, raw → compressed | Carrier UTF-8 B, raw → compressed |
|---:|---:|---:|---:|---:|
| 510 | 382 | 674 → 546 | 402 → 326 | 7,236 → 5,868 |
| 1,024 | 759 | 1,186 → 930 | 707 → 555 | 12,726 → 9,990 |
| 2,046 | 1,353 | 2,210 → 1,506 | 1,318 → 898 | 23,724 → 16,164 |

The largest compressed example fits both whole-carrier limits (5,388 units,
220 UTF-8 bytes of headroom). It illustrates a bucket crossing for one public
technical text. Corpus generality, long-passage readability, actual authenticated
compression metadata, length disclosure and bounded decoding remain open.
Any product-level compression needs separate format review. [Host probe](../tools/themed-verse/compression_probe.py)

### Raw-secret utilization target

The user set utilization to original UTF-8 secret bytes divided by complete
visible UTF-8 bytes. The [20% research plan](natural-wording-steganography.md#8-原始正文利用率至少-20-的长文本方案)
budgets public dictionary compression, the single-layer frame and visible
punctuation/paragraphs. Reproduce with
`python -B tools/themed-verse/utilization_probe.py`. In the exploratory
size simulation, two of four version notes reached 21.53% and 21.16%; both
design excerpts missed (15.04% and 17.79%). A separate synthetic repeated
form reached 50.60%. No independent reader has evaluated these whole texts.
Next measure the cost of curated stanza transitions and blind-rate full
length against matched controls. Actual authenticated compressed frames and
independent device decoding remain review gates.

The subsequent [binary codec comparison](natural-wording-steganography.md#加密前的二进制文本编码)
ran upstream rmw-utf8 0.0.6 on the same six documents. All six round-tripped;
RMW alone reached 13.80–17.79% under the existing frame budget. Keeping the
best exact codec retained two passes out of six. Separate CRLF, CR and emoji
fixtures failed exact RMW reconstruction.

The [binary host prototype](themed-verse/prototype.md) now performs actual
single-layer authenticated encryption and reconstructs the original bytes from
the UTF-8 verse in a fresh process with only the agreed profile and key.
Run `python -B tools/themed-verse/check_binary_prototype.py` after building
the two locked Rust helpers. All nine project documents round-tripped, with
two above 20%; the separate synthetic form also passed. The new literal-safe
word codec reached the smallest envelope bucket for v0.7.5 without adding a
20% success. The three additional project documents reached 15.03–16.76%.
The original 24 Rust reference output lines and nine previous verse vectors
still match. Next evaluate whole-text readability and non-template prose
before reviewing a mobile implementation.

The subsequent [default-dictionary and native replay comparison](themed-verse/prototype.md#默认词库位置与原引擎按键重放)
compared full-table positions, actual librime spelling/candidate events and the
combination on the same corpus. All nine real documents recovered byte-for-byte.
Static positions plus compression lowered six encrypted envelopes by 64 B each,
raising the number at or above 20% from 2/9 to 4/9. Candidate replay alone
did not beat the existing codec on any of the nine. A synthetic form and a
public verse excerpt were separate controls. Keep the experimental profile
isolated while checking wider genres and full-passage readability.

## Personal stego notes and public verse (2026-11-20)

The user subsequently clarified the personal credential acceptance criteria:
an independently enrolled fingerprint plus the passphrase must work on a new
phone, and other fingerprints enrolled in the phone's OS must not authorize
the biometric factor. The device-bound feature below does not meet those
criteria. Stock Android's public APIs for built-in sensors do not expose an
individual fingerprint identity or portable template. The personal fingerprint
feature remains open pending a decision on supported hardware.

[ADR 0040](adr/0040-local-biometric-message.md) adds the device-bound passphrase
mode, its private Activity entry, and the short-word scorer feasibility assessment.
The editor appends complete couplets selected from the bundled Wanxiang poetry
table. Natural wording remains a research item.

The API 37 emulator exercised two distinct fingerprint prompts for sealing and
opening a note. The private page displayed masked plaintext until reveal, and
the original editor remained empty. The poetry panel tests covered filling an
empty cover, appending, and hiding the control in the secret field. For the
biometric round trip, run StegoKeyboardIntegrationTest's
localDiaryRoundTripRequiresTwoFreshBiometricOperations with instrumentation
argument `-e stegoTestFingerprint true` and touch the enrolled test fingerprint
at each system prompt. OEM biometric behavior remains to be checked.

The offline x86_64 Rime Debug build, Debug unit tests, privacyCheck and Debug
Lint passed. All 54 stego platform cases were covered across the fingerprint
run and the explicit eight-case vault PIN run on the disposable API 37 AVD.
The layout checks also passed with dark theme at 360 x 640 and 640 x 360 dp.
The reviewed dictionary currently yields 13 complete verse pairs.

## File approvals and Android 10 verification (2026-11-20)

The [local acceptance results](audits/2026-11-19-ai-feature-assessment.md#local-remaining)
cover file identity, read/search scope, destination model and typed network
failures in tool tasks. An API 29 keyboard crash was fixed in two views.
The six tool/device protocol encodings, 26 API 29 platform tests and native
assistant screen-off recovery were verified locally. Unit reports show 1,155
passes and two live-provider skips. PrivacyCheck and Debug Lint passed.
The signed ARM64 Debug APK is 96,431,659 bytes, up 4,028 bytes from the previous
review. Live-model acceptance and physical OEM behavior still require external
test resources.

## AI reliability follow-up (2026-11-20)

The [follow-up review](audits/2026-11-19-ai-feature-assessment.md#phase-one-review)
covers formatted answers and bounded section insertion, reasoning delimiters,
on-demand section rows, queued-chat navigation and native-assistant launch
failure. Debug units plus ai-api report 1,153 passes and two live-provider skips.
PrivacyCheck, Debug Lint, 26 API 37 instrumentation tests and system-assistant
invocation, screen-off clearing and failed launch recovery passed. The verified
ARM64 Debug APK is 96,427,631 bytes, up 1,376 bytes from phase one.
Next checks include live-provider compatibility and physical OEM editor behavior.

## AI conversation reliability (2026-11-20)

Phase one of the [AI assessment](audits/2026-11-19-ai-feature-assessment.md#phase-one)
is implemented. B01-B05 cover omitted context, foreground-only manual recovery,
editor acceptance, literal reasoning tags and full bounded native-assistant answers.
Debug unit tests plus ai-api report 1,146 passes and two opt-in skips. PrivacyCheck
passes, Debug Lint reports no issues and 13 API 37 emulator tests pass. The full
ARM64 real-Rime Debug APK is 96,426,255 bytes (+6,848 from the same local baseline).
The assessment records commands, artifact hash and remaining live-provider/OEM checks.

## Stego review and ARM64 test delivery (2026-11-19)

Reviewed the stego fixes and required draft/input dependencies; no new blocking
issue was found. Changes are pushed through source commit
`44815410e6a7ba365ac720303f25862088d934c6`. A separate clean checkout excludes
unrelated expression-export and AI work. Exact-byte checkout attributes preserve
upstream licenses and the pinned Rime configuration on Windows without weakening
integrity checks. See the [review and delivery record](audits/2026-11-19-stego-security-ux-verification.md#7-代码复查提交与重新打包).

The full package script ran without SkipChecks: 2,284 unit passes, four optional
provider skips, no failures/errors (including up-to-date/from-cache reports),
privacyCheck, zero-issue Debug Lint, real-Rime ARM64 build and APK/signature/ABI/
permission checks pass. All three distribution checksums match.
The [ARM64 Debug test APK](../app/build/outputs/test-apk/20261119-213202/zero-fish-input-0.7.7-debug-arm64-v8a.apk)
is 96,418,147 bytes; SHA-256
`06e7de44b13ea15085e3e401d75c7d98c610c2115cebb14dd27f644695e9a2e3`.
The 180 MB budget passes; the 80 MB target remains exceeded. No platform rerun,
physical-device install, Release build or publication was performed in this round.
Next: physical ARM/OEM, older Android and the full system-clipboard rejection
matrix. Final evidence: `.tmp-evidence/stego-reviewed-arm64-package-complete.log`
and `.tmp-evidence/stego-reviewed-unit-results.json`.

## Stego audit verification follow-up (2026-11-19)

The [second source verification](audits/2026-11-19-stego-security-ux-verification.md#6-二次回源复核与修正)
corrects the missed existing working-deadline test and treats the proposed IPC
selection race as unproven. Pending candidate rejection still blocks incomplete
submission until explicit cancellation. Stego now opts into first-backspace
cancellation of rejected direct input without deleting accepted text; other draft
consumers retain their previous behavior. Submission feedback distinguishes
budget rejection from unfinished conversion. No new credential paste, retention,
key export, permission or format path is added.

Final app/core reports: 774 passes, two opt-in provider skips, zero failures/errors.
Real-Rime x86_64 Debug/test APKs, privacyCheck and zero-issue Lint pass. API 37
passes 24 targeted platform regressions, plus nine repeated checks at 320x480 dp,
dark theme/font 1.3. Emulator settings restored and task-owned emulator closed.
The verification records final logs/APK provenance and the initial failures that
led to limiting the behavior to Stego. Next: the full system-clipboard rejection
matrix and physical ARM/OEM/older-Android acceptance. No ARM redistribution,
Release or publication occurred.

## Stego security/UX audit resolution (2026-11-19)

The user approved verifying and fixing the feedback while preserving draft
revocation on navigation, external selection changes and recreation. The
[item-by-item resolution](audits/2026-11-19-stego-security-ux-resolution.md)
distinguishes bugs, missing guidance, deliberate boundaries and inaccurate claims.
Fixes cover independent working/authentication deadlines, exact UTF-8 input
budgets, early source revocation, handoff acceptance/global-vault/deletion-generation
checks and reduced hidden-text copies. UI adds expiry guidance, confirmed field
clearing, visible paste/next/selection actions, vault timestamps, explicit credential
and clearing limits, public-cover accessibility and status announcements.
No format, permission, export, credential paste or key-export change is introduced.

Validation and final artifact provenance are recorded in the resolution document.
Reports total 1,151 unit passes plus two opt-in provider skips, zero failures/errors;
the final real-Rime Debug/test build, privacyCheck and zero-issue Lint pass. API 37
passes all 52 stego tests after the final handoff change, plus 12 draft tests.
Five UI checks repeat at 320x480 dp dark/font 1.3 and actual 640x320 dp landscape.
Next: physical ARM/OEM, older Android and the complete explicit system-clipboard
rejection matrix; retain existing conservative fixture ownership checks. Existing
ARM64 distribution packages predate this change. No release or publication occurred.

## AI feature assessment (2026-11-19)

The [AI assessment](audits/2026-11-19-ai-feature-assessment.md) records five
findings, capability gaps, compatibility risks and proposed priorities. Scoped
AI tests report 389 passes and two opt-in provider skips; privacyCheck passes.
Context, queue and output-splitting probes reproduce three logic paths. Editor
refusal and native-assistant truncation still need targeted platform validation.
This is an analysis record; no AI implementation fixes were made.

## Stego defaults and three-field explicit paste (2026-11-19)

Settings > Steganography now persists the default random master length (initially
256 bits), ordered cipher/width cascade (initially AES-256), and independent
private-vault/system paste switches. New workspaces copy these settings and keep
the cipher group folded. Temporary workspace edits do not overwrite defaults.

Public cover, secret text and encoded-message fields support long-press source
selection. Private-vault paste retains authentication, foreground confirmation,
draft clearing and one-use return into a new matching editor-bound workspace;
secret text returns masked. System paste defaults off and requires explicit
opt-in. Keys/passphrases cannot be pasted; source changes revoke pending work.

Real-Rime x86_64 Debug/test builds, 613 scoped app/UI unit passes (two opt-in
provider skips), privacy checks and zero-issue Debug Lint pass. Final API 37
regression and isolated source/default-off checks pass 29 distinct platform
cases plus five landscape/dark/large-font repeats. The full system rejection
matrix conservatively skips on fixture-ownership metadata and is not a pass.
Main merged-manifest boundaries and installed APK hashes match. See the
[validation record](audits/2026-11-19-stego-defaults-paste.md).
Next: physical ARM/OEM and older-Android acceptance. No Release/publication was
performed; the 15:05 ARM package below predates this change.

Repackaged the defaults/paste changes at 2026-11-19 16:04 China time using
`./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000`, without
SkipChecks. The [current ARM64 Debug test APK](../app/build/outputs/test-apk/20261119-160438/zero-fish-input-0.7.7-debug-arm64-v8a.apk)
is 96,406,675 bytes, SHA-256
`9a8b776091bcf91635cb67bee6149ea033285eb1aa9383de97c61570202ba188`.
Full Debug/Release unit reports contain 2,278 passes and four opt-in provider
skips, no failures/errors (including cached/up-to-date suites). Privacy checks,
zero-issue Debug Lint, real-Rime ARM64 build, resource/signature/ABI/permission
checks and all three distribution checksums pass. The 180 MB budget passes;
the separate 80 MB target remains exceeded. The new Debug-signed package has not
been installed on a physical device. Evidence: `.tmp-evidence/stego-defaults-repackage.log`
and `.tmp-evidence/stego-defaults-repackage-verification.json`.

## Stego panel presentation and interaction (2026-11-19)

The panel now uses full-width labelled draft fields, a current-field outline and
editing marker, field-specific hints and long-text expansion. Cipher selection
starts folded with an ordered summary; secondary actions/settings fold separately.
Mode/clear and execution rows remain fixed; the viewport-constrained portrait
detail request is 320 dp. Field switching scrolls to the active field. Existing
masking, focus-free input and all source/crypto/lifecycle boundaries are preserved.

Real-Rime x86_64 Debug/test builds, 606 scoped app/UI unit passes (two opt-in
provider skips), privacy checks and zero-issue Debug Lint pass. API 37 passes 14
distinct platform tests, plus eight repeated layout/caret executions across small
dark/large-font and landscape/light configurations. See the
[change and validation record](audits/2026-11-19-stego-panel-ui.md).
Next: physical ARM/OEM and older-API acceptance. No Release or publication was
performed for this UI change; the audit work below remains preserved.

Repackaged after the UI change at 2026-11-19 15:05 China time with
`./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000` (no SkipChecks).
The current [ARM64 Debug test APK](../app/build/outputs/test-apk/20261119-150532/zero-fish-input-0.7.7-debug-arm64-v8a.apk)
is 96,383,639 bytes, SHA-256
`865c993b334fa791f9d243c02bab23f6c11892da7bfd56f14f68969739de2d1a`.
Full Debug/Release unit reports contain 2,264 passes and four opt-in provider
skips, zero failures/errors (including up-to-date/cached suites). Privacy, zero-issue
Debug Lint, real-Rime ARM64 build, full resource/signature/ABI/permission checks
and all three distribution checksums pass. The 180 MB budget passes; the separate
80 MB target remains exceeded. This is Debug-signed, not a production Release;
the new ARM64 artifact was not installed on a physical device.

The user requested historical package cleanup. After validating the new delivery,
ten old distribution directories and 66 old package/archive/metadata files were
removed (1,868,635,775 bytes); Debug packaging reset separately replaced five
historical main APKs. Only the latest ARM64 source APK and distribution copy remain.
Old provenance/checksum text and the path/hash cleanup manifest are preserved in
`.tmp-evidence/stego-panel-package-cleanup.json`; build evidence is
`.tmp-evidence/stego-panel-repackage.log`. Source changes, native caches, validation
records and remote release assets were retained. Historical local APK links below
may now be unavailable; their recorded hashes remain historical evidence.

## Current code audit (2026-11-19)

All three defects from the audit of `33ff309` are repaired: rejected engine commits
retain a session-local pending result with explicit retry/cancel; expression export
waits for foreground task availability; stego preedit and caret use one projection.
The user approved the pending-commit recovery policy. See the original
[report](audits/2026-11-19-current-code-audit.md) and
[repair/validation record](audits/2026-11-19-current-code-audit-fixes.md).

Current Debug/JVM results contain 1,177 passes and two opt-in live-provider skips,
with cached/up-to-date tasks noted. Privacy, zero-issue Debug Lint, real-Rime x86_64
Debug/instrumentation builds and main merged-manifest checks pass. API 37 passes
30 distinct platform cases, including actual English/native-Rime refusal recovery,
plus four repeated stego touch cases across small/dark/large-font and
landscape/light configurations. Installed APK hashes match; display settings were
restored and the task-owned emulator stopped. No Release or three-ABI package was
produced. Next: physical ARM/OEM, older Android and real DocumentsUI acceptance.

## v0.7.7 published (2026-11-19)

README and release notes describe the current stego workspace, full private-draft
masking and verified audit fixes. Version is 0.7.7, code 14. The public package
remains a Debug-signed testing prerelease; no production signing key was created.
See the [release notes](releases/v0.7.7.md) and
[validation record](releases/v0.7.7-validation.md).

Full Debug/Release tests pass: 1,163 distinct unit cases, two opt-in live-provider
skips, no failures. Privacy gates, both Lint variants (zero issues), three real-Rime
ABIs, unsigned Release artifacts and all 32 bundled notices pass verification.
The exact universal distribution APK upgraded official v0.7.6 in place and passed
103 distinct API 37 x86_64 platform cases, plus four repeated layout executions
across small/dark/large-font and landscape/light configurations. An intermittent
system-menu touch timeout in a combined sequence is retained in the validation
record; its isolated recheck passed without changing production code/assertions.

The universal APK is 108,003,899 bytes (below the 180 MB budget); ARM64 Debug
remains above the separate 80 MB target. Installed APK hashes match the artifacts.
Temporary PIN and device settings were restored and the task-owned emulator was
stopped. [v0.7.7](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.7)
was published at 2026-11-19 04:52:01 UTC as a public testing prerelease. Its
annotated tag resolves to `85b9a348268b8ec7f97fa52bbecb10dca6604de2`.
All four public assets match local sizes and GitHub SHA-256 digests; the downloaded
public checksum list also matches. README, source and release tag are pushed.
Next: physical ARM/OEM, older Android and opt-in live-provider acceptance.
Earlier entries below are historical.

## Full code review verification and fixes (2026-11-19)

The pre-fix workspace is committed as `0c1c433` and retained by the local
`zn/backup-full-review-20261119` branch. The user's ten-item review was verified
against the existing contracts and regression cases; see the
[item-by-item result](audits/2026-11-19-full-code-review-fixes.md).

Missing device-agent categories now grant none, while explicit selections are
preserved, as confirmed by the user. Personal-data refresh preserves a valid
model winner and its native route until personal priority or another existing
revocation boundary supersedes it. Swipe parsing and incomplete history appends
were hardened. Six other reported failures have no supported reproduction under
the current ownership and data contracts; no speculative behavior changes were
made for them.

Full Debug/native x86_64 build, instrumentation build, 1,163 unit passes (two
opt-in live-provider skips), privacyCheck/testPrivacyBoundary, both merged
manifests and Debug lint pass. API 37 x86_64 platform validation passes all 25
selected cases, including the audit examples and actual category-settings page.
No new ARM or Release APK was produced. Next: physical ARM/OEM and older-API
acceptance, plus opt-in live-provider checks when test credentials are available.
Commands and validation scope are in the linked record; earlier entries below
are historical.

## Stego paste, custom master material and compact layout (2026-11-19)

The latest request adds a COVER/CARRIER long-press source menu. System Paste reads
one bounded plain-text item only on explicit selection; vault Paste keeps system
authentication and foreground confirmation, wipes original drafts and returns to
the matching editor's newly opened isolated workspace. A lifecycle self-review
fixed premature cancellation during the first IME return; continued input or
subsequent leave still revokes the buffer. Default private input cannot preempt it.

Random master material supports 256/512/1024/4096 presets and byte-aligned custom
256..4096-bit lengths. Cipher subkeys remain at their supported widths; longer
material is not a higher cipher-strength claim. Text, credential and cipher
controls share one page; primary actions stay fixed under the scroll area. Full
reveal expands long credentials within that area. Compact 13 sp, zero-tracking
buttons retain 48 dp targets. See the [self-review](audits/2026-11-19-stego-paste-keys-layout.md).

Final acceptance passes 36 stego platform tests, three system-paste/masking tests,
and both two-test small-dark/large-font and landscape-light layout probes, with no
failures or skips. Full packaging reports 2,218 unit passes, four opt-in AI skips,
zero failures/errors; privacyCheck/testPrivacyBoundary, Lint (zero issues), real
Rime builds, Debug/Release manifest checks and artifact validations pass.

The current [ARM64 Debug test package](../app/build/outputs/test-apk/20261119-112304/zero-fish-input-0.7.6-debug-arm64-v8a.apk)
is 96,363,403 bytes, SHA-256
`f4b1899792921c1c1ee587826f78c9c417be547731966932b22d7f1fc80176a3`.
It meets the 180 MB budget, exceeds the 80 MB target, and is not a signed Release.
Temporary PIN/display/font/rotation/night/IME changes were restored. OEM/ARM64
and older-API runtime acceptance remain. See the
[validation record](text-steganography-validation.md#paste-custom-master-material-and-compact-layout-2026-11-19).
Earlier entries are historical.

## Steganography usability repair (2026-11-19)

The reported unusable flow is repaired: Encrypt and insert automatically fills the
original bound editor after self-check; Generate only retains preview and the four
authenticated private saves. All relevant fields share a bounded scrollable page.
Six inline cipher checkboxes use selection order, valid widths and direct earlier
moves; optional modes/KDF/transport settings are secondary. Private temporary
input now masks every committed character until explicit reveal.

The [self-audit](audits/2026-11-19-stego-usability.md) preceded platform acceptance;
it added workspace-generation guards to reject callbacks from a closed/reopened
workspace. The final API 37 x86_64 suite passes all 55 tests with no failures/skips,
including real touch insertion, selection revocation, authenticated saves, short
masking and existing native/caret regression. Both 240x480 dark/1.3-font and
640x320 light/landscape attached layout probes pass. Full packaging checks report
2,212 unit-test passes, four opt-in AI skips, no failures/errors; privacyCheck,
Debug Lint, real-Rime builds and APK artifact checks pass.

The repaired [ARM64 Debug test package](../app/build/outputs/test-apk/20261119-101438/zero-fish-input-0.7.6-debug-arm64-v8a.apk)
is 96,348,751 bytes, SHA-256
`8258e194e8a9dee91fe242625767749834b48719078b029c7da53340863c6132`.
It meets the 180 MB budget and still exceeds the 80 MB target. Test PIN and display,
font, rotation, night and IME settings were restored; the task-owned emulator was
stopped. Next: OEM/ARM64 and older-API runtime acceptance. See the
[validation record](text-steganography-validation.md#single-page-repair-and-automatic-encoded-insertion-2026-11-19).
Earlier records below are historical.

## Text steganography usability completion (2026-11-18)

The user approved the proposed four-content definition and authentication flow.
The toolbar now places `隐` immediately after shape lookup, with More after all
common tools (including selection copy). Next-field navigation and direct ordered
cipher editing simplify the path to generation and one-tap carrier insertion.
One to six distinct ciphers can be added, removed, reordered or assigned valid
key widths continuously, with one/two/six-layer presets.

Encryption and authenticated decryption expose separate private-save entries for
secret plaintext, exact public cover, detached ciphertext and the full carrier.
Noncredential drafts can also be saved. Every save consumes the workspace and
uses the existing system authentication plus foreground confirmation. Detached
`[ZIS1:...]` ciphertext requires the exact original cover for decryption. No vault
format, encrypted envelope, key alias, permission, clipboard or network boundary
changed. External-engine warmup adoption is deferred while stego is open so it
cannot revoke an otherwise valid prepared commit.

The new ARM64 Debug test package is
[zero-fish-input-0.7.6-debug-arm64-v8a.apk](../app/build/outputs/test-apk/20261118-224348/zero-fish-input-0.7.6-debug-arm64-v8a.apk),
96,338,823 bytes, SHA-256
`ac86cc4747ac07a875e0619435d38354451fd9bcae13b3b839a6e80229c7f6fb`.
The standard packaging script ran without SkipChecks: 2,208 unit-test passes,
four opt-in AI skips, no failures/errors; privacyCheck, Debug Lint, real-Rime build,
signature/ABI/permission/resource checks passed. The 180 MB budget is met and the
80 MB target remains exceeded. It has not been installed on an ARM64 device.
The [validation record](text-steganography-validation.md#usability-completion-2026-11-18)
records current device evidence and remaining platform limits.

The final API 37 x86_64 platform suite passes all 28 tests with no failures or
skips, including system-authenticated save and lifetime checks. The PIN harness
uses the system confirmation control and waits for window dismissal; production
authentication and timeout boundaries are unchanged.

Next: perform OEM/ARM64 and older-API runtime acceptance. Current commands and raw
local evidence are in the validation record; earlier delivery records below are
historical.

## Previous ARM64 test delivery (2026-11-18)

The checked Debug test package includes both refactor repair passes:
[zero-fish-input-0.7.6-debug-arm64-v8a.apk](../app/build/outputs/test-apk/20261118-205059/zero-fish-input-0.7.6-debug-arm64-v8a.apk).
Application ID: `dev.zeroinput.ime.debug`; size: 96,327,119 bytes (96.327 MB).
SHA-256: `a61b6292ce5902ab2e81d0c1d710e945216c327b816372febe0571bee8b2495f`.
The standard ARM64 script passed without `-SkipChecks`: full unit tests (2,200
passes, four credential-dependent skips, no failures/errors), privacyCheck and
Debug Lint (zero issues), real-Rime build, signature/ABI/permission/installability,
resource and size checks. All three distribution checksums match; the APK also
matches the current Gradle output and provenance. The 180 MB budget is met;
the separate 80 MB target is exceeded. Notices, corresponding sources, size,
provenance and checksum records accompany the APK. This package was not installed;
the device rerun limitation below remains.

Two attempts were interrupted by a Gradle daemon stop command. The successful
run used an isolated local Gradle user home with shared dependency caches and
the existing JDK paths; project build configuration was unchanged. Evidence:
`.tmp-evidence/refactor-arm64-package-final.log`.
Requested removal of three superseded test deliveries and four stale APK copies
was rejected by automatic approval (`blocked by policy`); no old package was
deleted. Next: complete that cleanup when deletion is permitted, and perform
ARM64/OEM runtime acceptance.

## Second refactor self-check (2026-11-18)

The second pass reproduced and repaired two residual gaps: native LinkageError
handling was missing from engine reset/startup/prepared disposal, and replayed
handoff tokens could discard a newer save draft. Engine-specific recovery now
preserves accepted commits without command replay; cancellation/interruption and
other fatal errors still propagate. Token mismatch only rejects the old caller;
matching-token expiry/deletion-generation failure still clears the owned draft.
Six engine lifecycle and two handoff regressions cover the changes. Details and
verification are in the [self-check disposition](audits/2026-11-18-refactor-fixes.md#second-self-check-2026-11-18).
Selected suites now report 1,145 passes, two opt-in live-AI skips and no failures.
privacyCheck/testPrivacyBoundary, real-Rime x86_64 Debug/AndroidTest/unsigned
Release builds and Debug/Release Lint (zero issues) pass. Updated artifact
identities are in the disposition; first-pass hashes below are historical.

This pass's device rerun is blocked: two starts of the same task-owned AVD fail
with WHPX partition setup error 0x80070005, even though accel-check reports the
installed hypervisor usable. Both emulator processes exited, no device became
available and no PIN or device setting was changed. The 35 platform passes below
belong to the preceding source state, not these additional fixes. Recheck on
API 37/OEM hardware when the local virtualization environment is available.

## Refactor review follow-up (2026-11-18)

Goal: verify and address the November 18 refactor review after a local baseline
backup. Backup commit: `2c86668`. The approved choices are implemented: distinguish
pending answers from unproven dictionary edits, preserve Space as raw input plus
a space when edits are unproven, separate the 120-second draft and 30-second
authenticated confirmation budgets, and limit fatal engine fallback to LinkageError.
Sixteen findings are addressed; D-5 is withdrawn because the rejected-clear
synchronous completion path really returns null. No persisted format or approved
privacy boundary changed. The baseline commit remains separate from these fixes.

Current Debug and pure JVM suites report 1,137 passes, two opt-in live-AI skips
and no failures. privacyCheck, its negative self-tests, Debug/Release Lint (zero
issues), real-Rime x86_64 Debug/AndroidTest and unsigned Release builds pass.
API 37 device validation passes 22 steganography and 13 import/language-pack
tests, plus 240x480-dp dark and 640x320-dp light layout probes. Test-only selection
acknowledgement and oversized-value expectations were corrected after actual
device failures; no production invalidation or parser safety checks were weakened.
The installed Debug hash was checked before/after; the public PIN and display/IME
settings were restored and the task-started emulator stopped.

Next: review the local changes and perform OEM/ARM64 and older-API acceptance.
The current ARM64 package is recorded above; native-symbol fault injection
remains unverified. The prior ARM64 delivery below predates these fixes.
Commands, per-finding evidence and current artifact hashes:
[refactor disposition](audits/2026-11-18-refactor-fixes.md) and
[device addendum](text-steganography-validation.md#refactor-review-follow-up-2026-11-18).

## Text steganography implementation (2026-11-18; local validation complete)

Implemented the six-cipher portable envelope, ordered authenticated cascades,
valid selectable widths, one master credential, five factor modes and bounded key
files; isolated compose/credential/preview/commit and masked local decryption;
selection/share/PROCESS_TEXT and authenticated vault coordination. The user
explicitly declined system-clipboard body reads. No permission/export/network/AI
exception or existing storage-format migration was added.

Pinned Bouncy Castle 1.87/Tink Android 1.21.0 and license/hash review are recorded
in [ADR 0039](adr/0039-text-steganography.md). Independent RustCrypto vectors cover
12 cipher-width modes, all three Argon2 profiles, all five factor combinations and
complete one/six-layer envelopes. Published vectors, all 720 algorithm orders,
mixed widths and damaged inner tags pass. Final JVM results contain 2,166 passes,
four opt-in live-AI credential skips and no failures. privacyCheck and
Debug/Release Lint pass with no issues. Real-Rime x86_64 Debug/AndroidTest and
unsigned R8 Release builds pass; the merged manifests preserve permission,
backup, export and Release-debugging boundaries.

All 17 API 37 steganography device tests pass without skips, including actual
system-PIN authentication for private-vault read and save, fresh confirmations,
cancellation, settings/deletion revocation and late-worker results. Selection
preservation and private-candidate clearing were reproduced before repair and
verified afterward. Small/normal portrait/landscape and light/dark layout checks
pass, including 48-dp touch targets and scrolling. The standard 64-MiB Argon2id
six-layer/self-check probe took 1,014 ms, with a maximum 7-ms main-looper probe
delay; this is not a full input-latency benchmark. Device overrides and the public
test PIN were cleared, and the task-started emulator was stopped.

The historical checked ARM64 package, without `-SkipChecks`, was
[zero-fish-input-0.7.6-debug-arm64-v8a.apk](../app/build/outputs/test-apk/20261118-174651/zero-fish-input-0.7.6-debug-arm64-v8a.apk).
It is 96,317,775 bytes: below the 180 MB budget and above the separate 80 MB target.
SHA-256: `50f11f0937687e3786c27e996b946a9f938f6f931f61243d7ed3f077029ed25c`.
Notices, corresponding sources, provenance, size and checksum records are alongside
it. This package is superseded by the current ARM64 test delivery above.

Natural wording remains unavailable under the design's resource, capacity,
semantic and detector gates; no enhanced profile is claimed. API 26/29/33,
OEM/ARM64 runtime and actual external-channel round trips remain unverified.
No release signing, Git commit or publication was performed. Existing audit
changes below remain preserved. Details and manual destination acceptance:
[validation record](text-steganography-validation.md).

## Final audit re-verification (2026-11-18)

Rechecked the updated November 17 audit against current code and corrected its
remaining classifications. Fixed two confirmed residual issues: owned CMake,
host-script and catalog privacy-gate coverage, and JSON dictionary full-tree
allocation. A 14,000,001-byte repeated-record file failed with OOM before the
streaming repair and loaded successfully afterward under the same 128 MiB JVM
heap. Added explicit token/key budgets, real-hash negative tests, and the public
asynchronous-clear contract. Original audit now records 17 confirmed findings
fixed and three withdrawn; M-7/L-2 each include one withdrawn subfinding.

Independent full verification passed: 2,122 JVM variant executions, four
credential-dependent skips, privacyCheck and Debug/Release Lint with no issues.
Real Rime Debug/unsigned Release builds cover all three ABIs. All six APKs passed
resource/native contents checks; Debug splits passed package/ABI/signature
checks. The exact installed x86_64 APK passed 52 API 37 tests with no skips,
including nine language-pack platform cases. Installed hashes were verified
before and after. JNI failure injection passed; the task-started isolated
emulator was stopped with its data retained.

Current build outputs are in `app/build/outputs/apk/debug/` and `release/`.
The cleanup/test-package entry below describes its earlier source state: that
ARM64 delivery package predates this streaming-parser repair. No commit or
publication was performed. Full evidence and runtime limits are in the
[updated audit](audits/2026-11-17-code-audit.md#9-最终再核验2026-11-18).

## Build cleanup and fresh test package (2026-11-18)

Removed 23 generated build/distribution directories and 101 expired APKs,
reclaiming 16.50 GiB before rebuilding. Module outputs, native intermediates and
old delivery packages were cleared. Pinned dictionaries/models, local Maven
inputs, third-party sources, emulator data and review evidence were retained;
historical package provenance and Lint metadata were saved before removal.
Only the new ARM64 APK and its identical Gradle output remain in the repository.
Product sources, resources and build configuration match the pre-cleanup hashes;
a concurrent update to the original audit document was preserved.

Rebuilt with the normal checked packaging command:

```powershell
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

The checks passed: 90 pure JVM tests and 1,012 passes plus two opt-in skips per
Android variant; privacyCheck; Debug Lint with no issues; fresh three-ABI native
Debug builds; packaged resources, signature, permissions, ABI, installability,
ZIP integrity and checksum verification. Gradle reused input-matched caches for
some unchanged JVM/Lint tasks. This packaging run did not perform device tests.

New full Debug test package, version `0.7.6-debug`:
`app/build/outputs/test-apk/20261118-123028/zero-fish-input-0.7.6-debug-arm64-v8a.apk`.
92,460,582 bytes; within the 180 MB packaging budget and above the separate 80 MB
optimization target. SHA-256:
`e2be32ed917eb4c881d05f45904c1db2f1ab8e0d02fbb204a51b06ec0401eb6b`.
Notices, handwriting corresponding sources, size/provenance JSON and
`SHA256SUMS.txt` are alongside it. Cleanup inventory, package log and verification
results are in `.tmp-evidence/repackage-20261118/`. Older artifact paths below
are historical records; the superseded local packages have been removed.

Next: install and verify this ARM64 test package on a suitable physical device.
No release signing, version bump, commit or publication was performed.

## First audit follow-up (2026-11-18; historical)

Verified all 20 findings in the user-rewritten November 17 audit and repaired the
confirmed input, deletion, transport, observer, parser and candidate-routing
issues. Retained the approved legacy AI reset, clipboard grant consumption and
draft authorization checks; documented partial findings and false positives.
The original audit content was preserved in that first run and has since been
updated by the user-authorized final re-verification above. No commit or
publication was performed in that run.

Validation: 2,114 JVM variant executions passed with four credential-dependent
skips; privacyCheck and Debug/Release Lint passed with no issues. All three ABIs
built with real Rime and passed Debug/unsigned Release APK contents checks.
The exact installed x86_64 APK passed 50 API 37 tests without skips, with hashes
verified before and after execution. JNI fault injection and public-fixture
small-screen/theme/landscape checks passed. The isolated emulator was stopped.

Details, commands, test groups and limits:
[follow-up verification](audits/2026-11-18-followup-verification.md).

Next: physical ARM/OEM and Android 8 timing checks, live provider transport
validation and device heap profiling when suitable environments are available.
These remain unverified; no production signing key was used.

## Re-review and v0.7.6 publication (2026-11-18)

Reviewed the pending audit repairs and fixed two additional AI configuration
defects: equal reloads replacing request snapshot identity, and failed settings
saves permitting empty provider replacement or preventing editing recovery.
Recovery retains durable providers with activation off and never republishes
transport authorization. Negative and actual queue-rejection regressions pass.

Validation is complete: 2,087 JVM passes and four opt-in skips across variants,
privacyCheck, Debug/Release Lint, three-ABI native/Release builds, 34 Python tests,
JNI failure injection and packaging/script checks. The final universal APK
passed 60 API 37 x86_64 device tests with no skips and verified installed hashes,
after upgrading from the actual published v0.7.5 APK with the same certificate.

Details, commands, hashes and limits: [v0.7.6 validation](releases/v0.7.6-validation.md).
Published [v0.7.6](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.6)
from source commit `35ea1ae` and its annotated tag. All four public assets (Debug
APK, notices, handwriting sources and checksum manifest) have verified sizes and
server SHA-256 digests matching local files. No production key was used. The
isolated emulator started for this review was stopped.

Next: physical ARM/OEM and Android 8 execution when devices are available; these
remain unverified. The 80 MB ARM64 optimization target is still exceeded.

## Audit repair and packaging (2026-11-18)

Completed the 13 retained findings from the reviewed November 17 audit and
re-reviewed the affected paths. Details, evidence, commands and residual limits:
[repair verification](audits/2026-11-18-fix-verification.md).

- Full JVM tests: 87 pure JVM cases; Android Debug/Release each 997 passed,
  2 opt-in skips, no failures/errors. Privacy and Lint passed, no Lint issues.
- 16 Python resource tests and packaging locator/reset tests passed. Original
  8 MB line probe is bounded under 128 MB heap. JNI failure injection passed.
- Final universal package: 22 API 37 x86_64 device tests passed, no skips, with
  installed APK hashes checked. Native shared contract and small/rotated/themed
  layouts included. Physical ARM and Android 8 execution remain unverified.
- Rebuilt all three Debug/Release native ABIs; full APK content checks passed.
  Release splits are unsigned. No commit, push or publication was performed.

Installable Debug test artifact:
`app/build/outputs/test-apk/20261118-090141/zero-fish-input-0.7.5-debug-universal.apk`.
104,087,362 bytes; within 180 MB budget and above the 80 MB target. SHA-256:
`6f870e7b4d4bf3afd4aeda0e3e27933e42596aa220aab2add833a14e38de0d7c`.
Notices, sources, checksums and verification JSON are alongside it.

Next: physical ARM/OEM and Android 8 timing acceptance when devices are available.
No unresolved confirmed defect remains in the affected-path review; documented
platform races, JVM String erasure limits and unverified scenarios remain.


## Public catalog and remote branches (2026-11-13)

- QQ and Sogou now surface downloadable dictionaries on their home and category
  pages. Category, sorting and upstream page links have their own view. Duplicate
  dictionary links are consolidated by source ID and returning restores the
  previous catalog snapshot.
- The reviewed November 9 Wanxiang update reached main through PR #1. Its pinned
  upstream grammar asset now returns HTTP 404 and the corresponding optional
  resource was never published. Commit `4e22558` restores the v0.7.3 lock,
  matching native assets and published optional resource manifest.
- The November 12 audit branch was merged into main. The pre-device-agent
  checkpoint and weekly update branch are reachable from main. The private-copy
  prototype has two experimental commits preserved in the local branch and a
  verified Git bundle. The weekly workflow reports scheduled results in GitHub
  Actions. A manual run can create a temporary branch for a review PR.

## Keyboard copy, shape lookup and appearance (2026-11-13)

Shape lookup replaces copy selection in the primary toolbar. Copy selection is
the first action in More tools and is also available from the private clipboard
panel. The keyboard reviews the selection length, invokes system authentication,
then asks for a fresh Save tap in the returned editor. The previous private
import Activity and token handoff have been removed from the keyboard route.

The Mint and Rose candidate accents match their respective palettes in light and
dark mode. Background settings support custom RGB colors with a swatch and hex
value. The picker saves only on confirmation and uses the existing preference.

Android's standard EditText fixture exposes Copy to ZeroInput after full
selection when it supports PROCESS_TEXT. Android and source apps control external
selection menu placement and can omit external actions altogether. The user's
vendor-specific reply/SMS menu still requires physical device confirmation.

Validation command:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  privacyCheck :app:lintDebug :app:processDebugMainManifest :app:processReleaseMainManifest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

The Debug verification passed with 967 JVM tests and two opt-in skips, and Lint
reported no issues. An isolated API 37 x86_64 emulator passed 26 tests with
verified app and test APK identities. Coverage included device PIN authentication,
save and paste confirmations, editor selection changes, cancel, native editable
and read-only selection menus, sharing, toolbar ordering, shape lookup, light
and dark palettes and custom color persistence. Evidence is in
`.tmp-evidence/keyboard-copy-final-checks.log` and
`.tmp-evidence/keyboard-copy-device-acceptance.log`.

Four landscape cases passed, including preview geometry and saving/cancelling
a custom color. The geometry test uses the preview viewport instead of assuming
the system rotation determines the key height. Final evidence:
`.tmp-evidence/keyboard-copy-landscape-acceptance.log`.

`./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000` passed
full unit, privacy and Lint checks plus APK signature, ABI, permissions and
contents validation. The Debug package is 92,425,098 bytes, within the test
budget and above the 80 MB target. Artifact:
`app/build/outputs/test-apk/20261113-123155/zero-fish-input-0.7.3-debug-arm64-v8a.apk`.
SHA-256: `4b1509ac4340e2463eab252c042c7dd334f55e6a4797a54b2181c6fe8743ea6e`.
Physical ARM64 execution, OEM selection menus, biometrics and Release APK
packaging were not verified in this pass.

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

[v0.7.5](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.5) is the
current Debug-signed testing prerelease. See [release notes](releases/v0.7.5.md)
and [release validation](releases/v0.7.5-validation.md).

## November 13 code review follow-up

Reviewed all 41 findings against `64fc1d0`. Fixed 37 findings across input
routing, engine recovery, candidate ranking, settings lifecycle, public resource
storage and AI. Leaving the dictionary page still cancels work as specified in
[ADR 0020](adr/0020-public-dictionaries.md). The shared Rime worker contract is
documented at dictionary preparation. The pinned librime menu and spelling
lookup share the same filtered candidate indices. Kotlin's parameterless
`lowercase()` uses the invariant locale.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-english:test :engine-api:test :engine-dictionary:test :ai-api:test `
  privacyCheck :app:lintDebug :app:processReleaseMainManifest `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

The Debug APK and test APK built with native Rime. The JVM reports contain
1,071 passes and two opt-in skips. Debug Lint and the Debug/Release merged
manifest privacy checks passed. Two API 37 x86_64 emulators passed 44 checks
with one opt-in public dictionary skip. Device coverage included hardware key
routing, the last primary Rime page, registry rename failure and recovery,
settings layouts, and a screen-off during a blocked authenticated paste.
Evidence and per-finding dispositions are in `.tmp-evidence/`.

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
