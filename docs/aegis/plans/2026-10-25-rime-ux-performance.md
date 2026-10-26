# Rime cold start, readiness recovery, and UX performance

## Goal

Make a fresh default Rime installation ready within the existing 60-second acceptance budget, preserve real conversion verification and bounded worker ownership, and assess user-facing performance using measurements rather than speculative refactoring.

## Architecture

The single engine worker remains the only native initialization owner. `RimeAssetInstaller` remains the deployment owner. A reviewed, exact-default table/prism/reverse artifact set is copied to the shared `build` directory only after validating its manifest and file hashes. Source dictionaries remain the source of truth for generation identity, integrity checking, changed dictionaries, and controlled rebuilds. `RimeRuntime` still publishes READY only after native initialization and a real conversion session.

The prebuilt artifacts accelerate deployment; they do not become a second dictionary source or a new initialization route. Retain the source compiler for corruption recovery, changed generations, and incompatible runtimes. Native artifact compatibility is tested on all packaged ABIs; a package-size regression can reject the optimization.

## Tech stack

Android Kotlin, JNI, pinned librime 1.13.1, CMake, JDK 17, Android instrumentation, x86_64 emulator, Windows host generator.

## Baseline and authority refs

- User approval: controlled precompiled default Rime artifacts plus lifecycle correctness work, retaining assets only when measured benefit justifies package growth.
- Baseline: HEAD e0a9cb81f06291a004be9de7845e7b06cdd84201; existing personalization edits remain untouched.
- Measured default cold deploy/native/verification: 71,046 ms; reused generation: 1,243 ms; existing readiness budget: 60,000 ms.
- Default generated table/prism/reverse: 74,179,788 / 22,192 / 263,796 bytes. Sixteen source tables: 70,601,147 bytes.
- Native full-pinyin key timing: p50 928 us, p95 7,865 us, 1,140 samples. Strict pinyin: p50 456 us, p95 1,345 us. Candidate layout: p50 99 us, p95 256 us.
- Original incident remains deepest-confirmed-root-unknown, confidence B; cold compilation is a demonstrated sufficient mechanism, not proof of the lost original runtime stack.
- Governing docs: docs/architecture.md, docs/threat-model.md, docs/adr/0001-engine-boundary.md, docs/adr/0003-background-engine-warmup.md, docs/adr/0022-compressed-resources-and-reduced-runtime.md.

## Compatibility boundary

No new permission, network path, personal-data source, unbounded worker, or public engine API. Do not move native initialization onto the IME thread or remove maintenance joining without an equivalent readiness boundary. Do not reuse prebuilt data for a different source/schema generation. Ordinary close, last-session release, initialization cancellation, active sessions, and process-wide native ownership must retain their existing contracts. User-owned personalization changes are outside this task.

## Change necessity and owner

No-change cannot remove the measured first-install compilation; increasing timeouts only hides it. Decision: code-change, limited to deployment artifacts/integrity, the existing asset installer, a reproducible generator, and any lifecycle defect proven at its canonical owner.

Patch shape: producer artifacts must bind to the installer identity and runtime verifier, not to a new readiness owner. Downstream consumers remain RimeEngineFactory and the existing warm-up coordinator. Ripple checks cover cold/reuse, source changes, corruption, close/retry, fallback, all ABIs, and package size. Source compilation is retained for recovery and changed dictionaries, not duplicated as a concurrent deployment owner. No old owner is retired.

TDD Route: mode off; decision skipped (regression evidence required, strict RED/GREEN not mandated); verification is behavioral instrumentation plus focused JVM/build checks and before/after measurements.

## Execution tasks

1. Write a host-side generator using the pinned librime deployer and reviewed public inputs. Verify default schemas, all sixteen source hashes, table/prism/reverse output, and a pinned artifact manifest. Keep temporary build output outside source assets. Do not edit third_party to bypass build problems.
2. Add integrity-checked prebuilt installation to RimeAssetInstaller. Bind artifacts to source/schema/runtime format identity. Copy only fixed allowlisted files using bounded reads, hash verification, and atomic replacement. Do not trust an existing marker or artifact merely because it exists.
3. Add device regressions for cold prebuilt deployment and conversion, warm reuse, corrupted artifact recovery, changed dictionary generation, and normal close/release/retry. Reproduce and fix only lifecycle defects established by runtime evidence. Do not weaken the 60-second AiImeInteractionTest readiness assertion.
4. Measure cold and warm deployment, conversion, first-frame fallback availability, typing latency, panel behavior, and recovery. Reuse InputLatencyTest for comparable timing and inspect its actual workload before drawing conclusions. Avoid unrelated hot-path optimization without a measured high-value target.
5. Run focused JVM/device regressions, privacyCheck, lintDebug, three-ABI native/package checks, and -PrequireRime=true. Compare APK size with the matching baseline. Report skipped or unavailable physical-device measurements explicitly.
6. Update architecture/deployment decision docs and the measured performance assessment. Record exact evidence and uncertainty in docs/aegis/work/2026-10-25-rime-ux-performance/. Keep the original timeout diagnosis evidentiary boundary intact. No commit is requested.

## Completion evidence and stop

A fresh default installation must complete below 60 seconds with actual conversion; warm reuse and fallback must remain correct; all required artifact/ABI checks must pass; measured package growth must be justified. Stop for incompatible formats, unsafe source/artifact identity, unbounded growth, lifecycle races, or results that cannot be reproduced from a clean fixture. Emulator results do not establish physical ARM performance or release readiness.

Execution route: inline in the current dirty workspace to avoid parallel Gradle/native ownership conflicts and to preserve user edits. User confirmation required: no; the architecture and artifact tradeoff were already approved.
