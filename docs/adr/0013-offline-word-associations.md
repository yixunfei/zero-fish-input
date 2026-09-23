# ADR 0013: Offline next-word suggestions

Status: accepted (2026-09-21). Amended 2026-09-22 by
[ADR 0014](0014-candidate-association-synergy.md): suggestion order may follow
learned personal frequency, and an explicit accepted click writes the word back
through the encrypted learning channel. The "does not learn" sentence in the
Decision below is superseded by that amendment; every other boundary here is
unchanged.

## Context

Composing candidates end when a word is committed. Users need a small set of
useful continuations without typing another reading. Rime's bundled essay data
contains word frequencies, not sequential sentence examples from which a
next-word model can simply be trained. The optional short-word model ranks
existing candidates and does not provide an unrestricted continuation vocabulary.

## Decision

- Add the memory-only `engine-api/NextWordPredictor` port. Its context is borrowed
  only for the duration of a bounded synchronous query. It cannot retain context
  or access personal storage, editor text, native engines or networking.
- Reuse `engine-dictionary` for an immutable index of project-authored Chinese
  and English phrase pairs in `word-associations.tsv` (Apache-2.0). Rows are ordered
  by editorial priority. The existing engine worker validates and loads this
  public resource once; keys only query the prepared map. Missing/invalid assets
  leave an empty provider and preserve ordinary input.
- `ime-core/WordAssociationSession` owns at most 32 UTF-16 units from successful
  IME commits. It keeps a wipeable character buffer and no persisted history.
  `EditorConnection.commitText` returns actual adapter acceptance; failed commits
  cannot seed predictions or learning. No editor surrounding-text API is added.
- Query the longest matching suffix, respecting English word boundaries, and
  return at most eight continuations. English commits insert a separator only
  when needed; Chinese suggestions use the active engine's script normalizer.
  The small seed vocabulary has limited coverage and is not a trained model.
- Display `NEXT_WORD` candidates only when composition and ordinary candidates
  are empty. Only a click carrying a current candidate identity accepts them.
  A successful click can chain. ~~It does not learn or update personal
  frequency~~ (amended by ADR 0014: accepted clicks learn through the ordinary
  encrypted channel; order may follow learned frequency).
  Space and Enter retain their normal editing behavior and never autoaccept a
  next word. The existing strip scrolls horizontally; prediction expansion and
  paging are unnecessary for this bounded set.
- Provide an independent, default-on switch in input settings. Require an active
  text editor with suggestions and personalization allowed. Password/PIN,
  unknown, numeric, phone/date, email/URI, no-suggestions/no-learning, incognito
  and learning-disabled contexts cannot query or retain association context.
- Clear on session/view/connection, cursor/selection, privacy/settings, engine,
  language/pack/subtype, panel, deletion, reconversion and direct literal/private
  insertion boundaries. Typing hides old predictions while composing. Candidate
  identities and UI binding generations reject stale clicks. Public asset
  readiness has no callback into a session, so it cannot revive cleared results.

## Consequences

The feature introduces no runtime permissions, dependencies, worker threads,
personal-data format or migration. The public index may remain process-wide;
private context belongs only to its editor controller and is wiped at boundaries.
Temporary JVM string copies during lookup have garbage-collector lifetimes; no
complete process-memory erasure claim is made.

The table is deliberately small. Unknown contexts show no continuation. Broader
coverage requires a separately reviewed licensed sequential corpus and evaluation;
private adaptive n-grams and model generation are outside this change. Ordinary
successful typed words may still use the preexisting encrypted learning policy.

## Alternatives

- Using Rime internals for empty-input prediction would couple controllers to a
  specific engine and leave English and data engines without the feature.
- Reusing the masked-word scorer would add inference and candidate-generation
  requirements beyond its tested purpose and default-off scope.
- Mining personal word history would require new sensitive data semantics and
  lifecycle/deletion guarantees. The approved first iteration uses public pairs.

## Verification

The approved 2026-09-21 coverage follow-up expands the project-authored table to
1,698 pairs (723 keys), with no new corpus license or runtime component. Frozen
development/acceptance fixtures measure before/after coverage and ranked hits.
They were visible during curation and are not a held-out accuracy estimate.
One-character Chinese keys now match only the complete context; longer Chinese
suffixes and English word boundaries retain their existing matching semantics.
See [quality evaluation](../word-association-quality.md).

Unit tests cover lookup boundaries, longest matches, English spacing, script
normalization, failed commits, no learning on selection, stale identities,
privacy revocation, memory clearing and late provider readiness. Android tests
cover actual Rime/English editor round trips, cursor/settings/restart invalidation
and stable candidate layouts. See [validation](../word-association-validation.md).
