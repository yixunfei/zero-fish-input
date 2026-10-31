# Private-copy acquisition probe

Developer research APKs, separate from the production Gradle build and IME.
Use only the supplied public fixture. This experiment does **not** save to the
private vault: confirmation discards the draft. It tests acquisition before
making an integration or OCR-adoption decision.

Implementation authorization and gates: [project plan](../../docs/clipboard-product-repair-plan.md).
Current project status: [project-progress.md](../../docs/project-progress.md).

## Components and boundaries

| Component | Responsibility | Capability |
| --- | --- | --- |
| `fixture` APK (`dev.zeroinput.copyfixture`) | Public editor, custom message menu, Canvas without text nodes, offline WebView, secure-window toggle, share/drag sender | No permissions; bundled public data only; WebView blocks network loads, file and content access |
| `probe` plain flavor (`dev.zeroinput.copyprobe`) | Bounded text handoff, consent, one acquired frame, crop/review | Two normal foreground-service manifest permissions; no OCR dependency |
| `probe` OCR flavor (`dev.zeroinput.copyprobe.ocr`) | Same flow plus one local line recognizer | Pinned PP-OCRv5 recognizer and existing reduced ONNX Runtime; no page detector |
| Test APKs | Exact comparisons, lifecycle and public-frame checks | No UiAutomation, accessibility service, runtime shell helper or permission-grant bypass |

Minimum API 34, compile/target API 36. Single-app sharing availability and its
picker depend on OS build (AOSP introduced it in Android 14 QPR2). The production
minimum SDK is unchanged. API 26-33 compatibility is outside this experiment.

The receiver has only `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MEDIA_PROJECTION`. The tile is exported under the system
`BIND_QUICK_SETTINGS_TILE` permission; the projection service and review/consent
Activities are not exported. Only the launcher/plain-text receiver is exported.
No INTERNET, storage, notification, overlay, accessibility, assistant role or
default-IME change is requested. Installing a tile does not authorize capture.

The system grants a window/display stream for a session. The service acquires
one ImageReader image and then closes the reader/display and stops projection.
This is an application-enforced one-image limit, not an OS one-image or regional
grant. Cropping happens **after** window pixels entered the app. The 400 ms
transition-settling delay is a measured UI heuristic, not source authentication.
Source app/window identity and same-app page changes cannot be inferred from
coordinates; the user must check the frozen preview. `FLAG_SECURE` remains binding.

## Operation

1. Install the fixture and one probe flavor. In the probe, add its Quick Settings
   tile through the system confirmation UI; put it near the start if desired.
2. Open the public fixture. No editor focus, keyboard or default-IME identity is
   needed for these routes.
3. For exact text, use **Share text**, a standard editor's PROCESS_TEXT action, or
   (with both apps in split screen) drag the explicitly offered text into the
   probe's **Open split-screen receiver** area. Its empty, protected task can be
   selected in Recents; it is removed before the payload opens in protected,
   excluded-from-recents review. The custom bubble menu deliberately has no PROCESS_TEXT
   entry. Global drag works only where the source offers a payload.
4. For pixels, expand Quick Settings and tap **Capture once**. Read the short
   disclosure, choose sharing scope, then select the public fixture in the
   **system** dialog. Sharing stops after a frame. Expand Quick Settings again
   and tap **Review capture draft**.
5. Drag a rectangle around one line in the protected preview. In the OCR flavor,
   tap **Recognize selected line**, correct the result if needed, then
   **Confirm and end test**. This clears the draft; it does not claim a vault save.
   The plain flavor validates the crop without running recognition.
6. Cancel, leave review, recreate/rotate review, lock/screen off, or allow the
   deadline to expire to discard. A new operation invalidates older callbacks.

Typical projection interaction: expand Quick Settings (1-2 swipes), tile (1),
disclosure action (1), system Next (1), target (1), expand again (1-2 swipes),
review tile (1), crop gesture (1), recognize (1), confirm (1): **10-12 actions**
when the tile is on the first page; paging adds actions. Setup, editing and any
future vault authentication are extra. This is feasible acquisition but has
not met the original “copy one sentence directly” usability goal.

Exact share commonly takes 3-5 actions; PROCESS_TEXT depends on source selection
and overflow placement. Drag adds split-screen setup. Those routes are useful
where the source cooperates, not replacements for missing source handoff.

## Data lifetime and correctness

- Text receiver accepts one `text/plain` payload, at most 8192 UTF-16 units.
  URI/stream/HTML/nested Intent/multiple drag items, blank content, NUL and
  malformed surrogate pairs fail closed. Spans are discarded; code units,
  whitespace, newlines, links and emoji are not normalized.
- Intent/drag delivery uses Android IPC. It is not a system clipboard operation.
  Source apps can retain their own originals; this prototype cannot erase them
  or promise that every OEM/system implementation has no internal retention.
- Capture is bounded to 10 seconds and 4.5 million output pixels. A draft expires
  60 seconds after acquisition. Review cannot renew that deadline. Only a single
  draft and one recognition worker are allowed. Lock and revocation invalidate
  old generations. Consent does not survive Activity recreation.
- No frame, crop, source text or recognition result is written to files, saved
  instance state, preferences, logs, notifications or the clipboard. Protected
  pages use `FLAG_SECURE`, exclude recents, and disable autofill/content capture.
  Edit controls reject system copy/cut/paste menu actions.
- The correction editor still exposes editor text to the active IME. Its
  `IME_FLAG_NO_PERSONALIZED_LEARNING` is a request, not an access restriction.
  Use only the public fixture; a private-content correction flow needs a separate
  trusted-IME or app-owned editing design before production adoption.
- The OCR flavor writes only hash-verified **public models/dictionary** to
  `noBackupFilesDir`; app-managed content never goes there. Models are bundled,
  not downloaded by the APK. There is no AI or network path.
- Owned arrays/bitmaps are cleared and references released. Framework immutable
  strings, native allocator pages and OS-owned projection buffers cannot be
  promised forensic zeroization. Closing a native session is not proof of an
  immediate PSS drop. Deadlines prevent late use even if process scheduling delays
  reclamation. The research APK remains separate from the latency-sensitive IME.
- Recognition keeps aspect ratio, pads to a multiple of 32 (maximum width 640),
  uses height 48 and a single inference thread. It neither detects lines nor
  reconstructs original Unicode spacing/emoji from pixels. Dark backgrounds
  require explicit inversion. Long lines, multiple rows and wrong crops can fail.

## Build and checks

Run from the repository root. Use the installed Android SDK consistently in
`ANDROID_HOME` and `ANDROID_SDK_ROOT` (or unset the deprecated variable).
The isolated Gradle user home avoids unrelated global repository init scripts.
The existing reduced-runtime Maven artifact must be prepared as documented in
[`tools/onnxruntime/README.md`](../onnxruntime/README.md).

```powershell
# Stage hash-pinned public models if not already available on this development machine.
python tools/prepare-ocr-evaluation.py

./gradlew.bat -g ./build/verification-gradle-home -p tools/private-copy-probe `
  :probe:testPlainDebugUnitTest :probe:assemblePlainDebug :probe:assembleOcrDebug `
  :probe:assemblePlainDebugAndroidTest :probe:assembleOcrDebugAndroidTest `
  :fixture:assembleDebug :probe:lintPlainDebug :probe:lintOcrDebug :fixture:lintDebug `
  verifyProbePrivacy --no-parallel
```

Outputs are under `build/private-copy-probe/{probe,fixture}/outputs/apk`.
Default native ABI is x86_64. Use `-PprobeAbi=arm64-v8a` for an ARM64 research
package; building that package is not physical-device acceptance. Do not install
a test APK on a personal-content device for automated capture tests.

After explicitly installing the matching APK/test APK and fixture on an emulator:

```powershell
adb -s emulator-5554 shell am instrument -w `
  dev.zeroinput.copyprobe.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode ocr -e samples stress `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode capture `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode capture -e secure true `
  dev.zeroinput.copyprobe.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode tile `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode cancel `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode drag `
  dev.zeroinput.copyprobe.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode expiry `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mode lock `
  dev.zeroinput.copyprobe.ocr.test/dev.zeroinput.copyprobe.ProbeInstrumentation
```

Capture/tile/cancel tests wait for normal system UI interaction. The host may
drive taps in this dedicated emulator; ADB is solely test deployment/control,
never an APK permission dependency. No screenshot protection is disabled.
Tests report counts, geometry, exact-match booleans and resource samples, never
acquired text/image payloads. `am instrument` can exit zero after a test failure:
require the expected JSON success fields and absence of `Probe failed`.

Before adoption, validate rotation, landscape/split screen, source changes,
screen lock, expiry, revocation and rapid repeated requests. Check
`dumpsys media_projection` and `dumpsys activity services` after completion;
inspect only the research app's sandbox for unexpected files. Use public
fixtures for each real OEM/app/version combination. Verify actual keyboard
latency, cold/warm PSS/RSS sampling and package costs on ARM before integrating.

## Primary interface references

- [MediaProjection and per-session consent](https://developer.android.com/media/grow/media-projection)
- [Android 14 QPR2 app sharing](https://developer.android.com/about/versions/14/features/app-screen-sharing)
- [Foreground media-projection service prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)
- [Quick Settings TileService](https://developer.android.com/reference/android/service/quicksettings/TileService)
- [Receiving plain text](https://developer.android.com/training/sharing/receive)
- [Cross-app drag and drop](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop/multi-window)
- [ONNX Runtime 1.26.0 tensor buffer ownership](https://github.com/microsoft/onnxruntime/blob/v1.26.0/java/src/main/java/ai/onnxruntime/OnnxTensor.java)

Observed results and remaining gates are recorded in the project progress and
the prototype validation report, not inferred from API availability.
