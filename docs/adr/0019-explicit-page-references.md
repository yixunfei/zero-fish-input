# ADR 0019: Explicit page references and selected conversation context

Status: accepted by the user on 2026-10-07

## Context

The user needs to select text from another application's current page while using
the AI keyboard, without replacing the question. Ordinary IMEs cannot enumerate
arbitrary page text. The user approved the reviewed plan in
`docs/ai-page-context-plan.md`, including a separately enabled accessibility service.

## Decision

`PageReferenceService` is the sole external accessibility-node reader. Android binds
it using `BIND_ACCESSIBILITY_SERVICE`; it is not an accessibility tool and requests
only window-content retrieval and interactive-window metadata. It cannot act on
nodes, inject gestures, scroll, take screenshots or execute tools. No OCR or new
dependency is added. AI/network switches, a separate default-off setting, a live
authorized IME session and explicit Reference page action are all required.

`PageReferenceBroker` connects the service to an IME-owned review without retaining
text. One bounded worker traverses at most 512 nodes, depth 32, 128 blocks and
32,768 UTF-16 units, with a five-second application deadline checked between platform
calls. A blocking OS call may outlive this deadline; its result is discarded and the
bounded worker is not replaced with unbounded threads. There is no automatic retry.

Only a unique focused application window whose nodes match the current editor's
package is eligible. IME, system, overlay and own-app content are excluded. Node
metadata is checked before reading text. Invisible, editable, password and API 34+
explicitly sensitive nodes and their subtrees are skipped. Blocks must fit within
the window and avoid higher windows, including the keyboard. Missing identity or
ambiguous focus fails closed; collection never expands to other applications.

Event callbacks inspect only event-type metadata to revoke captured state. Both
window-state and window-set events revoke it without enumerating windows, including
source navigation that reuses the package/window ID.
They never retrieve node roots, event sources or event text. Screen lock, source
window change, service disconnect, settings/data clear, panel/session/chat/model
changes and review cancellation invalidate generations. Android node caching is
disabled on API 33+. Unselected snapshots expire after 30 seconds; selected page
references remain only in their current workbench context. Submit and Insert also
check the broker generation synchronously before pending UI revocation callbacks.
Explicit request cancellation clears selected references/history; ordinary draft
editing can stop output while retaining the user's current context choices.

The user explicitly checks blocks; nothing starts selected. At most 16 references
of 4,096 units each and 12 selected history messages share a 16,384-unit budget.
Excess selections fail as a whole instead of silently removing checked content.
The bounded collector indicates incomplete capture. Data crosses to the existing
provider only on Send, serialized as quoted reference strings in a USER message.
Source application/window identifiers never enter requests or history. References
cannot supply SYSTEM roles. Prompts describe references as untrusted, but this does
not guarantee that a model will ignore embedded instructions; results have no tools
or automatic effects and still require explicit insertion.

Saved conversations and their request projection are separate. Opening a saved chat
initially selects no history. Users may select recent history or individual messages
and preview the selection. New turns of the currently active chat join the visible
selection only when the complete pair fits. Changing context cancels active output.
Chat list, reference selection and answer views are separate. Rename uses the local
keyboard draft and restores the prior question after Save or Cancel.

Conversation format 1 and configuration format 4 remain unchanged. The new default-off
setting is a nonsensitive local preference. Rename modifies the existing encrypted
title field on the serial storage worker. Raw references are transient and not saved;
saved questions and responses may contain information from them. History saving
remains off by default, and disabling it retains the established deletion behavior.

The content review Activity remains nonexported. Confirmed internal imports become
separate references and attachments instead of replacing the question. Earlier
documents described external SEND/PROCESS_TEXT publication; the current manifest
does not publish that route and this decision does not enable it.

## Consequences and alternatives

Android grants a broad platform capability to the accessibility service even though
its implementation provides narrowly scoped explicit reads. The first enabling and
each system-authorization request disclose this purpose and limitation. Accessibility
authorization alone never triggers collection. Apps may withhold nodes, use custom
drawing, mislabel sensitivity, or expose partially visible text. Ordinary labels can
contain secrets; the user must inspect the checklist. The feature cannot promise
complete page extraction or semantic detection of sensitive information.

Manual sharing has a smaller permission surface but does not provide an in-keyboard
page checklist. Screenshot/OCR would add image capture, processing and dependencies;
it is excluded. Automatic collection would violate the approved boundary. Missing
or unsupported page text fails visibly without any broader fallback.

The original clipboard, editor privacy, personal-data isolation, encrypted history,
offline input and sole-network-provider boundaries remain mandatory. Negative tests
cover unchecked text, bounds, role injection, disabled/revoked access, late callbacks,
context replacement, rename failure and deleted-data resurrection. Platform tests
use fixed public text in a separate-UID fixture, never private application content.
