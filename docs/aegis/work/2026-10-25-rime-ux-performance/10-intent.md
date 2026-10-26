# Rime startup and UX performance - Intent

## TaskIntentDraft

- Requested outcome: Repair Rime readiness recovery, reduce measured cold-start cost, and assess user-facing performance with fresh regression evidence.
- Goal: Repair Rime readiness recovery, reduce measured cold-start cost, and assess user-facing performance with fresh regression evidence.
- Success evidence:
- none
- Stop condition: Stop when success evidence is satisfied or a blocker/risk requires pause.
- Non-goals:
- No unrelated personalization edits; no timeout-only workaround; no release claim.
- Scope: engine-rime startup/lifecycle and asset deployment; focused measured UX hot paths; relevant tests and documentation.
- Change kinds:
- bugfix
- Risk hints:
- none

## BaselineReadSetHint

- HEAD=e0a9cb81f06291a004be9de7845e7b06cdd84201

## BaselineUsageDraft

- Required baseline refs:
- HEAD=e0a9cb81f06291a004be9de7845e7b06cdd84201
- Delivered context refs:
- HEAD=e0a9cb81f06291a004be9de7845e7b06cdd84201
- Acknowledged before plan:
- HEAD=e0a9cb81f06291a004be9de7845e7b06cdd84201
- Cited in plan:
- HEAD=e0a9cb81f06291a004be9de7845e7b06cdd84201
- Missing refs:
- none
- Advisory decision: continue

## ImpactStatementDraft

- Compatibility boundary: Prebuilt artifacts apply only to the exact managed/no-grammar default generation. Source dictionaries, custom native compilation, conversion-before-READY, fallback and encrypted personalization remain authoritative. Cleanup is bounded to app-owned base/shared archive caches.
- Affected layers:
- engine-rime deployment
- build artifact generation
- focused device verification
- architecture and UX documentation
- Owners:
- RimeRuntime and RimeAssetInstaller
- Invariants:
- No IME-thread native initialization; conversion, privacy and session ownership remain correct.
- Non-goals:
- No unrelated personalization edits; no timeout-only workaround; no release claim.

These records are Method Pack drafts / hints, not authoritative runtime decisions.
