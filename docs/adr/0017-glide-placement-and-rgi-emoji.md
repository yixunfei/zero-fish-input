# ADR 0017: Offline glide, keyboard placement and complete RGI emoji

Status: accepted, 2026-09-30. The user approved all five glide layouts, quality-first
simplified/traditional single-character handwriting, bundled emoji artwork and
parallel implementation with centralized builds and device verification.

## Decision

Glide decoding is a separate public-data port, not a replacement Chinese engine.
The UI captures at most 256 points for 30 seconds with actual key rectangles;
the decoder evaluates a bounded dictionary shortlist on one cancellable worker.
The user chooses a proposed spelling. Chinese codes append through the current
engine; English words use its normal text/space path. Repeated codes remain
distinct choices. No editor-context query, gesture persistence, network request
or personal-data port is introduced. Sensitive/unknown/nontext contexts reject
glide; English additionally obeys no-predictions policy. Ordinary privacy gates
continue to control any subsequent engine learning. Stale/cancelled choices and
replay slices fail closed.

All panels share one IME layout host. Floating controls drag, resize, minimize,
restore and dock that surface. One-hand controls select either side and adjust
width. Position is normalized, clamped to current safe bounds, and stored by
orientation as public preferences. The application-overlay permission remains
limited to its previously approved clipboard reminder and is not used here.

Unicode Emoji 18.0 is pinned to the formal release, with an exact-set asset audit.
The catalog includes fully-qualified sequences and components, while kaomoji and
custom entries retain separate identities. Noto WebP artwork is bundled so panel
display does not depend on old system fonts; submitted content remains Unicode.
Public variants are available independently of personal favorites. Search and
image preparation stay off the input thread, and private rows are synchronously
cleared on policy/session transitions. No personal persistence format changes.

Handwriting remains governed by [ADR 0016](0016-offline-chinese-handwriting.md).
Its model-specific public source and training-overlap limitations are recorded
in the handwriting quality report rather than inferred from font tests.

## Consequences and alternatives

Bundled public resources increase APK and memory footprint. Bounded scratch
space, worker cancellation and public-cache lifecycle handling are required.
Glide dictionary matching cannot guarantee arbitrary out-of-vocabulary words or
natural gestures; synthetic corpus checks are regression evidence only. Explicit
choices and normal tap input remain available. Cloud decoding was excluded by
the input privacy boundary. A system-font-only emoji solution was rejected by
the user's complete offline display requirement. The receiving application's
font still controls the appearance of inserted text.

Desktop and emulator results, distribution artifacts and untested platforms are
recorded in [feature validation](../input-feature-completion-validation.md).
