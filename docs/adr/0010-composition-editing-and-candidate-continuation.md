# ADR 0010: Composition editing and bounded candidate continuation

Status: accepted. Date: 2026-09-08.

## Context

The user requires browsing beyond a fixed candidate count, completing unknown
phrases by segments, and explicitly reopening the last committed Chinese word.
Editor mutation, private learning and native selection must remain isolated.

## Decision

- `CompositionEditingEngine` owns restore, smaller-segment selection and undo.
  Engine updates carry the completed canonical reading and a learning decision.
  Segment commits stay in composition until the complete phrase is selected.
- External editor cursor changes finish the existing visible preedit
  without rewriting, reading or learning it, then reset engine and candidate
  state. Rime's temporary single-syllable candidate caret is restored to the end
  before ordinary typing or deletion. Fallback deletion removes input appended
  after selection before undoing that selection.
- The core retains one Chinese commit of at most 128 UTF-16 units in mutable
  buffers. The app expires this draft after 30 seconds and invalidates it on
  further editing, cursor changes, session/settings changes or destruction.
  Reopening verifies the original connection, acknowledged collapsed selection,
  composing bounds and exact preceding text before setting a composing region.
  Unsupported or changed editors reject the operation without deleting text.
- `CandidateWindow` retains at most six engine pages. Each route restores the
  original page and verifies candidate identity and text before selection.
  Expanded display uses RecyclerView and deduplicates its resident window.
- Full-keyboard Rime uses two sessions prepared on the engine worker, sharing
  public dictionaries. The primary retains the original composition. After its
  exact results end, a secondary enumerates alternative valid segmentations and
  near readings. There are at most 32 related readings, eight segmentation paths
  and 512 segmentation search steps. Results are labelled as similar readings.
  Selecting a secondary candidate transfers that session's composition ownership.
  Paging work is bounded; sources stop when exhausted. This is not an infinite
  generator of unrelated or duplicate words. Nine-key keeps native decoding.
- Native candidate iteration fetches one page plus lookahead and permits absolute
  candidate selection. It does not expose native pointers to Kotlin business code.
- Personal suggestions use a paged port and deterministic ordering (exact shortcut,
  frequency, recency, identifier), on the existing serial encrypted-data worker.
  The first eight precede public candidates; remaining personal pages follow
  public exhaustion. Pending results within the same data revision preserve the
  previous page; clear or mutation revisions immediately discard old pages.
  Space routes to the visible highlight, including earlier retained pages.
  Enter commits raw composition; only an idle Enter invokes the editor action.
  Cache storage
  remains bounded at 64 requests with at most 50 terms each. The existing 20,000
  entry repository capacity and encrypted format remain unchanged.
- Experimental typo handling is independent and default off. Bounded spelling
  rules compile into Rime's public prism off the input thread. No touch trace,
  private corpus, runtime network client or new model dependency is introduced.

## Consequences and alternatives

A second native session and spelling expansion cost memory/CPU; synthetic device
measurements are recorded separately with the experiment off/on. Reusing native
dictionary/prism data avoids loading a second full Kotlin Chinese dictionary.
The immediate fallback retains a small indexed public vocabulary and supports
segment composition, undo and paging; its coverage remains intentionally limited.

Unbounded candidate caching and deleting preceding text followed by reinsertion
were rejected because they risk memory growth and editor data loss. Reopening an
arbitrary older word or maintaining a cross-session text history is out of scope.
Recent-word replacement is not Android undo integration: an application may reject
composing regions, and concurrent edits cannot be made transactional across apps.

Small model integration remains gated on distribution rights, held-out quality,
Android performance and total package cost. See `../small-model-evaluation.md`.
