# ADR 0004: Chinese input options and bounded schema deployment

Status: accepted

## Context

The initial schema exposed traditional full pinyin only. Product settings need
script selection, abbreviation, independent fuzzy pairs and candidate page size.
Rime spelling algebra is compiled into a prism, so changing a UI preference alone
cannot enable a fuzzy rule. Recompiling a live index can block input or invalidate
native state. Future Chinese engines should share product-level settings.

## Decision

- Define immutable ChineseInputOptions and explicit capabilities in engine-api.
  Keep Rime spelling algebra and deployment in engine-rime. The fuzzy-pinyin
  mask keeps the original bit positions and appends new pairs, so existing
  saved settings retain their meaning while supporting the expanded rule set.
- Microsoft and Ziranma double-pinyin are distinct full-keyboard options. Their
  codes derive from the bundled public dictionary syllables during bounded,
  worker-side schema deployment. The memory fallback uses the same code mapping.
  Nine-key ignores the saved double-pinyin choice, and compiled variants carry
  a separate schema identity. Only the applied session choice changes the keys.
  Related-reading expansion remains specific to full pinyin; double-pinyin
  sessions use native candidate pages without interpreting codes as readings.
- Default to simplified Chinese with abbreviation enabled; fuzzy pairs are opt-in.
  A master boolean suspends the selected pair mask without erasing it. The
  effective mask drives both spelling rules and schema identity; preferences
  without the new boolean retain their prior selected-rule behavior. No personal
  storage migration or duplicate format is introduced. The keyboard shortcut
  opens rule configuration when no pair has been selected.
  Use Rime's existing algebra and OpenCC's pinned offline dictionaries.
- Use JSON syntax for the bundled YAML schema, allowing structured configuration
  derivation with the platform JSON parser. Derive only known option values.
- Bind options to each preparation request. Defer ordinary option changes until
  composition is idle, retire the native engine, then compile on the existing
  bounded worker. Refuse deployment while another native session is alive and
  signal readiness again when that session is released.
- Give compiled variants distinct prism names and remove inactive generated files.
  Keep the original encrypted personal data format and learning identities.
- Normalize personal candidate text through an optional engine port, gated by
  existing privacy policy. A failed conversion hides the personal candidate.
- Render preparation/failure/retry in the keyboard. Reuse native snapshot data and
  candidate views; do not move editor mutations to an unordered background path.

## Consequences

Changing phonetic rules can require a short visible preparation period. Basic
input remains available, while advanced options require the native engine to be
ready. Deployments cannot accumulate an unbounded configuration cache. A second
engine can advertise a subset of the same capabilities without changing session
or UI code to understand its native API.

## Alternatives

Prebuilding every fuzzy combination increases package size and deployment cost.
A custom pinyin parser duplicates mature domain logic. Sharing a mutable prism
between active sessions risks invalidating mapped native data. An unrestricted
Rime configuration editor would expose options that violate the privacy model.
