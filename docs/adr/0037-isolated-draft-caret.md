# ADR 0037: Isolated draft caret editing

Status: accepted

## Context

On 2026-11-12 the user approved tap positioning, a visible caret, paired-symbol
insertion and middle backspace in private input, keyboard AI and expression-search
drafts. Previously these local buffers appended and deleted only at the end.

## Decision

- `LocalDraftInput` owns the committed-text caret in each independent bounded
  buffer. Insertions preserve the suffix; paired symbols place the caret between
  the pair. Backspace removes the preceding grapheme and clears vacated buffer
  units. Click positions snap to a grapheme boundary.
- Views receive a display offset and revision, never an editor connection.
  A tap carries that revision back to its owning draft. Rendering, closing and
  restarting retire old gestures. The view also tracks renders locally because
  separate draft owners can produce identical revision values.
- Clicking during conversion finishes the current composition before moving.
  AI and search render composition at the caret and map its display offsets back
  to committed text. Capacity failure does not apply the requested movement.
- Private masking still exposes only the last three committed code points.
  The compressed masked prefix cannot be positioned by tapping. The existing
  explicit full-display toggle enables editing there; masking again hides a
  caret inside that prefix. Composition and candidates keep their existing
  separate display. Private text remains non-selectable and excluded from
  accessibility text exposure.
- Long presses, drags, cancelled touches and touches across a render do not move
  the caret. AI's existing contextual selection command is unavailable during
  composition, so it cannot confuse preedit positions with committed ranges.
- Existing session, privacy, panel and settings revocation, empty personalization
  ports, memory bounds and explicit submission remain authoritative. This adds
  no external-editor reads, system clipboard access, persistence or permissions.

## Alternatives and consequences

Using an editable Android input field would introduce another input connection
and platform editing actions into the keyboard. A display-only view instead emits
bounded intents to the existing isolated controller. Immutable display and engine
strings retain the existing garbage-collection limitation; mutable committed
buffers are cleared on close. Middle editing moves a bounded suffix in memory.

Device tests cover paired insertion, suffix preservation, surrogate boundaries,
capacity failure, composition completion, mask/reveal, stale sessions and gestures,
and the actual private/AI/search touch routes. Existing panel tests cover selection,
layout, themes and rotation.
