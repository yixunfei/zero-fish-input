# ADR 0014: Candidate generation and association synergy

Status: accepted (2026-09-22). Implemented the same day: Parts 1–2 in
`engine-api`/`user-data`/`app`/`ime-core`, Part 3 as the `candidate-quality`
suite in `engine-dictionary`. The threat model and ADR 0013 were updated with
it. One scope adjustment is recorded under Part 3 below.

## Context

Candidate generation and next-word association are today two isolated
pipelines (evidence in
[word generation and association evaluation](../word-generation-association-evaluation.md)).
Three consequences follow:

1. Association order is static editorial priority. The encrypted personal
   frequency that already ranks composing candidates is never consulted, even
   though it is computed locally, gated by the existing session privacy
   policy, and available from the prepared in-memory mirror.
2. Per [ADR 0013](0013-offline-word-associations.md), an accepted association
   "does not learn or update personal frequency". A continuation the user
   accepts every day is rediscovered from the static table every time.
3. Association has a frozen-fixture JVM quality gate
   (`:engine-dictionary:evaluateWordAssociations`); candidate generation has
   only one-off host scripts and numbers recorded in documents. Regressions in
   candidate ranking cannot be caught by a single repeatable command.

The constraints that shaped ADR 0013 still hold: no networking, no new
sensitive-data semantics without lifecycle guarantees, no disk or crypto work
on the input thread, and no publishing private vocabulary in the idle strip.

## Decision

### Part 1 — Personal-frequency rerank of association order (C1)

- Extend `engine-api` with a bounded, read-only frequency lookup: given a
  small set of word values and a language, return their learned frequencies.
  Maximum 8 values per call, matching the association display bound. The port
  does not expose shortcuts, ids or other entries, and cannot enumerate the
  store.
- Serve the lookup from the same prepared in-memory mirror that backs
  personal candidates (`QueuedPersonalizationStore`). A miss, a failure or an
  unready mirror returns an empty result and preserves editorial order. No
  disk, Keystore or decryption work may occur on the input thread.
- `WordAssociationSession` reranks the at-most-eight public suggestions by
  descending personal frequency with a **stable** tie-break on editorial
  order. Rows with zero personal frequency keep their relative table order.
- Reranking only reorders existing public rows. It never injects personal
  vocabulary into the `NEXT_WORD` strip, never changes suggestion text, and
  runs under the same `personalizationAllowed` gate that already governs the
  association feature.

### Part 2 — Write accepted association selections back to learning (C2)

This revises one ADR 0013 decision ("does not learn or update personal
frequency"). The revision is narrow:

- Only an explicit click carrying a live candidate identity (the existing
  validated `CandidateRoute.Association` path) may write back. Space, Enter
  and automatic acceptance remain excluded, exactly as in ADR 0013.
- The write uses the existing `PersonalizationStore.learn` call with the
  session's current `learningAllowed` flag, so every existing gate applies:
  sensitive editors, incognito, learning-disabled, email/URI and the other
  classified contexts neither show associations nor write back. Failed or
  stale clicks write nothing.
- English: shortcut is the lowercased word, so written-back entries resurface
  normally through prefix matching.
- Chinese phase 1: the table carries no reading, so the write-back stores
  `shortcut = value`. Such entries cannot resurface through pinyin prefix
  matching; their immediate effect is the frequency signal consumed by Part 1
  and visibility in the existing phrase management screen, where the user can
  inspect and delete them. Storing a self-shortcut is honest about what the
  system knows; it does not fabricate a reading.
- Chinese phase 2 (separate data project, not required by Parts 1–2): add a
  pinyin reading column to `word-associations.tsv` with format validation and
  hash updates, letting written-back continuations resurface as ordinary
  personal candidates. Deferred until phase 1 evidence justifies authoring
  readings for the Chinese rows.
- Chain commits each write back once per accepted click, so frequency grows
  with real acceptance. Write-back failures are counted by the existing
  `learningFailureCount` degradation counter and never affect the commit.

### Part 3 — Unified quality evaluation pipeline (C3)

- Keep one harness pattern in `engine-dictionary`: frozen TSV fixtures, pinned
  SHA-256, recorded baseline, Gradle evaluation task, and a report without
  input or candidate text.
- Add a candidate-generation suite evaluated the same way: public
  pinyin/context fixtures with acceptable answers, evaluated against captured
  candidate lists (as the model rerank evaluation already does) rather than a
  live native engine, because librime cannot load in `engine-dictionary` JVM
  tests. Native round trips remain in the existing Android test suites.
- Shared metrics vocabulary across suites: coverage, Top-1/Top-3/Top-8,
  abstention false positives; plus first-choice correctness and
  clean-regression rate for ranking policies.
- Any fixture used to tune a feature is labelled a development fixture in its
  report header. An independent set stays frozen and untouched, per the rule
  already recorded in the association quality document.

Implementation note (2026-09-22): the on-device Rime candidate capture
(`build/model-evaluation/rime-candidates.json`) is a generated, ignored
artifact and is not committed, so no captured candidate lists exist to seed a
frozen native-order suite. The first candidate-generation suite therefore
evaluates the JVM-loadable production generator (`ReferenceDictionary`, the
fallback/cold-start engine) live on the harness. Its 30 fixtures, pinned
hashes and recorded baseline follow the association suite exactly, and the
report shares the coverage/top-k/abstention vocabulary. A captured native
suite can be added in the same format when a capture is committed.

## Consequences

- Data flow stays one-directional into the strip: personal frequency can
  reorder public rows, and explicit clicks can write to the encrypted store.
  No new store, file format, permission, thread or network path is introduced;
  clear-data and deletion semantics are unchanged because Part 2 writes
  through the existing repository.
- The association strip can surface user-adapted ordering. Because rows remain
  the public table's rows, no private word is ever displayed as a next-word
  suggestion.
- `engine-api` gains one read-only port method; `ime-core` wires it through
  `WordAssociationSession`. ADR 0013 must be amended to reference this ADR for
  the write-back revision.
- Phase-1 Chinese write-back entries are management-visible but not
  pinyin-reachable. This is an accepted, documented limitation until the
  phase-2 reading column exists.
- The evaluation task count and pinned hashes change; CI-style verification
  commands in the quality documents must be updated.

Privacy notes: no content is logged; the frequency lookup returns integers for
caller-supplied words; rerank happens in the session that already owns the
bounded context buffer; write-back reuses the generation-checked encrypted
write path. Threat-model sections on personalization and the association
feature need updating upon acceptance.

## Alternatives

- **Do nothing.** Keeps the strip static and the evaluation gap open. Rejected:
  the frequency signal already exists locally and the harness pattern is
  proven.
- **Inject personal words directly into NEXT_WORD.** Better coverage, but it
  publishes private vocabulary in an idle strip above arbitrary editors.
  Rejected on the privacy boundary; C1's reorder-only ceiling stands.
- **Train bigrams from luna essay or mine personal history.** Recorded dead
  ends in ADR 0013 and the comprehensive review: no sequential corpus, and new
  sensitive-data semantics respectively.
- **Synchronous store queries on the input thread for C1.** Simpler wiring,
  but it would put encrypted-store work on the latency-critical path.
  Rejected; the prepared mirror already serves personal candidates.
- **Give every Chinese continuation a fabricated reading from a pronunciation
  table at write-back time.** Polyphonic characters make this wrong often
  enough to pollute the store. Rejected in favour of the honest phase-1
  self-shortcut.

## Verification

Required before acceptance, in addition to the existing suites:

1. Threat-model update covering C1's read path, C2's revised learning
   boundary and the amended ADR 0013 text.
2. Unit tests: rerank stability and tie-breaks, empty/failed lookups, privacy
   gates off/on, no injection of non-table rows, write-back only from
   validated clicks, no write-back under any restricted editor class,
   `learningFailureCount` accounting, chain-commit single write per click.
3. Negative tests: store cleared between click and write-back (deletion
   generation), stale candidate identity, mirror unready, lookup throwing.
4. C3 suites frozen with pinned hashes before any tuning; reports show
   development vs independent fixtures separately.
5. Device regression: existing 8 association instrumentation tests plus
   rerank acceptance and write-back round trips; ARM/API 26 remain recorded
   gaps, not claims.
