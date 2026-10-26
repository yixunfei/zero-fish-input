# Rime startup and UX performance - Checkpoint

## Current Checkpoint

- Current todo: None; implementation, measured UX assessment and bounded verification are complete.
- Active slice: Completed; only future profiling of real-window p95 UI/candidate scheduling remains outside this scope.
- Completed todos:
- Source-bound prebuilt deployment below unchanged 60-second readiness budget with real Chinese conversion.
- Lifecycle, corruption, changed-dictionary, cancellation, close/retry, fallback and cache/user-boundary regressions passed.
- 370 JVM tests with 2 skips, privacyCheck, lintDebug, requireRime three-ABI and focused final-device checks passed.
- Measured UX report, ADR, architecture/threat-model and evidence/reflection completed; no commit requested.
- Evidence refs:
- docs/rime-ux-performance-2026-10-25.md
- docs/aegis/work/2026-10-25-rime-ux-performance/90-evidence.md
- Blocked on: none
- Next step: No required next step for this scope. Use the report to choose a separately measured UI-tail profiling task.

## Recent Checkpoint History

## Checkpoint Update

- Current todo: Finish fresh JVM/privacy/Lint/build evidence and performance report after cache-cleanup verification.
- Active slice: Final validation and measured UX report.
- Completed todos:
- Generated and installed source-bound default prebuilt artifacts with verified cold-start benefit.
- Passed lifecycle, corruption, custom recovery, first-frame, typing, panels and readiness-budget checks.
- Reproduced and removed duplicate base/shared packaged archives while preserving user-generation data.
- Evidence refs:
- docs/aegis/work/2026-10-25-rime-ux-performance/90-evidence.md
- Blocked on: none
- Next step: Refresh JVM/privacy/Lint/three-ABI evidence and bind final report to APK 7d06cb28.
## Checkpoint Update

- Current todo: Remove duplicated packaged archives from app-owned base/shared caches without touching user data; rerun focused deployment/lifecycle and packaging checks.
- Active slice: Final review found APK prebuilt archives are also copied to base and shared. Preserve the sole installer/cleanup owner and native-conversion-before-READY invariant.
- Completed todos:
- Generate official table/prism/reverse artifacts with the pinned host deployer.
- Align schema serialization to Android JSONObject and bind source/grammar/runtime identity.
- Add bounded XZ deployment and packaging; preserve native conversion verification and source compilation.
- Evidence refs: 90-evidence.md; engine-rime/prebuilt/rime/prebuilt/manifest.json.
- Blocked on: no infrastructure blocker. Runtime, UI, JVM, privacy, Lint and ABI checks passed. Public runtime directory inspection confirms `base-11-…/prebuilt` and `shared-ae165bd3…/prebuilt` duplicate compressed APK payloads. Regression added before the narrowly scoped cleanup change.
- Drift check: no production lifecycle rewrite without runtime evidence. Do not attribute the invalid 935a... artifacts to an installer or librime defect. The canonical generation is now ae165bd3c065ad102852eda9.
- Next step: Observe the new cache assertion failing on current code; skip prebuilt in copyAssetTree and clean app-owned copies after conversion verification. Then rerun deployment/readiness and report exact cache savings.

## DriftCheckDraft

- Scope status: Completed Rime deployment, bounded cache repair and measured UX assessment; personalization changes preserved
- Compatibility status: Conversion-before-READY, fallback, source compiler, custom generations and privacy preserved
- Retirement status: Only duplicate public archive caches removed; required source compiler retained with changed-dictionary evidence
- New risk signals:
- none
- Advisory decision: continue

## DriftCheckDraft

- Scope status: Completed Rime deployment, bounded cache repair and measured UX assessment; personalization changes preserved
- Compatibility status: Conversion-before-READY, fallback, source compiler, custom generations and privacy preserved
- Retirement status: Only duplicate public archive caches removed; required source compiler retained with changed-dictionary evidence
- New risk signals:
- none
- Advisory decision: continue
