# ADR 0031: Declarative Skills and reviewed context compaction

- Status: Accepted on 2026-11-02 as E2 of the assistant expansion
- Scope: Local Skill management/import and explicit selected-history summaries

## Decision

The private standalone assistant supports user-created recipes and one explicitly
picked UTF-8 text/Markdown document, limited to 16 KiB. Import uses a temporary
SAF content URI, no persisted permission, directory traversal or archive loader.
The optional front matter accepts only name, description and a bounded
allowed-tools string array. Unknown/duplicate fields, YAML extensions, binary,
invalid UTF-8, controls and oversized input fail closed. Plain text requires a
user-entered name. Imported text is reviewed and explicitly saved; neither save
nor navigation selects it or sends it to a model. Code blocks remain inert text.

AiSkillsActivity is non-exported and secure, without saved private view state,
autofill or content capture. The bounded document executor, cancellation signal,
30-second deadline, activity epoch and AI-data generation revoke pending imports.
A document provider may itself use its cloud service. Choosing that provider
does not authorize an AI upload. Uncooperative provider calls may delay worker
cleanup; their late results cannot reach UI or storage.

AiSkillRepository stores at most 32 definitions in a strict version-1 encrypted
file ai-skills.bin under noBackupFilesDir, using the independent AI_SKILLS alias.
Optimistic revisions prevent stale edits. AI clear deletes the file/key even if
another domain fails; failed deletion blocks subsequent access until retry.
Existing persisted formats are unchanged. AiLibrarySession shares the revocable
worker boundary with role/memory management without coupling their storage.

Skills are selected per chat, reread before apply and counted against the shared
context budget. They are structured user content, never system instructions,
permissions, code or automatically installed tools. The ordinary text-chat tool
set is empty: Skills with tool prerequisites are visible but disabled. Later
tool tasks must validate prerequisites against their reviewed registered set.

Compaction has two explicit steps: inspect/send selected earlier conversation
text to the current provider, then inspect/apply its summary. It preserves the
latest two messages, current draft, role/memory/Skills and other references.
Only selected older turns and a prior summary are sent for this request. No
original message is deleted or rewritten. Applying replaces the previous summary
reference and deselects summarized turns. One local undo restores the projection
until subsequent context/chat work invalidates it; summaries are not persisted.

Proposals bind to the exact issued instance, context revision, provider settings,
task and AI-data generations. Cancellation, switching, policy changes and stale
results cannot apply. Malformed/overlong output leaves the source projection
unchanged. This uses the existing provider and adds no network endpoint or
permission. Summaries can omit information: the preview and retained originals
allow the user to reject or undo them.

## Alternatives and validation

Executable Skills and unrestricted YAML/bundle import add unapproved execution
and parsing surfaces. Automatic compaction hides data submission and information
loss. Both are excluded. Tests cover strict parsing/size/UTF-8, unsupported tools,
shared budgets, stale definitions, encrypted deletion and buffer cleanup,
two-step review, cancellation, forged proposals and original-message retention.
