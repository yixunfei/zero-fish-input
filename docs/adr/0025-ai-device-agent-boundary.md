# ADR 0025: AI device agent boundary

- Status: Accepted
- Date: 2026-10-19
- Scope: `ai-api` device-agent contracts and the Android operation-capable
  `AccessibilityService`

## Context

ZeroInput is expanding from an input method with an optional AI workbench to an
explicit device-agent mode. The mode can observe a bounded representation of the
currently focused application and execute a small set of reversible UI actions.
The reference implementation is OpenDroid, but this project only uses its
public design ideas. No OpenDroid source, model, permission set, or action
implementation is copied into ZeroInput.

An accessibility service has broad platform authority. The product therefore
needs a separate consent flow, a local policy gate, a fixed tool directory, and
short-lived task state. This decision also records the user's confirmed release
choice: side-loading or private distribution, with the complete stage 0-5
action set allowed by this plan. High-risk direct capabilities are reviewed and
approved separately by [ADR 0026](0026-ai-device-agent-stage6-minimal-capabilities.md).

## Decision

1. Device-agent mode is disabled by default. Enabling it requires an in-app
   disclosure, an explicit policy selection, and a separate Android accessibility
   authorization. Returning from system settings never starts a task.
2. The capability scope and approval mode are independent settings. Scope is
   either an exact package allowlist or the built-in full-support action catalog;
   approval is each action, once per task, or safe actions automatically. The
   full-support label does not grant root, ADB, shell, Shizuku, or system
   security bypasses.
3. The initial catalog is limited to `wait`, `back`, `home`, `scroll`, `click`,
   `long_click`, `swipe`, `input_text`, `ime_action`, and `open_app`. High-risk
   external side effects are absent. Adding an action requires a catalog entry,
   local argument validation, policy tests, privacy review, and a new ADR when
   it changes the data or permission boundary.
4. The stage 0-5 loop receives only the user-authored device task, the current
   bounded UI snapshot, and bounded redacted action results. It cannot read the
   host editor, system or private clipboard, notifications, files, contacts,
   messages, calls, calendars, credentials, or personal input data. Stage 6
   direct tools have a separate disclosure, capability switch, target grant,
   runtime/special permission and one-use approval boundary.
5. Every task is bound to a session generation, task ID, target package, window
   ID, and snapshot ID. A foreground package change, window/event invalidation,
   lock screen, service disconnect, setting change, cancellation, timeout, or
   Activity/service destruction revokes the task and pending approval.
6. The only network path is the existing `OpenAiCompatibleProvider` transport.
   Device-agent tool calls support the already configured Chat Completions,
   Responses, and Anthropic Messages encodings. No WebView, remote MCP, new
   transport, telemetry, or automatic fallback is introduced. Device-agent
   history is not persisted by default.
7. Screenshot consent is a separate two-level gate and defaults off. Settings
   provide a capability gate, while each standalone task requires a fresh
   per-task confirmation. On API 30 and above the accessibility adapter takes
   one bounded JPEG frame only while the task token, target window and unlock
   checks remain valid. The existing protocol codec sends it as a native image
   part only when bytes are present; the frame is never persisted and is
   cleared when the snapshot or task is invalidated. Unsupported, oversized or
   expired captures fail closed.
8. The operation-capable service remains separate from the read-only
   `PageReferenceService`. It uses `BIND_ACCESSIBILITY_SERVICE`, an in-task
   `TYPE_ACCESSIBILITY_OVERLAY` status/approval surface, and no
   `SYSTEM_ALERT_WINDOW` capability of its own.

## Consequences

The core loop is testable without Android: immutable models and a static action
catalog live in `ai-api`; policy evaluation, protocol parsing, coordination and
redaction live under `app/ai/device`; the service is the only platform adapter.
The existing ordinary AI text path keeps rejecting tool calls.

The current deliverable provides the usable task loop in the AI panel, a
separate `AiDeviceAgentActivity` with an in-memory action timeline, settings for
disclosure/scope/allowlist/approval/screenshot state, and a stop/approval
overlay. Activity recreation reattaches to the coordinator timeline without
starting a second task. The task may continue while the Activity stays behind the
selected target app; the broker revokes it when the target leaves the foreground.
Android 16 emulator tests cover the public fixture, redaction, screenshot bounds,
single-use approval/click, screen-off, foreground switching and service revocation.
Physical-device behavior, Activity recreation and live-provider acceptance remain
separate validation items, not implicit permissions. Stage 6 notification,
provider, sensor and runtime-permission behavior is documented in ADR 0026.

## Validation required

Unit tests cover bounded contracts, protocol rejection, package hard blocks,
sensitive input labels, snapshot mismatch, approval policy, queue rejection and
lifecycle revocation. Android 16 emulator tests additionally exercise service
authorization, target window capture, screenshot JPEG bounds, one-use action
approval, foreground switching, screen-off and service revocation. Activity
rotation/recreation, physical hardware and live-provider requests remain to be
validated before release. Run `privacyCheck`, merged-manifest checks, Debug lint,
unit tests, and an ABI Debug build; a real Rime Release build still requires
`-PrequireRime=true`.

