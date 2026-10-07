# ADR 0015: Optional AI workbench boundary

Status: accepted

## Context

The keyboard needs question answering, planning, rewriting and translation while
preserving the offline input path, encrypted personalization and private clipboard
boundaries. The user explicitly accepted an opt-in network exception. RIMES is used
as a product reference for a user controlled text buffer and explicit delivery; its
macOS implementation and permissions are not copied into the Android input method.

## Decision

- AI is disabled by default and requires both the AI switch and the network switch.
  The only runtime network transport is the app-owned `OpenAiCompatibleProvider`.
  It accepts HTTPS endpoints, rejects user info, query/fragment data, port zero and
  redirects, and bounds the request, timeout, SSE line, stream and output sizes.
- Text typed into the AI workbench's separate local draft, explicitly imported
  context, selected attachments and an explicitly selected conversation can be sent.
  The workbench never automatically reads the
  external editor, editor selection, system clipboard, private clipboard vault,
  personal lexicon or emoji history. The draft has its own conversion session and
  uses a no-op personalization store.
- The user approved multi-provider OpenAI-compatible Chat Completions and explicit remote
  model discovery or manual model lists. Each model has explicit image/audio capability flags. A mismatch
  fails before transport; endpoints, models and credentials are locally encrypted.
- The exported `AiComposeActivity` accepts bounded plain text from SEND/PROCESS_TEXT.
  It never sends requests, reads editor selections or returns content to the sender.
  File/image/audio input is exclusively a user-selected content URI from OpenDocument;
  sender-supplied streams and remote URLs are ignored. No new permission is needed.
  Review confirmation transfers one memory-only draft to `AiContentInbox` for two
  minutes. A separate tap in an eligible keyboard session claims it; submission and
  result insertion retain the normal session checks. No old InputConnection survives
  document-picker navigation. Settings/data clear revoke unclaimed content.
- Attachments allow UTF-8 plain text, JPEG/PNG/WebP, WAV/MP3, at most two and 1 MB
  total. Text files have a 16,384-character limit and strict UTF-8/control validation.
  Document reads run on a bounded worker with lifecycle cancellation; no persistent
  URI grant, disk cache or attachment history is created. Removed, expired or revoked
  byte buffers are wiped. Temporary JVM serialization strings cannot be reliably wiped.
- Password, PIN, unknown, email, URI, incognito, no-personalized-learning,
  and learning-disabled sessions fail closed. The user approved independent AI
  drafts in ordinary text editors that only request NO_SUGGESTIONS. This exception
  never permits personalization or overrides combined privacy restrictions.
  A result remains in
  the workbench until the user taps **Insert result**, which revalidates the
  current editor session before committing it.
- Saved conversations are optional, limited, encrypted with a dedicated Keystore
  alias under `noBackupFilesDir`, and managed through background operations. The
  UI first receives bounded summaries; selecting a row loads its history. Selection,
  deletion, editor changes, settings changes, service destruction and data clearing
  revoke request and persistence generations.
- The provider does not log endpoint credentials, request text, response text or
  conversation content. Configuration and history are separate encrypted stores.
- An explicit settings model test reuses the sole transport with the selected
  saved provider/model, a fixed public prompt, 16 output tokens and at most 30 seconds.
  It requires both network switches, has no history/attachment/editor ports, does
  not persist or expose an insertable result, and is cancelled on dismissal,
  backgrounding, configuration revocation and data clear. Only safe status categories
  are displayed; server error bodies are never read.

## Consequences

The manifest contains `INTERNET`, an intentional user-approved exception to the
original zero-network rule. Core input, dictionaries, personalization, emoji and
the secure clipboard remain usable with networking disabled or unavailable. An AI
provider outage never blocks or changes ordinary input.

The AI workbench is a compact keyboard panel rather than a background assistant:
there is no automatic context capture, automatic insertion, notification preview,
remote configuration, telemetry or cloud synchronization. Real provider behavior,
vendor editor behavior and device authentication still require device validation.

## Provider interoperability amendment (2026-10-07)

The user approved explicit model discovery and repair of real provider connections.
API base addresses (including versioned proxy prefixes) and full Chat Completions
addresses resolve on the same HTTPS origin to `chat/completions` and `models`.
A bare origin uses `/v1`. Embedded URL credentials, query/fragment data, dot segments and
redirects remain rejected. Configuration format 4 and conversation format remain
unchanged; no stored data is cleared or migrated for this amendment.

An explicit Fetch available models action in the protected editor may send the
entered endpoint/key before saving, but only while both saved network switches
are enabled. GET `/models` has no body or editor/history inputs. Responses are
bounded to 512 Ki characters and 2,048 entries; only validated model identifiers
are used. The user chooses up to 32 models, applies them to the draft, and saves
explicitly. Manually entered models remain selectable; unavailable discovery never
blocks a model test. Discovery never infers image/audio capabilities.

Model tests use an actual generation request with the fixed public prompt and
16-token output cap, now with a maximum 30-second deadline. A catalog entry or
HTTP 200 alone does not establish usability. HTML successes are rejected without
reading the body. The transport accepts bounded SSE or a complete JSON Chat
Completions result; truncation, non-text/tool finishes and empty results fail.
There is no automatic alternate-endpoint retry or duplicate generation request.

Credentials and selected model bind when a request is queued. Deadlines start at
submission, and cancelling one request cannot remove unrelated queued requests.
Settings, endpoint/key/draft edits, backgrounding and dialog dismissal invalidate
model discovery; stale results cannot rewrite the draft. Configuration changes
also revoke workbench results, context and queued persistence before delayed
observer delivery. Existing explicit history selection remains available.

The approved quick model selector is workbench-local: it can choose only saved
models under the active provider and starts empty context. It does not rewrite the
default or fetch models on opening. The effective request model must pass that
provider's membership and media-capability checks. New chat cancels pending
generation and clears the draft/context while retaining this local choice.
Leaving the workbench restores the saved default. No stored format or network
surface is added.
