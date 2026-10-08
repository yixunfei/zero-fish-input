# AI page references and conversation management

Date: 2026-10-07
Status: approved by the user on 2026-10-07; implemented and verified on 2026-10-07.
Results and remaining device coverage: [validation report](ai-page-context-validation.md).

## Goal and observed state

The user wants to select text from another application's current page as AI
context and improve conversation management for keyboard use.

The existing implementation provides an isolated draft, generation, explicit
insertion, new/open/delete conversation actions and optional encrypted history.
`AiWorkbenchController` projects recent messages into request history; users
cannot select individual messages. `AiWorkbenchPanelView` combines the saved
conversation list and transcript in one scrolling area and has no rename action.
`ZeroInputService.importAiContent` starts a new conversation and fills the draft,
rather than adding independently removable references.

`AiComposeActivity` contains SEND/PROCESS_TEXT parsing, but its current manifest
declaration is nonexported and has no external intent filters. Earlier documents
describe an exported entry that is not present in the current manifest. The
implementation and documentation must be reconciled for the chosen import scope.

## Recommended product flow

1. In an eligible editor, open AI and tap **Reference page**.
2. If the separate page-reference setting or Android accessibility authorization
   is missing, show its purpose and limits, then let the user open system settings.
   Returning from settings never captures automatically; the user taps again.
3. Capture one bounded snapshot of text exposed by the current source window's
   accessibility tree. Show a checklist with nothing selected initially.
4. The user selects blocks, reviews their full text, and adds them to a separate
   reference area. References can be removed individually or cleared together.
   The current question and conversation stay intact.
5. Show selected history and page-reference counts and a preview of the text
   context before Send. Only Send creates a generation request. The response stays
   in the workbench until an explicit, session-validated Insert action.

This route needs an optional Android accessibility service. An ordinary input
method has no general API for enumerating another application's entire page.
Custom-drawn, inaccessible, protected or offscreen text may be unavailable. The
first implementation does not add screenshots, OCR, automatic scrolling or gestures.
User-initiated text sharing is the lower-permission alternative, but cannot provide
the requested page-wide checklist by itself.

## Proposed security exception

Approval must explicitly allow one additional source: a user-triggered snapshot
of another application's visible, noneditable accessibility text for AI references.
This is an exception to the current external-page-reading prohibition, not a
general authorization to observe input or application activity.

- The feature is separately disabled by default. Android authorization alone does
  not activate it. The service performs no text reads in ordinary event callbacks.
- The platform service is protected by `BIND_ACCESSIBILITY_SERVICE` and obtains
  only the capabilities needed for this flow. It does not perform actions, gestures,
  screenshots, clipboard access or background text collection.
- A request requires an active eligible IME session, a current user action and a
  source application/window matching that session. Missing or ambiguous identity
  fails closed. IME, system, overlay and ZeroInput windows are excluded.
- Exclude editable/password nodes and their subtrees, invisible nodes, and nodes
  explicitly marked sensitive where the platform exposes that information. Source
  applications may incorrectly label sensitive displayed text; selection preview
  cannot guarantee semantic detection of secrets in ordinary text labels.
- Traversal runs off the key path with cancellation and explicit limits: proposed
  maximum 512 visited nodes, depth 32, 128 candidate blocks and 32,768 UTF-16 units.
  Signal incomplete capture instead of implying the entire page was read.
- Proposed selected-reference limits: 16 blocks, 4,096 UTF-16 units per block and
  16,384 units for combined selected references and history. Oversized selection
  requires explicit reduction; never silently omit a selected reference at Send.
- Unselected snapshots expire after 30 seconds. Selected references are transient
  for the current workbench session. Source/editor changes, panel close, lock,
  permission revocation, configuration change or data clear invalidate pending
  capture and references. Late results cannot attach to a different conversation.
- References enter the existing request transport as quoted, untrusted source
  content. They cannot create SYSTEM messages, invoke tools or change credentials.
  Source package names, window IDs and accessibility metadata never leave the device.
- Raw page references are not persisted in this iteration. Existing saved questions
  and AI answers may themselves contain information from those references. Show this
  distinction when explaining local history; do not promise otherwise.
- Existing sensitive-editor, clipboard, private-data, encryption, offline-input and
  explicit-insertion controls continue to apply.

Before implementation, record the approved exception in AGENTS.md and a new ADR;
update architecture, threat model, SECURITY.md and precise privacyCheck allowlists
alongside code. Do not weaken general network or clipboard checks.

## Conversation and history interaction

- Provide separate conversation-list and conversation-detail views within the AI
  panel so saved titles do not occupy the response-reading area.
- Support new/open/rename/delete, current-chat identification, empty/loading/error
  states, and a clear distinction between temporary and locally saved conversations.
- Keep conversation saving off by default. With saving off, only the current
  temporary conversation exists; leaving its workbench/session releases it.
- Opening a saved conversation shows its messages and an explicit context selection.
  A visible recent-history preset may be selected by the user; individual messages
  can be included/excluded without deleting them from the saved transcript.
- Keep history selection stable while composing. New chat and switching chat/model
  reset context choices and invalidate old callbacks. Context changes cancel any
  running request and revoke its insertable result.
- Renaming and deleting run on the serial storage worker, with stale-operation,
  clear/delete and failed-write protections. Confirm deletion of the named chat.
- Retain conversation format 1 and configuration format 4. Rename uses the existing
  title field; reference choices remain in memory. No legacy format or migration
  layer is introduced. Any later need for persisted references requires a separate
  format and retention decision.

## Implementation sequence and module ownership

1. Approve this flow and the page-read exception; refine the ADR and negative-test
   cases before adding the platform surface.
2. `ai-api`: immutable bounded reference/context values and validation, independent
   of Android. `app/ai`: context selection and request projection, separated from
   transport, encrypted storage and page capture.
3. `app`: a dedicated accessibility adapter, one-shot request coordinator, lifecycle
   invalidation and opt-in settings. The adapter exposes no network or persistence
   port. Service integration remains orchestration only.
4. `ime-ui`: checklist/reference preview, history selection and separate conversation
   management presentation, localized Chinese/English strings and accessibility.
5. `user-data`: rename within the existing encrypted format and regression coverage
   for save/delete/rename races. Reuse existing storage and encryption abstractions.
6. Reconcile import documentation and entry points. Do not silently enable the
   currently nonexported sharing component unless that scope is approved as well.
7. Run the checks below and record actual outcomes, remaining platform limits and
   manual acceptance steps. Work proceeds in one agent; no parallel agents planned.

## Verification and acceptance

- Unit tests: exact selected context, stable ordering, duplicate blocks, limits,
  untrusted text, no implicit send, unchecked-text exclusion, repository failure,
  rename/delete/clear races and no restoration of revoked data.
- Capture tests: disabled switches, missing authorization, mismatched windows,
  sensitive/unknown editors, editable/password/invisible/sensitive nodes, excessive
  trees, expiry, duplicate callbacks, cancellation and worker rejection.
- Platform tests use public fixture applications only. Prove that tapping capture
  alone sends nothing, unchecked text never enters a request, and window/editor,
  chat/model, lock and permission changes revoke pending results.
- Exercise compact portrait, landscape, large text and light/dark themes; keep
  Send/Insert reachable and prevent reference lists from growing the keyboard
  without bounds. Ordinary Chinese/English typing remains functional.
- Run affected module unit tests, `:ai-api:test`, `privacyCheck`, Debug/Release merged
  manifest checks, `:app:lintDebug`, Debug APK and instrumentation compilation,
  followed by available emulator/device tests. Do not describe unrun checks as passed.
- No real provider request is needed for constructed behavioral tests. Live service
  and third-party application compatibility, if exercised, are reported separately.

## Plan review

The recommendation matches the requested in-keyboard checklist without introducing
an OCR dependency or changing stored formats. Its material trade-off is the broad
platform capability granted to an accessibility service, despite narrowly scoped
application behavior. OS/vendor restrictions and source accessibility quality limit
coverage. A fixture-based proof of source-window binding and prompt cancellation is
required before treating this route as complete; missing identity never triggers a
broader scan or permission fallback.

The user approved the recommended one-shot accessibility route and conversation
workflow. External SEND/PROCESS_TEXT publication is not included; the existing
nonexported content review entry remains internal.
