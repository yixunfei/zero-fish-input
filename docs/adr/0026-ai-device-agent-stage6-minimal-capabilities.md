# ADR 0026: AI device agent stage 6 minimal capability gates

- Status: Accepted and implemented for the documented direct-operation subset
- Date: 2026-10-20
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
   access is off by default. Notification-triggered operations remain inside the
   active task and require a fresh approval for each read or follow-up action;
   background rules and automatic replies are not included.
4. Calls, messages, contact/calendar writes, media deletion and system-setting
   changes require a fresh per-action approval that displays the exact target
   and effect. Approval is single-use and never authorizes later actions.
5. Camera/microphone capture is task-scoped and off outside an explicit capture
   action. No continuous or hidden capture is included.
6. Files and media are addressed only by a URI selected in the current task.
   The picker accepts the small allowlist already supported by the attachment
   reader; no storage permission, directory traversal, URI persistence or
   arbitrary destination is exposed. Reads are bounded by the remaining
   provider attachment budget and deletes require a separate approval.
7. Screen recording remains a separate review item. It is not included in the
   direct-read/action implementation scope requested so far.

## Implemented operation matrix

The application now exposes a static tool for each operation below. Every
tool is independently enabled in settings, is bound to the current task and
foreground package, and consumes a single approval. The Android adapter also
checks the relevant runtime or special permission immediately before access.

- Notifications: read at most five active notifications for the explicitly
  named package; no notification history, replies or background rules.
- Files and media: read or delete the one user-selected `content://` URI;
  media MIME types are checked against the provider result.
- System settings: read or modify four non-security `Settings.System` keys;
  secure settings, permissions, default IME, network and developer options are
  outside the catalog.
- Camera and microphone: one in-memory JPEG frame or at most one second of
  PCM/WAV audio; no recording, preview persistence or background capture.
- SMS and telephony: read the newest record for one explicit number, send one
  SMS or start one call; no bulk scan, deletion or call recording.
- Contacts and calendar: read an explicitly named contact/event and perform
  the fixed create/update/delete operations listed in the tool catalog; no
  provider crawl or agent history.

Runtime permissions are requested from separate operation-specific controls;
for example, SMS read and send permission are separate requests. Notification
access and system-settings write access use their own system settings pages.

The selected URI and sensor bytes are cleared when a task ends, is cancelled,
is invalidated or cannot deliver its result. A task with a user-selected item
does not send that item as an automatic attachment: the model must request the
corresponding fixed Stage 6 read tool, after which the result is sent once.

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

Validation covers the static catalog and argument limits, policy and grant
expiry, one-use approvals, task invalidation, provider tool encoding, selected
URI lifecycle and attachment budgets. Device permissions, notification access,
camera/microphone hardware and real providers still require physical-device
acceptance. Screen recording, background notification rules, bulk scans and
general file management remain out of scope.
