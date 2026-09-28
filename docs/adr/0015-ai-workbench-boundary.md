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
- Only text typed into the AI workbench's separate local draft and a conversation
  explicitly selected by the user can be sent. The workbench never reads the
  external editor, editor selection, system clipboard, private clipboard vault,
  personal lexicon or emoji history. The draft has its own conversion session and
  uses a no-op personalization store.
- Password, PIN, unknown, email, URI, incognito, no-personalized-learning,
  no-suggestions and learning-disabled sessions fail closed. A result remains in
  the workbench until the user taps **Insert result**, which revalidates the
  current editor session before committing it.
- Saved conversations are optional, limited, encrypted with a dedicated Keystore
  alias under `noBackupFilesDir`, and managed through background operations. The
  UI first receives bounded summaries; selecting a row loads its history. Selection,
  deletion, editor changes, settings changes, service destruction and data clearing
  revoke request and persistence generations.
- The provider does not log endpoint credentials, request text, response text or
  conversation content. Configuration and history are separate encrypted stores.

## Consequences

The manifest contains `INTERNET`, an intentional user-approved exception to the
original zero-network rule. Core input, dictionaries, personalization, emoji and
the secure clipboard remain usable with networking disabled or unavailable. An AI
provider outage never blocks or changes ordinary input.

The AI workbench is a compact keyboard panel rather than a background assistant:
there is no automatic context capture, automatic insertion, notification preview,
remote configuration, telemetry or cloud synchronization. Real provider behavior,
vendor editor behavior and device authentication still require device validation.
