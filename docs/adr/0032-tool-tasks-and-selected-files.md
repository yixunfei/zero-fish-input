# ADR 0032: Bounded tool tasks and selected files

- Status: Accepted and locally verified as E3 on 2026-11-02
- Scope: Independent foreground tool tasks and explicit SAF documents

## Decision

Tool tasks have a separate user-authored draft and reviewed immutable catalog.
AiToolProvider uses the existing OpenAiCompatibleProvider HTTPS endpoint and
credentials after the existing network/model gates. It sends only the task,
selected workspace, declared tools and bounded approved results. No editor,
clipboard, personal dictionary or implicit page content is available.

AiToolRun serializes model calls, approvals and execution. A task permits at most
32 tools, 20 model rounds, 40 calls and five minutes. Each call requires a fresh
explicit approval of its tool, arguments and destination. Unknown tools,
malformed arguments, repeated call IDs and unsupported schemas fail closed.
Schema descriptions and remote annotations do not grant authority. Configuration,
data, lock and activity revocation cancel the task; late results cannot reach a
new owner. Uncertain writes stop without automatic retry or rollback claims.

AiFileToolExecutor owns only temporary content URIs selected for this task.
Read/search accepts bounded UTF-8 text/Markdown/JSON/CSV and returns bounded
excerpts. Model arguments use local task file IDs, never paths or URIs. Reads
require explicit disclosure of the selected document and current provider.
Save As previews content and uses ACTION_CREATE_DOCUMENT; after a fresh picker
result the user confirms the selected destination before writing. There is no
arbitrary overwrite, deletion, tree permission, persistent grant or indexing.
A cancelled creation may leave an empty provider-created document; interrupted
dispatched writes are uncertain and are never retried automatically.

The private task Activity uses FLAG_SECURE and no saved private view state,
autofill or content capture. Picker handoffs suspend dispatch and have a bounded
single-use owner token; they cannot keep a model or file write running in the
background. Normal backgrounding cancels the task. Later E5 may explicitly
authorize bounded non-GUI continuation; no such mode is implied here.

Device operations remain in the existing separate device broker/task surface,
with their target/window and Stage 6 approvals intact. They are not generic
file-tool executors. This stage adds no permission or network destination.

## Consequences and validation

Explicit approvals add interaction cost but keep untrusted model output from
directly reading or modifying files. Task and file references are memory-only.
SAF providers can access their own cloud; this is disclosed separately from AI
submission. Validate malformed/schema/ID attacks, cancellation and replay,
limits, rejected approvals, URI revocation, invalid UTF-8, oversized documents,
uncertain writes, private lifecycle and existing provider/device regressions.
