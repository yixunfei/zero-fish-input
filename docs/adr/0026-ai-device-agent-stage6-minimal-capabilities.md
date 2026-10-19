# ADR 0026: AI device agent stage 6 minimal capability gates

- Status: Accepted direction; per-capability implementation boundaries remain open
- Date: 2026-10-19
- Scope: Stage 6 capability review for the side-load/private-distribution device agent

## Context

The device agent can already observe a bounded foreground window and execute a
small fixed set of user-approved reversible actions. Stage 6 requests cover
capabilities with substantially different privacy and external-side-effect
risk: notifications, media and system settings, camera and microphone, file
selection, calls and SMS, and contacts and calendar.

The owner requested direct access and operation for these categories, rather
than relying only on system picker/compose surfaces. The device agent still
needs per-capability permission gates, explicit target scoping, revocation and
negative tests. A broad Android permission is not equivalent to permission for
the model to read every accessible record or perform every available action.

## Decision direction

1. Implement capabilities independently. Each has its own switch, disclosure,
   Android permission or special-access request, data minimization rule, target
   binding, revocation path and tests. The existing device-agent switch does not
   grant them.
2. Direct access is limited to the record or media item named or otherwise
   explicitly selected in the current task. No bulk contact/calendar/SMS/media
   scan, full notification history, arbitrary directory crawl or unrestricted
   settings mutation is implied.
3. Provider requests receive only the data required for the current task. No
   personal content is persisted as agent history or written to logs. Background
   access is off by default; the notification rule service, if implemented,
   needs a separate user-created rule with an explicit package and predicate.
4. Calls, messages, contact/calendar writes, media deletion and system-setting
   changes require a fresh per-action approval that displays the exact target
   and effect. Approval is single-use and never authorizes later actions.
5. Camera/microphone capture is task-scoped and off outside an explicit capture
   action. No continuous or hidden capture is included.
6. The Android platform cannot provide generic direct file access to arbitrary
   user documents under scoped storage without a broad special access. Such
   access is not approved by this ADR; the initial file implementation must
   stay within a separately defined media collection and file type.
7. Screen recording remains a separate review item. It is not included in the
   direct-read/action implementation scope requested so far.

## Data and lifecycle boundaries

- Permission results and provider reads are accepted only while the task
  generation and capability policy remain current. A cancelled operation,
  revoked permission, settings change or task cancellation clears transient
  buffers and pending actions.
- User-specified media and records are resolved locally; paths and provider
  exceptions are not sent to the model. Only bounded, explicitly requested
  fields or media content may enter the existing HTTPS provider.

## Rejected alternatives

- Requesting one shared permission for all stage 6 categories would create an
  unreviewable authority bundle and is rejected.
- A general-purpose file-management tool would permit hidden reads and writes
  beyond the user-specified media/files and is rejected.
- Using the existing accessibility switch as a proxy for notification,
  camera/microphone, telephony, contacts or calendar consent is rejected.

## Validation

Implementation remains pending per-capability action definitions. The first
subtask must choose a data domain and define exact read/write operations before
adding permissions. Every subtask needs oversize/unsupported input tests,
Activity recreation, settings/task invalidation, byte clearing, provider model
capability rejection and protocol-specific encoding where applicable. No
capability is enabled by this review record alone.
