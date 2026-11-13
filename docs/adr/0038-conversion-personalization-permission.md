# ADR 0038: Separate conversion personalization from prediction flags

Status: accepted by the user on 2026-11-13

## Context

The user reported that repeated non-default choices learn in most applications
but not in QQ chat and search fields. QQ's current EditorInfo has not been
observed. Independently, reproducible controller tests showed that the existing
policy rejects personal candidates and explicit learning whenever a known text
editor uses NO_SUGGESTIONS, AUTO_COMPLETE or FILTER.

Android defines suggestion/completion flags separately from the explicit
IME_FLAG_NO_PERSONALIZED_LEARNING privacy request:

- [InputType](https://developer.android.com/reference/android/text/InputType)
- [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)

The user explicitly approved a limited change to the previous project rule.
This decision does not assert that every affected QQ version uses these flags.

## Decision

- SessionPrivacy owns a separate conversionPersonalizationAllowed capability.
  General learningAllowed, personalizationAllowed, predictionsAllowed and
  independentAiAllowed retain their previous meanings and restrictions.
- Known non-sensitive text/search editors may use that capability despite
  NO_SUGGESTIONS, AUTO_COMPLETE or FILTER. Password/PIN, unknown, email/URI,
  IME_FLAG_NO_PERSONALIZED_LEARNING, incognito and disabled learning override it,
  including combined flags.
- The controller consumes this exception only for composing Chinese candidates:
  bounded personal suggestions and exact frequency lookups, then learning after
  an explicit candidate selection that the current editor accepts.
- Implicit Space, Enter, punctuation, paired symbols and engine auto-commits
  still require the general learning permission and the existing user opt-in.
- English personal completions, emoji/kaomoji history, next-word associations,
  model context/scoring, private drafts and AI receive no new authority.
- Existing encrypted formats, key isolation, worker serialization, session and
  deletion generations, data clearing and no-network boundaries remain intact.
  No application package allowlist or compatibility storage is introduced.

## Consequences and validation

Typing the same pinyin and explicitly choosing the same word can now promote it
in these editors without enabling unrelated personal data. The general and
conversion permissions must not be used interchangeably by future features.

Regression coverage includes all combinations of the three flags, native and
personal candidate routes, accepted/rejected commits, implicit commit opt-in,
English isolation, privacy tightening and combined sensitive/privacy restrictions.
Platform checks cover actual native conversion, encrypted frequency increments,
fresh repository reads, input connection restart and screen touches.

Alternatives rejected: app-specific exceptions would miss other editors and
could override sensitive fields; treating prediction flags as general learning
permission would also enable history, model context and implicit learning.
