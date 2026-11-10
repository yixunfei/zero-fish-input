# ADR 0029: Standalone AI entry in the existing APK

- Status: Accepted by the owner on 2026-11-01
- Scope: Launcher entry, independent chat host and device-agent task preparation

## Context

The owner requested improvements inspired by [Eta](https://github.com/Mangi-11/Eta)
and confirmed one APK with an independent desktop AI entry and panel. Eta's
[runtime design](https://github.com/Mangi-11/Eta/blob/main/docs/AGENT_RUNTIME.md)
informs the separation of entry surfaces, task ownership, cancellation and
observable progress. No upstream code or additional privileges are adopted.

## Decision

1. The existing package provides a second launcher icon, `zero fish AI`.
   `AiAssistantLauncherActivity` is the only new exported component. It accepts
   only MAIN/LAUNCHER with no data or MIME type, creates a new explicit Intent
   for the nonexported `AiAssistantActivity`, and never forwards extras, URI
   grants, ClipData or commands. Opening the entry never submits a task. Its
   launcher filter has priority -1, keeping the existing keyboard settings
   Activity as the package's default launch destination.
2. Chat uses `AiWorkbenchController` with an explicit standalone generation
   policy and its own request coordinator. It shares the configured provider,
   bounded transport, encrypted optional history and serial persistence worker.
   It has no external editor, insertion, page-reference, attachment-import or
   clipboard port. It never grants personalization access. Global learning-off,
   incognito, screen lock and both existing network switches remain gates.
3. Chat history is selected and reviewed using the existing bounded context
   controls. Saved history remains opt-in; this change adds no storage format,
   migration, automatic model discovery, automatic submission or network path.
   Leaving the chat cancels and clears its transient state; recreation never
   restores a private draft or replays a request. The screen is FLAG_SECURE and
   excluded from recents; autofill, content capture and instance-state content
   are disabled. Keyboard-window capture rules remain unchanged.
4. Chat opens the existing private device-agent Activity. Device tasks continue
   to use the process-owned coordinator, broker, approval overlay and capability
   gates. Explicit Stop acts on the coordinator even after entry from a different
   Activity instance, and also cancels a pending target-app launch. Returning to
   ZeroInput may revoke a foreground-bound task; the existing in-task overlay
   remains the place to stop or approve it without leaving the target.
5. Target selection queries only launchable activities and filters them through
   the existing local target policy. MAIN/LAUNCHER package visibility is declared;
   QUERY_ALL_PACKAGES is absent. Labels and the local app list are not sent to
   the model. Manual package entry remains available for eligible targets.
6. A start click creates a single-use ten-second launch guard, based on elapsed
   realtime including sleep. Configuration, policy or data-generation changes,
   cancellation, timeout, return to the panel or destruction revoke it. Starting
   a task checks the same guard immediately before handoff. A selected document
   is still read only through the approved Stage 6 tool and approval.

## Consequences

One install and configuration serve the keyboard, full-screen chat and device
tasks. Their transient drafts and cancellation ownership remain distinct. Root,
shell, remote MCP, default-assistant integration, arbitrary files, background
collection and permanent memory are outside this decision. Existing limits,
default-off settings and per-operation approvals remain in force.

Validation covers launcher filtering and clean handoff, disabled/privacy states,
no request on entry/recreation, stale callbacks, launch expiry and single use,
target policy, Stop availability, large fonts, rotation and themes, JVM tests,
privacy scan, both merged manifests, Lint and Debug main/test APKs. Live-provider
and physical-device acceptance are reported separately.
