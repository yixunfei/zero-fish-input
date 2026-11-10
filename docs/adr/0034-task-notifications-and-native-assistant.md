# ADR 0034: Task notifications and native assistant entry

- Status: Accepted and locally verified as E5 on 2026-11-02

## Decision

Tool tasks get independent, default-off status notifications and a separate
per-task background choice. Notifications contain generic state only, with
immutable Open/Stop actions bound to an in-memory task nonce. Stale/replayed
actions cannot act on a different task. No draft, answer, role, file name,
endpoint or credential enters notification text or Intent payloads.

Only a visibly initiated, already configured non-GUI task may use the private
AiTaskForegroundService. It is non-sticky, has a hard 120-second bound, declares
FOREGROUND_SERVICE and uses shortService on Android 14+. Background mode requires
both notification opt-in and actual notification/channel availability. Background
work rechecks that availability every second and cancels outstanding
requests when it is revoked, without waiting for a model response. Pending
approvals wait for foreground return; notification actions cannot approve them.
Selected file reads and remote tools may continue, but Save As and device GUI
operations remain foreground-only. No queued chat is started in the background.
Lock, revocation, timeout, service loss and process death revoke the task.

The app owns bounded task state while its Activity is absent; callbacks must not
retain the destroyed Activity. Default tasks still stop on leaving. Background
results remain transient and bounded, with no automatic persistence or restart.

Optional native assistant selection uses AiVoiceInteractionService and
AiVoiceSessionService, exported only under BIND_VOICE_INTERACTION. Their private
text surface ignores invocation extras, assist structures, screenshots and
commands, and never submits on invocation. Disabled contextual collection is set
before session display. No hotword/microphone acquisition or lock-screen tasks.
If required by the platform metadata, a non-exported recognition component
explicitly rejects speech requests; it does not record or claim speech support.
Users select the assistant through the system role/settings flow.

## Validation

Debug unit tests, privacyCheck, Lint and requireRime x86_64 builds passed.
All 54 non-live AI platform cases passed on API 37. System ASSIST-key invocation
opened the selected service/session with disabled context flags 3, empty assist
and screenshot queues, and FLAG_SECURE. The original assistant role was restored.
The shell `cmd voiceinteraction show` lacked platform permission; the normal
ASSIST input path provided the actual invocation evidence. Runtime deadlines,
hidden approvals, service-loss handling and stale owners/actions have JVM tests.
Real provider/background network work and OEM assistant behavior remain untested.

Verify private/system-bound manifest entries and metadata, generic notification
content, stale actions, denied notification permission/channel, pending approvals
while hidden, 120-second expiry, service timeout/loss and Activity replacement.
Native invocation must discard all unsolicited data and cancel on hide/lock.
OEM behavior and real network requests are separately reported.

- https://developer.android.com/develop/background-work/services/fgs/service-types#short-service
- https://developer.android.com/reference/android/service/voice/VoiceInteractionService
