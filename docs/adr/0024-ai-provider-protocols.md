# ADR 0024: Explicit AI provider protocols

Status: accepted (2026-10-14)

## Context

Users report HTTP 404 from providers they can use in other clients. The existing
transport always appends Chat Completions routing, even to a full Responses or
Messages URL, and cannot select those protocols. A catalog 404 and a generation
404 also share an ambiguous error. The 16-token model probe is insufficient for
some reasoning models; a token-limit response is indistinguishable from malformed
data. These defects do not establish the cause of every reported remote 404.

## Decision

The user approved Chat Completions, Responses and Anthropic Messages as explicit
per-provider choices. They share the existing sole network adapter. The editor
shows the resolved generation/catalog destinations and offers streamed or complete
JSON responses. A protocol change cancels discovery and testing. There is no
protocol sniffing, alternate-route retry, duplicate generation or origin change.

`ai-api/AiProtocol` owns the wire selection. `AiEndpoint` accepts a base or a full
matching generation URL. A bare origin uses `/v1`; custom base prefixes remain
intact. Mismatched full routes, catalog URLs, encoded/ambiguous path segments,
userinfo, queries, fragments, non-HTTPS origins and redirects are rejected.

The single platform exchange sends Bearer authentication for Chat/Responses and
`x-api-key` plus `anthropic-version: 2023-06-01` for Messages. Discovery is an
explicit bodyless same-origin GET. A missing catalog does not block manual models
or generation tests. Model capabilities are never inferred from catalogs.

Pure request encoders and bounded response readers are separate from transport.
Responses uses `store: false` and only the user's selected history, never remote
conversation IDs or tools. Messages uses explicit system and user/assistant turns.
Text and selected images are supported by all three; audio is restricted to Chat
Completions. Unsupported media fails before I/O. There is no new network library,
permission, exported component or data source.

Chat streams require a text `stop` and `[DONE]`. Responses requires a completed
response with matching accumulated text and no executable output; Messages requires
ordered content blocks, a successful stop reason and `message_stop`. Truncation,
tool output, malformed JSON, oversized input and late callbacks never produce an
insertable completion. Reasoning is not displayed or stored. Errors expose only
fixed categories, operation and HTTP status; server error bodies remain unread.

The model probe uses the fixed public prompt, 512 output tokens and at most 30
seconds. An exhausted budget is reported distinctly and is not claimed as a full
successful answer. Users can test the actual workbench explicitly; no retry occurs.

The user chose to discard existing Provider configuration rather than migrate it.
Format 5 persists protocol and streaming. Formats 1-4 trigger deletion of the AI
configuration file and its dedicated key, then return disabled defaults. Deletion
failure locks access until explicit clear succeeds. Conversation storage and keys
are unaffected; unknown/current malformed formats fail closed.

## Consequences and alternatives

Existing users must re-enter providers and enable networking explicitly after
the reset. Automatic protocol fallback was rejected because it can duplicate paid
requests and send content to an unintended route. A broad transport rewrite was
unnecessary: the existing deadline, cancellation and privacy boundaries remain.

Deterministic Android fixtures validate protocol behavior but cannot prove a
particular vendor/model accepts credentials or all three protocols. Live acceptance
requires a valid temporary credential, fixed public content and explicit reporting
of remote failures. No credential belongs in the repository or validation report.

References: [Responses request schema](https://developers.openai.com/api/reference/resources/responses/methods/create),
[Messages API](https://platform.claude.com/docs/en/api/messages/create).
