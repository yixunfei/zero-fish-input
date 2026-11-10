# ADR 0035: Public-API virtual-screen feasibility

- Status: Accepted E6 route, locally verified (2026-11-02)

## Decision

The owner selected ordinary Android APIs and accepts third-party restrictions.
AiVirtualScreen creates a private 640 x 360 OWN_CONTENT_ONLY/PRESENTATION display
with a fixed public resource in our own Presentation. Its TextureView preview
is local, transient and never saved or sent. There is no MediaProjection,
main-screen mirroring, third-party launch, input injection or device-agent port.
No new permissions, exports, libraries or privileged backend are introduced.

The explicit capability-check dialog describes creation, own content and
third-party observation/control separately. Creating this preview does not
claim background third-party automation. It closes on page exit, lock, settings
revocation, display loss, explicit Stop, or a 120-second deadline. The host dialog
is secure; the separate display contains only a fixed public fixture and no
editor, model, file, memory or user-authored content. Production device actions
keep their existing foreground target/window binding and never fall back from
this display to the main screen.

## Consequences and alternatives

Public APIs demonstrate resource ownership and preview feasibility, but do not
provide a supported general-purpose foreign-app background control backend.
Eta's privileged route was explicitly excluded. A future implementation would
need a separately approved supported backend and observation/action isolation.
Until then, capability guidance and the own-app preview are the delivered E6
scope; a fake enable switch or weaker foreground check is rejected.

## Validation

Platform tests require a non-default private display, rejection of a foreign
test Activity's launch eligibility, invalid-surface failure and release. They
do not launch the foreign Activity or interpret eligibility as a grant.
Check actual preview and page/lock cleanup on the isolated emulator. OEM and
physical-device behavior remains separate from emulator evidence.

API 37 verification passed: a non-default private display renders the fixed
public fixture into a TextureView, the external test Activity is ineligible,
invalid surfaces fail closed, and explicit close/surface loss removes the
display. The actual 640 x 360 public preview was visually inspected. All 59
non-live AI platform cases passed together, including opening the production
capability dialog and releasing its display when the task page finishes.
Lock cleanup is wired to the existing task-page revocation lifecycle; arbitrary third-party operation
and physical OEM display behavior have not been validated or enabled.

References: [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager),
[launch eligibility](https://developer.android.com/reference/android/app/ActivityManager#isActivityStartAllowedOnDisplay(android.content.Context,int,android.content.Intent)).
