# Rime startup and UX performance - Evidence

## Baseline

- Measured default cold deploy/native/verification: 71,046 ms; reused generation: 1,243 ms; unchanged acceptance budget: 60,000 ms.
- Fresh native full-pinyin key timing: p50 928 us, p95 7,865 us (1,140 samples). Strict pinyin: p50 456 us, p95 1,345 us (1,140 samples). Candidate layout: p50 99 us, p95 256 us. Existing `InputLatencyTest`: three tests passed in 59.267 s.
- Original intermittent incident remains `deepest-confirmed-root-unknown`, confidence B. Cold compilation is a demonstrated sufficient mechanism, not proof of the lost original stack.

## Generation and focused checks

- Pinned official host `rime_deployer` completed generation. Default table/prism/reverse are 74,179,788 / 22,192 / 263,796 bytes; XZ archives are 25,464,232 / 3,460 / 77,240 bytes (25,544,932 total).
- After matching Android `JSONObject` slash escaping, schema SHA-256 is `559da5c64d27f57f1823226cc981550bebf0883d0a78e36907383c385aebfa3f`. The first generated manifest used the wrong generation because its source-identity string omitted Kotlin `joinToString`'s default comma-space separators (1,521 vs 1,555 UTF-8 bytes); those artifacts were not accepted.
- Host table and reverse hashes match the previously inspected default device public generation. Prism uses its schema checksum and still requires Android conversion verification.
- `:engine-rime:compileDebugKotlin :engine-rime:testDebugUnitTest -Pandroid.injected.build.abi=x86_64`: passed with JDK 17 after fixing the local stream/file hash interface error.
- Device test APK was built and installed with `-PrequireRime=true`; installed SHA-256 `9e6e0f56344497c799434641e03e9cdf689e0cce4fee87d4d052f9607ca1c145`. `CompressedDeploymentTest`: one changed-dictionary scenario passed; two default/prebuilt path assertions failed after the initial warm-up. The suite took 216.495 s including repeated cleanup/restoration; it is not a passing prebuilt result.
- Root of the failed prebuilt assertions: the tested APK contained no `assets/rime/prebuilt` entries. The raw `srcDir("prebuilt")` produced `assets/prebuilt`, while the installer reads `rime/prebuilt`. Runtime therefore correctly used a separate non-default generation and source compilation. Cold <60 s and conversion do not establish prebuilt reuse in that APK.
- Build now stages reviewed files through `baseRimeAssets/rime/prebuilt` and checks their manifest/archive/source hashes. APK SHA-256 `3abde365730de165d915e5095aa268995c873fa62ee998479610f5714bcae440` was cross-checked with `aapt` and `jar`: all four `assets/rime/prebuilt/*` entries are present. One default run exceeded 60 seconds because the incorrect generator generation made `isDefaultGeneration()` false; this is not evidence that valid artifacts were used.
- The corrected generator uses the same comma-space separator and derives generation `ae165bd3c065ad102852eda9`. The official host deployer regenerated the manifest; local verification binds the Kotlin asset version, upstream Rime version/commit, schema/default bytes, source metadata and compressed artifact hashes. The regenerated table/prism hashes match the earlier host output. Corrected artifact device acceptance is still pending.

- Installed APK SHA-256 `19dc48189682ae20cb95d62ac766025b0ecca11eb700fb3a58d333ccd570b491`: `reviewedDefaultIdentityMatchesRuntimeInputs` passed (1 test, 0.123 s), confirming Android and generator source/default generation identities. This identity test does not establish native startup or artifact compatibility.
- Full stacks on that APK showed `RimeAssetInstaller.installSnapshot:81` failing before native initialization. The assertion incorrectly compared the original base schema (which retains optional grammar) against the normalized staging schema (without grammar). Remove the duplicate raw-file comparison; `installPrebuilt` already verifies the exact staging schema bytes. Corrected generation identity was therefore not a proof of working prebuilt deployment.
- APK SHA-256 `4bcbea8438e79bd16750631b118134ce4756a2ea64b5ca70194d2f4d2420305c` then initialized successfully but took 90,093 ms, failing the unchanged 60-second budget. The generator's primary dictionary retained its original `version`, whereas Android writes `ae165bd3c065ad102852eda9`; librime checksums that header. Align the generator's exact header and bind its resulting hash in the manifest before rerunning. This generation-header mismatch is a concrete incompatibility, not yet proof that it explains every native recompilation.

- APK SHA-256 `7b17cabbe213298ed949f989a6737e4e77b85dac9e25215437629da69fda65b3`: the default cold/warm test passed with three isolated cycles. Cold deploy/native/verification: 11,511 / 12,778 / 12,174 ms (median 12,174); reuse: 2,289 / 1,619 / 3,172 ms (median 2,289). All retained 16 source tables, expected generation, shared prebuilt table, no default user-compiled table, and real `nihao` -> `你好` conversion; application runtime restoration passed. Primary dictionary SHA-256 was `a24d250eda453f097d0b8bce51d2c670d9422efdc8cb38399683115ad4cb3156`, matching the generator and installer manifest.

- Same APK, full `CompressedDeploymentTest`: OK (4 tests), 270.781 s. Covers identity, default cold/warm, corrupted prebuilt replacement and changed-dictionary native compilation. Repeated cold timings in this shared suite (12,328 / 17,658 / 25,852 ms; reuse 1,952 / 6,802 / 1,636 ms) are contaminated by later fixture application restoration work and are not isolated cold-start medians. The isolated cycle above remains the performance sample.

- Same APK, `RimeReadinessTest`: OK (5 tests), 204.349 s. Covers failed-dictionary fallback, ordinary close/retry, last active-session release, queued cancellation/close during initialization, corruption and retry. No runtime rewrite followed these passing tests. A later code review scoped `lastVerified()` prebuilt matching to the default generation only; custom-generation close/recompile/retry must be rerun on the next APK before final acceptance.

- APK SHA-256 `11ba852d755b9588dccc8686fb4e511e372ae9419de22ee6856fa4b4e4a75c96`: changed-dictionary test passed (1 test, 116.045 s), including native compilation, close/retry recovery through `lastVerified()` and conversion of the additional public term. Its user table is retained and default prebuilt artifacts are not applied to that generation.

- Same `11ba852d…` APK, final `RimeReadinessTest`: OK (5 tests), 207.459 s. No lifecycle production rewrite was needed after the active-session, cancellation, failed verification and retry regressions passed.

- Same `11ba852d…` APK: `ChineseInputAvailabilityTest` OK (4 tests, 35.2 s), `InputLatencyTest` OK (3 tests, 8.852 s), `InputPipelineTest` OK (2 tests, 18.318 s), and `AiImeInteractionTest` OK (5 tests, 41.857 s) with the unchanged 60-second readiness assertion. First fallback creation was 21 ms; its five-key total was 18,457 us. Native full-pinyin p50/p95: 881/7,878 us; strict: 464/1,376 us; layout: 131/1,337 us (each 1,140 native samples). Real-window dispatch p50/p95: 18,839/76,500 us; editor 8,379/70,725 us; frame 19,514/76,785 us. No claim of a warm or UI-latency improvement follows.

- `privacyCheck :app:lintDebug -PrequireRime=true` passed in 1 m 37 s; three-ABI `:app:assembleDebug -PrequireRime=true` passed. arm64-v8a, armeabi-v7a and x86_64 APKs each contain all four prebuilt entries and their matching Rime native library; hashes are recorded in `build/performance-apk/three-abi.json`. This is packaging evidence, not ARM execution evidence.
- The first JVM attempt with Java 17 reached 370 tests, with 1 failed and 2 skipped: `DeviceAgentDisclosureAcknowledgementTest` could not initialize Robolectric because Android SDK 36 requires Java 21. The test code had not run; this is a test-runtime requirement, not a Rime regression. Rerun with Java 21 and explicit Java 17 compiler toolchain discovery.
- Clean app packaging without/with prebuilt assets: 65,341,387 / 90,887,825 bytes; delta 25,546,438 bytes (24.36 MiB). With-prebuilt SHA-256 `42c05e4b8c3fa28842c0e1dff6a4136bf5ef97222131ce02b37035a377aa9842`. The first incremental comparison (370 bytes) was falsified: removing four ZIP central-directory entries did not reclaim their payload space, as shown by unchanged central-directory offsets. Clean comparisons replace that misleading measurement; the 24.36 MiB cost is accepted for the measured default cold-start benefit, without a warm or storage-saving claim.

- Final clean APK `42c05e4b8c3fa28842c0e1dff6a4136bf5ef97222131ce02b37035a377aa9842`: `CompressedDeploymentTest` OK (4 tests, 114.763 s). Cold 9,354 / 7,792 / 7,996 ms; reuse 814 / 890 / 847 ms. Timers end before each fixture's `finally` restores the application runtime. Source table count, expected generation, prebuilt rather than user default table, unchanged source mtimes and real conversion passed. Corruption and changed-source close/recompile/retry recovery also passed. Earlier shared-suite timing contamination is not attributed to these intervals; both timing sets remain recorded.
- Java 21 + explicitly discovered Java 17 compiler toolchain: root `testDebugUnitTest` BUILD SUCCESSFUL in 52 s; XML reports total 370 tests, 0 failures, 0 errors, 2 skipped. No test or source was changed to bypass Robolectric's Java requirement.
- Benchmark `Sync` now declares the exclusion property as an input, so switching that property invalidates stale staging. The empty `Exec` was also disabled for that benchmark alone; ordinary builds execute verification. Subsequent clean and final normal packaging passed; final normal three-ABI identities are listed below.

- Final code review observed duplicate public archive directories at `base-11-…/prebuilt` and `shared-ae165bd3…/prebuilt`, each 24,976 KiB on the public emulator runtime. A new focused regression failed on APK `42c05e4b…`: `Packaged archives must not be deployed into the base cache` (1 failure). Repair: skip `rime/prebuilt` in generic asset copying and remove only canonical app-owned base/shared archive directories after conversion verification; no user-tree traversal or personal data deletion. Reuse tests seed old public copies, and custom recovery asserts a user-generation marker survives. This change was subsequently verified on fresh APK `7d06cb28…`; previous device hashes remain historical evidence only.

- Cache-cleanup APK `7d06cb28bea9c416ed082e531a0e8f3331a66c66a5a7ace06371e8ec73c05e10`: `CompressedDeploymentTest` OK (4 tests, 100.69 s); cold 7,493 / 8,086 / 7,257 ms and reuse 868 / 864 / 782 ms. Seeded base/shared archive copies are removed after conversion; a custom user-generation marker and custom-word conversion survive. `RimeReadinessTest` OK (5 tests, 93.869 s). `AiImeInteractionTest` OK (5 tests, 36.967 s), unchanged 60-second budget. Actual app base/shared listings after tests contain no `prebuilt` directory.
- Final JVM root `testDebugUnitTest` with Java 21 runtime and Java 17 compiler discovery: BUILD SUCCESSFUL in 36 s. This covers the final cache-cleanup production code; counts are checked from XML reports. No new runtime executor, dictionary authority, network or permission was introduced.

## Verification boundaries

- Default prebuilt artifacts are read from fixed APK assets into unpublished shared `build`, with fixed formats, bounded sizes and both original/archive SHA-256 checks. Native initialization, maintenance joining, session creation and real conversion remain mandatory.
- Changed or grammar-bearing generations retain source compilation. Corruption may recover through controlled rebuild/redeployment or fail readiness with a working fallback; behavior must be observed on device.
- Last-verified integrity, generation scoping, cache cleanup and build-time verification are covered by final APK `7d06cb28…` deployment/readiness tests. Prior APK hashes provide historical comparisons only.
- Final `privacyCheck`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` with `-PrequireRime=true`: BUILD SUCCESSFUL in 26 s. The device-tested x86_64 APK hash is `7d06cb28…`. Subsequent normal three-ABI packaging contains the same source revision but distinct APK identities: arm64-v8a `53f8f3d245bf1a8162aca8a29df3764eb485a2d8c89a71923f475f985919d99b`, armeabi-v7a `4a6dc903568ec13b6b0038ec9b4184954199f77a6eb55831d5efeff632018435`, x86_64 `ec109b166b210dfe9e820b4dc04f6a26ddbd9aa1fd40255daac8dba71ebfab3e`. Each has four prebuilt entries and its matching native Rime library. These later hashes are packaging evidence only, not substituted for device-test evidence.
- Final source, normal three-ABI-output-layout APK `c484e01d3a800ad4d6dfe0cc09d5742457dab4aa1cfeb3ce6c3f534009505631`: `InputPipelineTest` OK (2 tests, 12.555 s), dispatch p50/p95 8,502/55,324 us, editor 4,643/52,749 us, frame 12,647/55,617 us. `InputLatencyTest` OK (3 tests, 6.801 s), full pinyin p50/p95 767/6,927 us, strict 419/905 us, layout 119/228 us. `ChineseInputAvailabilityTest` OK (4 tests, 10.214 s), fallback creation 1 ms, five-key total 1,269 us. These distinct-run measurements are final-code behavior, not a controlled causal UI-improvement comparison. This x86_64 output-layout identity is not the earlier x86_64-only or later three-ABI packaging hash.
- No physical ARM device measurement, optional-grammar timing, release signing/readiness or complete application-wide device-suite claim is established. Complexity control: one installer/deployment owner and one native worker retained; the recoverable archive-cache copies are removed, while native compilation remains the required custom/grammar path. No new fallback or runtime authority was introduced.

## EvidenceBundleDraft: runtime-cache-and-readiness

- Artifact key: runtime-cache-and-readiness
- Slice ID: runtime-cache-and-readiness
- Type: device-regression
- Source: build/deployment-cache-green.txt; build/readiness-cache-green.txt; build/ai-ime-cache-final.txt
- Summary: APK 7d06cb28 passed deployment 4/4, readiness 5/5, AI/IME 5/5; source-bound prebuilt cold-start below 60 seconds, custom/corrupt recovery and user-cache boundary preserved.
- Verifier: api36-x86_64-userdebug-emulator
- Evidence status: evidence-finalized

## EvidenceBundleDraft: jvm-privacy-build

- Artifact key: jvm-privacy-build
- Slice ID: jvm-privacy-build
- Type: build-and-test
- Source: build/jvm-cache-final.txt; build/privacy-lint-build-cache-final.txt
- Summary: 370 JVM tests, 0 failures/errors, 2 skipped on JDK21 runtime/JDK17 compiler; privacyCheck, lintDebug, requireRime three-ABI and test packaging passed.
- Verifier: local-gradle-and-xml-reports
- Evidence status: evidence-finalized

## EvidenceBundleDraft: final-input-experience

- Artifact key: final-input-experience
- Slice ID: final-input-experience
- Type: device-regression
- Source: build/availability-cache-final.txt; build/pipeline-cache-final.txt; build/latency-cache-final.txt
- Summary: Final source APK c484e01d passed fallback/panel 4/4, real-IME pipeline 2/2 and latency 3/3; final measured metrics recorded in UX report, without causal UI-improvement claim.
- Verifier: api36-x86_64-userdebug-emulator
- Evidence status: evidence-finalized
