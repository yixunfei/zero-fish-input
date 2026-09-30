# Candidate generation and word association evaluation

Date: 2026-09-22
Method: read-only static analysis of current source plus the project's recorded
evaluation artifacts. No new runtime measurement, device data or corpus was
produced for this document. Companion design proposal:
[ADR 0014](adr/0014-candidate-association-synergy.md) (**accepted 2026-09-22**;
C1/C2/C3 implemented the same day — see the ADR's implementation note and the
threat model for the resulting boundaries).

"Candidate generation" is the composing pipeline that turns readings into
candidates. "Association" is the idle next-word strip defined by
[ADR 0013](adr/0013-offline-word-associations.md). This document evaluates each
on its current evidence and then analyses how the two isolated pipelines could
cooperate without weakening the privacy boundary.

## 1. Candidate generation: current state

### 1.1 Pipeline

```
key → InputSessionController.handle
      → InputEngine.handle (Rime full/9-key, fallback, English, or language pack)
      → CandidateWindow (6 retained pages, related-reading tier after native pages)
      → PersonalCandidatePaging (encrypted personal page, 8/page, prefix on shortcut)
      → optional AsyncCandidateRanker (Mini INT8 rerank, default off)
      → publish → strip / expanded view
```

### 1.2 Components and recorded evidence

| Component | Current behavior | Recorded evidence |
| --- | --- | --- |
| Rime engine (luna_pinyin 1.13.1) | Sentence mode on, simplified/traditional via OpenCC, 8 fuzzy-pinyin groups, abbreviated pinyin; static build-time lexicon, no update channel | First choice correct 36/94 on the held-out homophone fixtures in [small-model evaluation](small-model-evaluation.md); constructed contexts, not a population corpus |
| Fallback dictionary engine | Bounded reference TSV (≤512 entries) used for cold start and failure | Contract tests; degraded-mode integration tests |
| Personal overlay | Prefix match on encrypted shortcut; exact shortcut first, then frequency, recency; merged ahead of native candidates with text dedup | 216 unit + 44 device storage tests in [security storage validation](security-storage-validation.md); ranking correctness covered in `PersonalCandidatePagingTest` |
| Model rerank (experimental, default off) | Top-8, full-reading two-character Chinese words only, margin 1.25, 80 ms budget, order-preserving single promotion | Held-out +11.70 pp first-choice gain with 1.06 pp clean regression (gate ≤0.5): **quality gate fails, stays default-off**; one concrete `人物→任务` error recorded |
| Typo algebra (experimental, default off) | Adjacent-key, transposition, missing and repeated letter rules feeding Rime with reduced credibility | No independent effectiveness evaluation; the 1.06% figure belongs to the model rerank, not to typo rules |
| English engine | 862-word offline lexicon, indexed prefix lookup, frequency-aware paging, optional explicit corrections and automatic spacing | Public completions remain offline and bounded; correction candidates stay default-off |
| Word associations | See §2 | See §2 |

### 1.3 Assessed gaps

1. **No new-word channel.** The static luna_pinyin lexicon ages; the only
   update path is a hand-built language pack. This is the largest long-term
   correctness risk and is a data/build problem, not an algorithm one.
2. **Rerank and typo rules are unvalidated experiments.** Both are correctly
   default-off. Each needs its own frozen evaluation set; the rerank
   additionally needs a lower regression rate (higher margin, narrower
   candidate pairs, or a personal-frequency guardrail) before any default-on
   discussion.
3. **Personal overlay is prefix-only.** `PersonalizationStore` can answer
   "which learned shortcuts start with X" but not "what is the frequency of
   word W". Anything that needs value-based frequency (including §3) requires
   a port extension.
4. **English generation remains bounded.** The offline seed lexicon is broader and
   indexed, with frequency ordering, eight-item paging and explicit correction
   candidates. It is still not a complete dictionary or a natural-language model.
5. **No repeatable candidate-quality gate.** Rime first-choice baselines exist
   only as recorded numbers in evaluation documents; there is no JVM
   regression harness comparable to the association quality task (§2.3).

## 2. Word association: current state

### 2.1 Pipeline

```
successful commit (adapter-confirmed) → WordAssociationSession
      → bounded 32 UTF-16 unit letter/space context buffer
      → NextWordPredictor.suggest (longest suffix match, ≤8)
      → NEXT_WORD candidates shown only when idle
      → explicit click with live candidate identity → commitText
```

### 2.2 Recorded evidence

From [word association quality](word-association-quality.md) (2026-09-21):

- Corpus: 1,728 project-authored public pairs, 735 language/prefix keys,
  33,307 bytes UTF-8, Apache-2.0, no external corpus.
- Development fixtures (visible during curation, **not** an independent
  estimate): coverage 44/136 → 136/136, Top-1 36/136 → 136/136; abstention
  false positives 4/20 → 0/20 after the one-character anchor fix.
- Device: 8 instrumentation tests on the API 36 x86_64 emulator; no ARM, no
  API 26, no physical-device latency or energy data.

### 2.3 Assessed gaps

1. **Static editorial order.** Suggestion rank is table order, not measured
   frequency and not adapted to the user. Two users with opposite habits see
   identical ordering.
2. **No learning loop.** Per ADR 0013, an accepted association neither learns
   nor updates personal frequency. A chain the user accepts daily is
   rediscovered from the static table every time.
3. **Coverage ceiling of a hand-authored table.** Unknown contexts produce no
   continuation by design. Independent corpus evaluation is still missing and
   the dev-set 100% must not be quoted as natural-input accuracy.
4. **Matching limitations.** Without segmentation, one-character Chinese
   anchors only match the complete context (deliberate); longer suffixes can
   still cross semantic word boundaries.
5. **Chinese continuations carry no reading.** The table stores
   language/prefix/word only, so a selected Chinese continuation cannot be
   re-surfaced through pinyin prefix matching without new data.

## 3. Isolation analysis and collaboration opportunities

The two pipelines currently share nothing:

```
candidate generation: reading → engine lexicon ─┐
                                                 ├→ ranked candidates → commit
personal frequency ──(prefix query)─────────────┘         │
                                                          ▼
association:        commit text ──→ static public pairs ──→ NEXT_WORD strip
                    (no frequency read, no write-back, no reading retained)
```

Three opportunities survive the privacy boundary (selected for design in
[ADR 0014](adr/0014-candidate-association-synergy.md)):

| # | Opportunity | Main constraint found in code | Assessment |
| --- | --- | --- | --- |
| C1 | Rerank association order by encrypted personal frequency | Port is prefix-only; needs a bounded value lookup served from the prepared memory mirror, never disk/crypto on the input thread | Medium effort, read-only data flow, does not change what is stored |
| C2 | Write accepted association selections back to encrypted learning | Revises ADR 0013 "does not learn"; Chinese continuations lack a pinyin reading, so `shortcut=value` entries cannot resurface through pinyin prefix matching | Small for English; Chinese phase 1 feeds C1 ranking only unless the table gains a reading column |
| C3 | Unified quality evaluation pipeline | Association harness exists in `engine-dictionary`; candidate generation has only one-off host scripts and recorded numbers | Extends an existing pattern; needs captured/public candidate fixtures, no live native engine in JVM tests |

Explicitly rejected (recorded here so they stay rejected):

- **Training bigrams from luna essay data.** It is a word-frequency table, not
  sequential sentences; ADR 0013 already records this dead end.
- **Rime-internals empty-input prediction.** Couples controllers to one native
  engine and leaves English/data engines behind (ADR 0013 alternatives).
- **Injecting personal words into the NEXT_WORD strip.** That would publish
  private vocabulary in an idle strip visible above any editor; reranking
  existing public rows (C1) is the acceptable ceiling for now.
- **Mining personal history for new association pairs.** New sensitive-data
  semantics and lifecycle guarantees; out of scope per ADR 0013.

## 4. Metrics the next checkpoint must report

- C1: rerank acceptance (click rank distribution before/after), zero change in
  abstention fixtures, input-thread cost per commit (bounded value lookup).
- C2: learned-entry growth from association clicks, resurfacing rate of
  written-back entries, clear-data coverage unchanged (existing deletion
  tests must keep passing).
- C3: per-suite coverage / Top-1 / Top-3 / Top-8, abstention false positives,
  first-choice correctness and clean-regression rate for ranking policies,
  all on fixtures frozen before tuning with pinned hashes, reproduced by one
  Gradle invocation.
- All: natural-input accuracy still requires the separately licensed
  independent sequential corpus recorded in
  [word association quality](word-association-quality.md); ARM and API 26
  device evidence remains open from the comprehensive review.

## 5. Conclusion

Candidate generation is a solid static pipeline whose weakest points are
lexicon freshness and two unvalidated experiments; association is a
privacy-clean but static feature whose dev-set results say little about real
use. The highest-value collaboration is not a new model: it is letting the
existing encrypted personal frequency (already computed, already gated) flow
read-only into association ordering (C1), letting explicit association clicks
feed that same store (C2), and putting both features under one frozen-fixture
quality gate (C3). The design and its privacy analysis are in
[ADR 0014](adr/0014-candidate-association-synergy.md).
