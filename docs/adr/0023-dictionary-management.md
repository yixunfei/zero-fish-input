# ADR 0023: Public dictionary management and encrypted local edits

Status: accepted (user confirmation, 2026-10-12)

## Decision

Manage each of the 16 bundled Wanxiang tables and installed public dictionaries
from dictionary settings. The `zi` character table is mandatory. Other bundled
tables can be disabled or logically deleted. APK originals remain available for
offline restoration. Immutable deployment generations omit disabled tables and
reclaim old copies only after verification and release of active engines.

`language-pack/BuiltInDictionaryStore` owns public selection and composes a locked
`PublicDictionarySnapshot` with installed public files. Reviewed replacements keep
the original native table name. Downloads use only the existing dictionary
transport. A bundled replacement comes from the exact reviewed archive revision
and SHA-256 in `tools/dictionaries/sources.lock.json`; only the requested dictionary
entry is parsed. No schemas, scripts, libraries or executable archive content are
installed. Extra dictionaries record public source receipts separately; redownload
retains their stable ID, enabled state and encrypted changes. Older installations
without a receipt are matched against the source catalog; if unavailable, the user
can select the same dictionary again from its source. No personal metadata is sent.

Names, entry additions, replacements, frequency overrides and deletion tombstones
live in `user-data/DictionaryEditRepository`, with a dedicated Keystore alias and
AES-GCM file in noBackupFilesDir. Binary format 1 bounds payloads to 5 MiB and
20,000 edits; existing personal formats are unchanged. No plaintext edit index or
Rime user dictionary is created. Rename and entry changes never enter public
receipts, downloaded files, deployment YAML or logs.

A worker composes edits with learned suggestions only after the existing editor
personalization gate. Disabled/deleted dictionaries contribute no edits. Full,
mixed abbreviated, configured fuzzy, nine-key and double-pinyin spellings are
matched from explicit syllables; script conversion stays inside engine-rime.
Native candidates matching edited original text/readings are filtered. The engine
does not expose dictionary provenance, so same-text/same-reading duplicates are
filtered together. Corrected entries are ranked by their explicit local weights
before public native results; native Rime scores are not comparable to those
weights. Native candidate canonical readings also filter corrected spellings. Engines
without canonical readings use the bounded spelling matcher; native phrase
synthesis containing a deleted word inside a different phrase is not removed. Private/learning-disabled sessions use public
originals and never read the encrypted overlay.

Candidate queries are bounded and asynchronous. Pending edit queries hide native
candidates until resolution; Space waits without submitting an unfiltered native
default. When all candidates are deleted, Space commits raw input. Punctuation
uses the visible candidate or raw input. Native candidate routes retain their IDs.
Runtime/script changes and dictionary publication invalidate prepared caches.

Explicit restore-original clears the dictionary's local name and edits; offline
restore and redownload preserve them. Deletion preserves edits for reinstallation,
as the confirmation explains. Global personal-data clear deletes the edit file and
its separate key, advancing the generation before serialized deletion. Stale saves
cannot resurrect cleared data. Secure editor pages disable view-state persistence,
autofill/content capture and personalized IME learning; leaving closes private
forms and cancels outstanding tasks.
Forms capture the displayed deletion generation before editing, and keep it
through Save, Delete and Undo; a delayed revocation callback cannot renew an old
form's write authority. Async completion only dismisses its original dialog.

## Consequences and alternatives

Public offline operation, fixed supply-chain checks and the existing network
boundary are preserved. Search scans verified originals on a cancellable worker,
returning 40 rows at a time, without an additional plaintext personal index. Large
dictionaries can make searches slower. Editing public YAML directly was rejected
because it would persist personal terms in native plaintext. Retained local edits
are listed first and remain searchable even when a newer public version removes
their original row; original rows with those keys are skipped to avoid duplicates.
A full encrypted replacement of millions of public entries would add unnecessary storage and
startup work. A local overlay gives explicit undo without redownloading data.

Validation and remaining device checks are recorded in
[the management validation report](../dictionary-management-validation.md).
