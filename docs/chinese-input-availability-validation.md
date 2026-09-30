# Chinese input availability validation

Date: 2026-09-22.

## Scope

Chinese conversion availability, native readiness and `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
Abbreviation and phrase-quality changes are outside this fix.

## Reproduction and changes

- The reported artifact was
  `app/build/outputs/test-apk/20260922-151748/zero-finish-input-0.2.0-debug-universal.apk`.
  Running the new keyboard tests against that exact APK reproduced the
  no-suggestions failure. Delaying native preparation in an ordinary editor
  still allowed the existing fallback to select the public fixture phrase.
  Thus initialization delay alone was not confirmed as the cause of letters-only
  output in every reported application.
- The old privacy policy disabled the entire engine for no-suggestions editors.
  Conversion now remains available, with personal reads, learning, model context,
  emoji history and next-word predictions disabled. Password and unknown editor
  restrictions still take precedence.
- A separate fault-injection regression reproduced false native readiness:
  a schema referencing a missing public dictionary still created a native session.
  Runtime initialization and prepared primary sessions now verify fixed public
  pinyin, candidate availability and selection before handoff. Probes run on the
  engine worker, use no editor or personalization store, and reset afterwards.
- Probe failure publishes FAILED and retains the memory-only fallback. This
  change does not claim to accelerate first-time dictionary compilation or expand
  the fallback's limited vocabulary.

## Automated device validation

Device: x86_64 Android 17 emulator (`FlutterTest`, API 37).

```powershell
./tools/test-input-experience.ps1 -TestClass dev.zeroinput.ime.RimeReadinessTest
./tools/test-input-experience.ps1 -TestClass 'dev.zeroinput.ime.NativePinyinTest,dev.zeroinput.ime.NineKeyPinyinTest,dev.zeroinput.ime.ChineseOptionsTest,dev.zeroinput.ime.ChineseInputAvailabilityTest,dev.zeroinput.ime.InputPipelineTest'
./tools/package-test-apk.ps1
```

The readiness regression failed before the fix and passed afterwards. Its
disposable public-asset directory is removed after the test; it never modifies
the application's normal Rime data. The keyboard regression failed on the
reported APK and passed after the fix. The 16-test combined device suite passed,
covering native selection, paging, configured sessions, slow preparation with
fallback selection, no-suggestions click/space/enter commits and rapid input.
The Enter observations above describe the September 22 artifact. Current
behavior commits raw composition on the first Enter and invokes the editor
action or newline only on the next Enter.

Use the repository test script to isolate native tests from a live IME session.
A preliminary direct Gradle run of native and keyboard tests together encountered
five null-engine failures due to configuration changes while another session
was live. The isolated run passed without changing those test assertions.

The packaging script passed the complete unit-test task, `privacyCheck`, app
Debug Lint, three-ABI native packaging with `-PrequireRime=true`, and APK signature,
ABI and permission checks. It produced:

`app/build/outputs/test-apk/20260922-181926/zero-finish-input-0.2.0-debug-universal.apk`

SHA-256: `c551a8ff18aa2dc7dbb4989ef3bc2ce669d3fb92bbab87a7b11ee20ccb55c26e`.
This is a Debug-signed test artifact; Release assembly was not run for this fix.
The universal APK was installed on the emulator and both keyboard availability
tests passed again against that exact artifact.

## Manual acceptance

1. Install the newly generated Debug APK over the old Debug package.
2. Open an ordinary text editor. During initial preparation, type `nihao` and
   select the fallback candidate. After preparation, repeat and confirm Chinese
   output using both a candidate click and Space. Enter should commit the raw
   pinyin without conversion; a second Enter should act on the editor.
3. Repeat in a non-sensitive editor that sets `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
   Switch to English: typing `hel` must keep that text without offering completions;
   Space must commit `hel ` unchanged. A no-personalized-learning flag alone must
   still allow public English completions.
4. Confirm password and unknown editor fixtures never show conversion candidates.
5. During delayed preparation, check the prominent progress strip while typing,
   opening tools and changing panels. Only the first Chinese input attempt shows
   a reminder. Ready hides progress; failure removes progress and offers retry.

Physical ARM-device behavior and the user's complete set of application editors
have not been verified. First-launch deployment time depends on device storage
and CPU; the fallback remains a small public dictionary.

## Follow-up: preparation visibility and prediction policy

The user selected persistent keyboard progress plus a first-input reminder.
The keyboard now shows a high-contrast, indeterminate strip outside the candidate
and tool panels. Its fixed resource message explains that basic input remains
available. The first Chinese key shows one non-blocking Toast per editor or
preparation attempt. No keys are buffered, dropped or replayed by the notice.
Ready/failed/hidden status, editor change, window hiding and view disposal cancel
the Toast. Failure continues to expose the existing retry control.

A further real-editor regression reproduced English completions under
`NO_SUGGESTIONS` before this follow-up (`build/no-suggestions-english-before.log`).
The policy now separates prediction permission from conversion availability and
passes it to both immediate and worker-prepared engines. Built-in English and
English language packs preserve raw composition without predictive candidates;
Chinese conversion remains enabled. Password/unknown restrictions and all
personal-data gates remain in place. No-personalized-learning alone continues
to allow public completions.

Validation completed for this follow-up:

- Complete unit tests, `privacyCheck`, app Debug Lint, universal native build with
  `-PrequireRime=true`, signature, ABI and permission checks via
  `./tools/package-test-apk.ps1 -Install -Serial emulator-5554`.
- Unit coverage for first/repeated preparation attempts, editor/reset/retry states,
  policy propagation through language switching and background preparation,
  English composition editing, and Chinese/English language-pack restrictions.
- Device checks of visible progress during fallback composition and tools,
  Chinese candidate/Space/Enter commits under the historical behavior, English prediction suppression,
  failure/retry controls, ready/release cleanup and attached light/dark screenshots.
- Layout checks at 320x640, 540x280 and 800x360 dp in light and dark themes, including
  visible status text and bounds of every displayed key. The initial layout-only
  fixture incorrectly used `isShown` on an unattached view; it was corrected to
  check visibility through the fixture root and actual measured bounds.
- All 20 combined device tests passed against the exact universal artifact below,
  including native pinyin, nine-key, configured Chinese sessions, availability,
  preparation UI, rapid input and repeated live rotation. Native tests ran with
  an alternate default IME to isolate their runtime, restoring ZeroInput afterwards.

Latest artifact (supersedes the earlier artifact above):

`app/build/outputs/test-apk/20260922-183528/zero-finish-input-0.2.0-debug-universal.apk`

SHA-256: `8d6b86e5aaaa12d18f71c9075164e8b2abac7fea19bc206e8afac1025b2fdb78`.

Logs: `build/chinese-progress-unit.log`, `build/chinese-progress-device.log`,
`build/chinese-progress-layout.log`, `build/chinese-progress-package.log`,
`build/chinese-progress-universal.log`. Attached public screenshots were inspected
at `build/preparing-light.png` and `build/preparing-dark.png`.

Physical ARM devices and Release assembly were not tested. This follow-up improves
status visibility and input policy; it does not accelerate initial Rime deployment
or change abbreviation/phrase quality.
