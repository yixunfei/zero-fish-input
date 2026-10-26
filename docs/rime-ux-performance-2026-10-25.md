# Rime readiness and UX performance assessment

Date: 2026-10-25

## Outcome

Default prebuilt deployment reduces the measured first-start wait on the API 36
x86_64 userdebug emulator. Three isolated fresh-default cycles completed in
11.511, 12.778 and 12.174 seconds; their median was 12.174 seconds versus the
measured 71.046-second source-compilation baseline. This is 58.872 seconds
(82.9%) below that baseline and remains below the unchanged 60-second budget.
All cycles verified the expected generation, shared prebuilt table, absence of a
default user-compiled table, all 16 source tables and real `nihao` -> `你好`
conversion. Final clean APK `42c05e4b…` repeated the three fresh fixtures in
9.354, 7.792 and 7.996 seconds (median 7.996), with reuse in 0.814, 0.890 and
0.847 seconds. After bounded archive-cache correction, APK `7d06cb28…` measured
7.493, 8.086 and 7.257 seconds cold and 0.868, 0.864 and 0.782 seconds warm.
Each interval precedes fixture restoration; the three timing groups are
reported separately rather than selecting only the fastest result.
This is not a physical-device performance or release-readiness claim.

The accepted implementation retains source dictionaries and controlled native
compilation. The default artifacts are a source-bound build cache, not another
engine or dictionary authority. Native maintenance, session creation and
conversion still precede `READY`; fallback, privacy, session ownership and the
bounded single engine worker are unchanged.

## What was fixed

1. The generator now reproduces Android's source identity, normalized schema
   JSON and generation-specific primary dictionary header. Librime checksums
   these bytes; a generic host build is not necessarily compatible.
2. `RimeAssetInstaller` verifies the normalized staging inputs and fixed
   prebuilt artifacts using bounded XZ decoding, archive/original hashes,
   formats and unpublished activation. It does not compare the original base
   schema against its grammar-normalized staging counterpart.
3. `lastVerified()` applies default-prebuilt matching only to the default
   generation. Changed/custom dictionaries keep their independently compiled
   generation through close and recovery.
4. Packaged archives are no longer copied into app-owned base/shared caches;
   old copies are reclaimed only after conversion verification and without
   entering user generations.
5. Device regressions now cover cold/warm deployment, corruption, changed source
   compilation and recovery, failed verification, close/last-session release,
   cancellation, first-frame fallback and archive/user cache boundaries.

Production `RimeRuntime` ownership was not rewritten. The focused close/release,
in-flight close, queued cancellation, failure and retry tests passed against its
existing generation/finalize-when-idle behavior. No previously unproven lifecycle
race is asserted to be fixed.

## Measurement scope

Environment: `emulator-5554`, API 36, x86_64 `userdebug`, no optional grammar in
the default generation. Timings are deployment + native initialization +
conversion verification on the existing worker, not wall-clock application
launch. Fixed public test words only; no personal dictionaries or editor text
were read. Measurement samples are small and emulator scheduling varies.

| Path | Result | Interpretation |
| --- | ---: | --- |
| Baseline fresh default | 71,046 ms | Source compilation exceeded 60 s. |
| Prebuilt fresh default | 11,511 / 12,778 / 12,174 ms | Median 12,174 ms; all three below budget. |
| Baseline same-generation reuse | 1,243 ms | One baseline observation. |
| Prebuilt same-generation reuse | 2,289 / 1,619 / 3,172 ms | Median 2,289 ms; final APK: 868 / 864 / 782 ms. |
| Clean-APK fresh/reuse | cold 9,354 / 7,792 / 7,996 ms; warm 814 / 890 / 847 ms | APK `42c05e4b…`, before bounded archive-cache correction. |
| Final runtime/cache fresh/reuse | cold 7,493 / 8,086 / 7,257 ms; warm 868 / 864 / 782 ms | APK `7d06cb28…`; final regression, not a causal cache-timing comparison. |
| Full-pinyin native keys | p50 767 us, p95 6,927 us | Final APK, 1,140 samples; old 928/7,865 us. |
| Strict-pinyin native keys | p50 419 us, p95 905 us | Final APK, 1,140 samples; old 456/1,345 us. |
| Candidate layout | p50 119 us, p95 228 us | Final APK; old 99/256 us; separate runs, no causal claim. |
| Fallback creation | 1 ms | Final-code in-memory first-frame creation, not UI pixel latency. |
| Fallback five-key sequence | total 1,269 us; max 1,006 us | Final-code public `nihao`; no controlled cold-process comparison. |
| Real-window dispatch | p50 8,502 us, p95 55,324 us | Final code; includes real IME/UI scheduling. |
| Real-window editor update | p50 4,643 us, p95 52,749 us | Final code, public fixed editor fixture. |
| Real-window frame callback | p50 12,647 us, p95 55,617 us | Final code, one API 36 run; not a physical frame-rate claim. |

## Package tradeoff

The three raw generated binaries total 74,465,776 bytes. Their XZ assets plus
manifest occupy 25,546,614 bytes inside the APK (25,545,760 compressed bytes,
including ZIP framing differences). Clean, otherwise identical x86_64 debug
packaging measured 65,341,387 bytes without prebuilt assets and 90,887,825 bytes
with them: a real increase of 25,546,438 bytes (24.36 MiB). This comparison was
made before the bounded cache-source correction; that correction does not alter
the packaged assets. An earlier incremental
ZIP comparison reported only 370 bytes because removing entries did not reclaim
their physical payload space; it is excluded. The original 70,601,147-byte source
tables remain present. The measured cold-start benefit justifies retaining this
cache for the full default package, while source compilation remains available
for custom generations; no warm-start improvement percentage is claimed.

The initial implementation also copied the packaged archives into app-owned
base and shared caches. Public runtime inspection found 24,976 KiB in each copy.
New deployments now skip those redundant copies; after successful conversion,
canonical app-owned base/shared copies from older builds are reclaimed without
entering user generations. This avoids/reclaims approximately 51.1 MB of raw
archive duplication (49,952 KiB allocated in the observed pair), while retaining
all source tables and the APK cache. It is a cache-ownership correction, not a
claim that the total installed footprint is smaller than the pre-change engine.

## User-facing behavior and recovery

- Final-code `ChineseInputAvailabilityTest`: 4/4 passed in 10.214 s. Preparation
  status remains visible while native work is delayed; public fallback can
  convert and select `你好`. Tools-panel round trips and later native adoption
  passed. APK `c484e01d…`; fallback timings are process-state dependent.
- Final-code `InputPipelineTest`: 2/2 passed in 12.555 s, including real-editor
  key dispatch, raw Enter, language/number handoff and nine-key conversion.
- Final-code `InputLatencyTest`: 3/3 passed in 6.801 s with the metrics above;
  these are separate-run measurements, not a controlled before/after UI study.
- `AiImeInteractionTest`: 5/5 passed on final APK `7d06cb28…` in 36.967 s with
  its unchanged 60,000 ms readiness assertion; the delayed draft worker
  preserves the host editor and converts fixed public Chinese input.
- Final runtime/cache APK `7d06cb28…`: `CompressedDeploymentTest` passed 4/4
  (100.69 s) and `RimeReadinessTest` passed 5/5 (93.869 s). This includes old
  base/shared archive reclamation, a preserved user-generation boundary marker,
  corruption recovery, changed-source compilation and close/retry conversion.
  Actual application base/shared directories were inspected and contain no
  `prebuilt` copy after the suite. Earlier APK `42c05e4b…` passed the same
  deployment suite before this bounded cache correction.

These checks establish the tested emulator conditions, not full visual,
accessibility, user-research or ARM-device validation. No UI redesign or new
latency budget is introduced by this backend fix.

## Performance priorities after this change

Cold deployment was the demonstrated dominant cost and is now below the tested
budget. Native per-key work remains small relative to real-window dispatch and
frame tails, so speculative native-loop optimization is not justified by these
measurements. A later profiling slice should identify IME callbacks, candidate
updates and rendering costs during real-window p95 tails before changing them.
Candidate-layout p95 also needs repeated comparable runs rather than a causal
claim from two different sessions. Optional grammar and changed dictionaries
still require full native compilation and have no new latency guarantee.

## Verification evidence and boundaries

The original incident remains `deepest-confirmed-root-unknown`, confidence B.
The measured cold compiler wait was sufficient to exceed the budget, but the
lost original stack does not establish its complete trigger history. See the
unchanged [diagnosis](rime-readiness-timeout-2026-10-24.md).

The device scripts built with `-PrequireRime=true` and verified the installed
APK hash before and after instrumentation. Earlier baseline-adjacent
latency/fallback/panel results used APK `11ba852d755b9588dccc8686fb4e511e372ae9419de22ee6856fa4b4e4a75c96`.
Final-code latency, real-IME and fallback/panel checks use APK `c484e01d…`;
the unchanged-budget AI/IME path also passed on APK `7d06cb28…`.
The complete pre-cache-review deployment suite used APK `42c05e4b…`; its final
runtime/cache counterpart is
`7d06cb28bea9c416ed082e531a0e8f3331a66c66a5a7ace06371e8ec73c05e10`. Both
include the generation-specific custom-recovery fix. The latter additionally
prevents/reclaims duplicate app-owned packaged archives. Final device timings
are listed above by exact artifact generation; no older hash is treated as proof
of a later runtime change.

Root `testDebugUnitTest` passed with Java 21 for the Robolectric runtime and an
explicit Java 17 compiler toolchain: 370 tests, 0 failures/errors, 2 skipped.
Java 17 alone fails before the SDK 36 Robolectric test executes; that
infrastructure failure was not suppressed. `privacyCheck`, `lintDebug` and
three-ABI `assembleDebug`, all with `requireRime`, passed on final cache-cleanup
code. The final JVM run took 36 s; the final privacy/Lint/three-ABI/test-package
run took 26 s. All three APKs include the four prebuilt assets and their matching
native Rime library. The later three-ABI x86_64 package has a different APK hash
from the device-tested x86_64-only artifact. The final real-IME, latency and
fallback/panel checks use that three-ABI-layout APK, `c484e01d…`, built from
the same source; these artifact identities are reported separately.

A build passing does not establish device behavior. Release signing, physical
ARM timing, optional-grammar timing and a full application-wide device suite
are outside the demonstrated scope.
