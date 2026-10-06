# AI workbench assessment and implementation

## Reference and decision

Reviewed RIMES at commit `6a01bdf563182ace347a1d4665f9b403236d664c`:

- [README](https://github.com/scholay/rimes/blob/6a01bdf563182ace347a1d4665f9b403236d664c/README.md)
- [Architecture](https://github.com/scholay/rimes/blob/6a01bdf563182ace347a1d4665f9b403236d664c/SYSTEM-ARCHITECTURE.md)
- [OpenAI Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

RIMES provides a buffer before delivery, connector-driven generation, translation,
separate conversations and explicit insertion. Its implementation is for macOS,
with IMK, Accessibility, desktop CLI connectors and clipboard integration. Those
platform and data-access paths do not fit ZeroInput's Android privacy boundary.
No RIMES source, dependencies, dictionaries or assets are copied into this change.

| Reference capability | ZeroInput implementation |
| --- | --- |
| Text buffer before insertion | Independent in-memory draft with local Chinese/English conversion |
| AI connectors | Replaceable provider port; one HTTPS Chat Completions transport |
| Mailbox conversations | Optional encrypted history; new/select/continue/delete controls |
| Translation | Explicit one-tap translation with eight selectable target languages |
| Focus-aware delivery | Current session and InputConnection checked on explicit insert |
| Clipboard capture and fallback paste | Excluded; no clipboard integration in AI |
| CLI/plugin/remote inbound execution | Excluded; no code execution or inbound service |

The user approved optional networking and then approved implementation. This is
an explicit exception to the original no-INTERNET rule, documented in
[ADR 0015](adr/0015-ai-workbench-boundary.md). All other clipboard, encryption,
backup, sensitive-editor and offline-input protections continue to apply.

## Reviewed implementation plan

1. Define platform-free request, bounded conversation, action, provider and policy
   contracts in `ai-api`; keep the engine and personal-data ports independent.
2. Add an optional HTTPS transport with cancellation, no redirects, bounded SSE
   parsing, no logs, and no third-party networking SDK.
3. Add separate encrypted configuration and history stores with dedicated keys,
   bounded decoding, serial background writes and deletion failure handling.
4. Extend the keyboard with a draft/candidate workspace, Ask/Plan/Polish/Rewrite/
   Translate actions, streaming output and a separate insert action. Compose draft
   text through its own conversion session without personal reads or learning.
5. Wire configuration, transient/persisted chats, explicit history selection,
   continuation and deletion. Invalidate stale work at UI, editor and data boundaries.
6. Add regression tests for negative privacy gates, queued callbacks, stale reads,
   failed deletion, generation races, malformed SSE and limits. Run Kotlin tests,
   privacyCheck, Lint, Debug packaging, platform-test compilation and device checks
   when an authorized device environment is available.

## Boundaries and limits

AI and network switches default off. Conversation saving also defaults off.
Ordinary keys never schedule network work. Draft conversion has no personalization
store and no external editor connection. Only a completed response can be inserted,
only once, after the user taps Insert result in the original active editor.

Input is limited to 16,384 UTF-16 units, output to 32,768, history to 20 messages
per saved chat, and chat count to 32. Requests use at most 12 recent history
messages within 16,384 units. The JSON request is limited to 256 KiB, SSE lines to
16,384 characters and total SSE input to 2 Mi characters. The encrypted history
plaintext payload is limited to 4 MiB before persistence/decoding.

Listing chats decrypts the bounded history file temporarily on the storage worker
and delivers summaries only; full messages are retained by the workbench only for
an explicitly selected or currently active chat. There is no process-wide history
cache. JVM strings and native engine internals do not provide verifiable memory
zeroization; owned byte buffers are wiped and UI/session references are released.

HTTPS protects transport, but the chosen provider receives the explicitly sent
text, selected history and normal connection metadata. Provider retention and
model correctness are outside ZeroInput's control. AI results are plain text; no
HTML, Markdown execution, URL fetching, tools or commands are executed.

## Acceptance procedure

1. With AI disabled, type Chinese and English, select candidates and use the
   private clipboard. There must be no AI request or new clipboard access.
2. Configure a test HTTPS endpoint/model and test key. Enable AI and networking.
   Open AI, type `nihao`, select a Chinese candidate and verify the host editor
   has not changed. Exercise English, backspace, candidate expansion and rotation.
3. Submit each action. Check streaming, Stop, translation language selection,
   empty input, service errors and timeouts. The host changes only on Insert result.
4. Enable history saving. Create a chat, continue it, select it again, delete it
   using the confirmation button, and clear all AI data from settings.
5. Switch editors while generating or loading history. Tighten privacy, hide the
   keyboard, switch panels or change endpoint. No late response may reappear or
   be inserted. Password/PIN/email/URI/incognito/no-learning/unknown fields must
   hide or reject the AI workspace. An ordinary text field with only
   NO_SUGGESTIONS allows an independent draft; combined restrictions still block AI.
6. Verify 320dp portrait, landscape, light and dark themes; API-key dialog and AI
   workspace must be protected from screenshots and saved view state. The private
   clipboard must still require its original authentication/confirmation flow.

Validation outcomes are recorded in [ai-workbench-validation.md](ai-workbench-validation.md).
