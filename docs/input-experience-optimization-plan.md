# Input experience optimization plan

Date: 2026-10-09
Status: approved and implemented; emulator acceptance and limitations are recorded
in `input-experience-optimization-validation.md`.

## Confirmed objective

Preserve existing features while prioritizing Chinese full-pinyin, reliable fast
typing, accessible candidates and fewer language/number/symbol navigation steps.
The user has approved layout and candidate-interaction changes.

## Initial source findings

- `CandidateStripView.render` resets horizontal scrolling whenever the candidate
  list changes, including updates with unchanged raw input. Its next-page target
  uses text rather than stable candidate identity. Reproduce asynchronous refresh
  while browsing before changing the anchor policy.
- The strip gives tools and expansion 48 dp each. Long candidates have no explicit
  viewport-relative maximum width. Optimize usable candidate width without
  shrinking accessible controls or hiding complete long candidates everywhere.
- `KeyboardKeyView` cancels a tap as soon as it leaves the exact key rectangle.
  Measure small finger drift, overlapping pointers and glide interception before
  introducing bounded touch tolerance.
- `KeyboardPanel.render` cancels all pending key gestures. In particular, one-shot
  Shift resets after text dispatch. Reproduce overlapping shifted typing before
  narrowing invalidation to keys whose actions actually change.
- Text layouts expose combined numbers/symbols, while `NUMERIC` is selected only
  from editor type. Text entry needs a deliberate, directly accessible number pad.
- `InputSessionController.setLanguage` clears composition before replacing the
  engine. Define explicit user-switch behavior separately from privacy, subtype,
  settings and session invalidation, which must keep existing safe cancellation.
- Expanded candidates already use bounded paging, identity checks and scroll
  anchors. Preserve that work and improve it only for reproduced deficiencies.

These are source observations, not measured latency or confirmed device defects.
The workspace contains existing edits in candidate, service, ranking and document
files. Continue from those contents without reverting them.

## Proposed interaction decisions

1. Keep the keyboard height and principal control positions stable across ordinary
   text, number and symbol pages. Reserve the header for preedit and candidates
   during composition; place secondary tools behind the existing tools entry.
2. Show a clear first candidate, densely fit short alternatives and bound a long
   phrase's strip width. Expanded browsing provides readable complete phrases.
   Preserve a visible identity/pixel anchor on same-input asynchronous refresh;
   new input returns to the first candidate. Never relax stale-selection checks.
3. Provide direct number and symbol entry with a consistent return-to-text key.
   Preserve the active Chinese/English language when changing these pages.
   Keep numbers/symbols open for repeated entry; return explicitly, avoiding
   unexpected automatic page changes after punctuation.
4. An explicit Chinese/English switch should preserve unfinished input as literal
   text, not silently discard it or choose an unconfirmed conversion. Numbers or
   punctuation entered during Chinese composition should finalize the current
   first candidate before inserting the literal input, through existing engine
   contracts. Engine failure must preserve a controlled fallback.
5. Improve small-drift taps, overlapping fingers and Shift continuity without
   enabling speculative autocorrection or changing glide privacy boundaries.
   Retain platform-compatible Back, long-press and accessibility activation.
6. Use a restrained existing-theme presentation, clear language/page labels,
   consistent pressed states, readable composition and candidate contrast.

## Implementation order and ownership

Work sequentially in this chat; no delegated agents or concurrent Gradle runs.

1. Baseline: inspect remaining action routing, composition contracts and build
   configuration; reproduce public synthetic typing/navigation cases on the
   available emulator. Record render/input timings without input-content logs.
2. Candidate experience: strip sizing, stable browsing, expanded phrase reading
   and focused regression tests in `ime-ui` and app instrumentation.
3. Keyboard navigation: page state and layouts in `ime-ui`; explicit composition
   handoff in `ime-core`/app only where required by the approved decisions.
4. Fast input: fix reproduced touch/render issues; profile before and after;
   remove measured redundant work while retaining privacy checks.
5. Integrated review: Chinese/English, nine-key/double-pinyin, expression/AI local
   drafts, editor actions, numeric/password fields and secondary-panel return.
   Update architecture/README and a factual validation report as applicable.

No new network access, dependencies, persisted formats or security permissions
are planned. Protected panels retain their authentication and invalidation rules.

## Acceptance

- Public rapid-input sequences commit exactly once and in the defined order;
  repeat with overlapping touches, one-shot Shift, Backspace and engine warmup.
- Candidate browsing survives same-input updates without jumping to the start;
  stale taps cannot select a replacement candidate.
- Entering/exiting numbers or symbols does not unexpectedly change language or
  keyboard height. Explicit language switching preserves unfinished input.
- Check 320/360/411 dp portrait, landscape, dark/light themes and larger fonts;
  include narrow/floating layouts and long phrases. Use synthetic public text.
- Run relevant unit and engine-contract regressions, `privacyCheck`, app Lint,
  Debug APK build and emulator instrumentation. Require real Rime for engine
  validation; do not substitute a fallback-only build.
- Record actual measurements and unverified physical-device behavior. Do not
  claim universal latency improvements from static source analysis.

## Implemented outcome

- Candidate updates preserve the visible identity and pixel anchor for unchanged
  input. Long strip entries are bounded; expanded entries wrap without ellipsis.
- Idle tools are reduced to tools, numbers, AI and expressions. Secondary actions
  remain accessible from tools, with a fixed return control outside its scroller.
- Text number entry has a dedicated keypad, direct symbol switching and explicit
  return to the same Chinese/English keyboard.
- Explicit language switching commits unfinished literal input; privacy/settings
  cancellation retains its separate behavior and failed commits preserve input.
- Key release dispatch is immediate, small touch drift is tolerated and overlapping
  one-shot Shift gestures retain their captured letter actions.
- Redundant candidate/header binding is reduced. Handwriting no longer alternates
  compact/stacked layouts from collapsed IME measurements. Extremely short wide
  windows retain editor space and constrain every key row inside the panel.

No physical device is connected. Phone-specific latency, touch feel and OEM host
behavior remain target-device acceptance work.
