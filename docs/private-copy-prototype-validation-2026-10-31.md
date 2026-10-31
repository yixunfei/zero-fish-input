# Private-copy acquisition prototype validation

This records the first round. Later optimizations, controlled IME return trials
and the larger fixed OCR corpus are recorded in the
[second-round report](private-copy-round2-validation-2026-10-31.md).

Scope: independent research APKs on `zn/private-copy-prototype`, after baseline
`3cbfaa1` was committed and pushed to `main`. No production source, manifest,
vault schema or IME behavior changed. Confirmation in the probe discards data.
Implementation plan: [clipboard-product-repair-plan.md](clipboard-product-repair-plan.md).
Reproduction: [probe README](../tools/private-copy-probe/README.md).

## Environment and evidence limits

- Emulator: API 37, x86_64, 1080 x 2400, 420 dpi; fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.051.A2/16418371:user/release-keys`.
- Research APKs: minimum API 34, compile/target API 36. An ARM64 APK build is
  packaging evidence only. No physical Android 16/OriginOS device is connected.
- Only bundled public fixtures were used. System consent was exercised through
  its actual UI. ADB deployed tests and drove emulator UI actions; neither APK
  depends on runtime ADB/Shell authorization or a privileged helper.
- No accessibility/overlay/assistant/default-IME permission was enabled. No
  actual QQ, WeChat, LINE conversation or personal media was read.
- Local evidence is under `.tmp-evidence/private-copy-probe-*.log`. These files
  contain assertions, timing and resource metadata, not acquired payloads.
  Host screenshots document only public fixtures and system dialogs; they are
  development evidence, not files created by the capture implementation.

## Implemented paths and results

| Path | Observed result | What this establishes | Remaining limit |
| --- | --- | --- | --- |
| Explicit SEND text | Full public fixture matched code units exactly | Cross-app plain-text delivery, validation and protected review work | Does not make a missing source Share action appear |
| Explicit PROCESS_TEXT | Full supplied fixture matched exactly | Payload dispatch and receiver behavior work | Test source explicitly dispatches the action; real menu discovery/order is not established |
| Global drag in actual split screen | Complete Unicode fixture matched exactly | ClipData handoff works without a clipboard operation | Requires source drag support and split-screen setup |
| System single-app projection, Canvas fixture | Two measured frames contained the correct blue source marker; 727 and 641 ms after consent return | Pixels can be acquired without nodes, overlay, a11y or editor focus | Two timing samples do not establish capture reliability or latency percentiles |
| Protected source | 0 marker pixels, black fraction 1.0 | FLAG_SECURE was respected on this emulator | No protected-content extraction or fallback |
| Actual capture plus bounded line OCR | Public `Hello world 123` matched exactly; 257 ms for local OCR request | Capture, geometry and recognizer can be composed | One recognized line is not a quality acceptance corpus |
| Quick Settings round trip | System tile installation, launch, consent, return tile, crop gesture, exact line, protected edit field and confirmation/discard passed | The supported foreground interaction works without background Activity launch | Around 10-12 actions on the first tile page, plus paging and future vault auth |
| Custom TextView menu and offline WebView fixtures | Implemented and built | Separate source types are available for follow-up | Dedicated capture/OCR acceptance for these pages has not been run |

The fixture distinguishes an ordinary TextView with a custom menu from a Canvas
with no text nodes. Neither case grants the receiver a text-reading API. The
existing production InputConnection selection route remains unchanged; this
prototype does not duplicate it or broaden it to arbitrary page content.

The drop receiver needed its own **empty** task: the secret import/review pages
are excluded from Recents and cannot serve as a convenient phone split-screen
picker target. The empty receiver can appear in Recents with a protected
thumbnail, receives only an explicit drop, then removes that task before opening
the protected, excluded review. No content is put into the landing task Intent.

## Privacy and lifecycle checks

Nine JVM tests cover exact Unicode/whitespace retention, length and surrogate
bounds, one-time consumption, cancellation generations, expiry and letterbox/
reverse/out-of-bounds crop mapping. Device boundary tests additionally cover
URI/HTML/stream/multiple-item rejection, real cross-app payload delivery,
Activity recreation, late frames and recognition overlap/cancellation.

Observed negative paths:

- Cancelling the actual system sharing dialog produced no draft.
- Recreating review removed the original draft rather than restoring view text.
- Screen-off cleared a pending public draft. This is not credential-protected
  lock/unlock validation on an OEM phone.
- The actual draft deadline cleared content after 60,004 ms; no test clock or
  shortened timeout was used.
- A second recognition request was rejected while an earlier one was active.
  Cancelling an active fake native operation invalidated delivery, recycled both
  owned crop buffers and cleared the returned late text.
- After capture, `dumpsys media_projection` reported `null`; no CaptureService
  remained. Android may still bind the tile while Quick Settings is visible.
- The OCR app sandbox contained only the two verified public model/table files;
  no image, crop or recognized-text file was created. Source review confirmed no
  clipboard API, content serialization, network or AI call in either research APK.

A final regression initially checked the draft from the instrumentation thread
while main-thread cleanup was still in progress: the generation had already
changed, but buffer clearing had not returned. The test now synchronizes with
the main thread before asserting release and waits for recognition completion,
without changing the required cleanup behavior. Five consecutive runs passed
all 30 device boundary assertions per run. A separate fixture fix consumes automatic test handoff arguments once
so split-screen recreation cannot replay the earlier SEND test.

The editable preview is an ordinary editor and can expose its contents to the
currently active IME through InputConnection. Its no-learning flag is a request,
not access control; Android explicitly says IMEs may ignore it. All tests used
public text. A production correction flow still needs a trusted-IME or app-owned
editing decision before accepting private content. See the official
[learning flag](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
and [surrounding-text API](https://developer.android.com/reference/android/view/inputmethod/InputConnection#getSurroundingText(int,%20int,%20int)).

Strict manifest checks cover Debug and Release for both flavors and the fixture:
only the two projection foreground-service permissions, expected components,
system-protected tile binding, nonexported internals, disabled backup/transfer,
disabled cleartext and nondebuggable Release. Lint passes for all three builds.
Production `privacyCheck` also passes. Production builds/tests were not rerun
wholesale because no production implementation changed; baseline validation
was retained in commit `3cbfaa1`.

## Local OCR measurements

Uses the existing hash-pinned reduced runtime and recognizer, no detector.
Input height 48; width adapts to aspect ratio and rounds to 32, capped at 640.
Each request creates/closes a native session on one background worker. The worker
has no queue, rejects overlap, and exits after one idle second. No native/model
initialization is added to the IME or to the plain flavor.

Eight distinct public synthetic lines, repeated four times:

| Measurement | Result | Interpretation |
| --- | --- | --- |
| Exact results | 4/8 distinct lines; 16/32 repeated cases | **Correctness gate failed**; repetitions are not new quality samples |
| Request median | 182.5 ms | Includes model verification/extraction/load, preprocessing, inference, decoding and session close |
| Request P95 / maximum | 261 / 577 ms | Emulator only; excludes user interaction, acquisition and manual crop |
| Baseline PSS | 15,416 KiB | Fresh instrumentation process sample |
| Largest post-request PSS sample | 82,110 KiB | Not a peak measurement |
| Last closed-session PSS | 42,245 KiB | Resident memory does not return to baseline immediately |
| Diagnostic post-GC PSS | 39,673 KiB | Test-only forced GC; product does not use GC as a cleanup guarantee |

The failures include a price with mixed spacing, repeated spaces, a URL with
query punctuation and an emoji/punctuation line. There is no automatic semantic
repair or normalization. A fixed-width 640 comparison had a larger short-run
post-request sample; adaptive width reduces unnecessary padded inference, but
these samples do not establish mobile performance or a memory ceiling.
ONNX Runtime's public `getFloatBuffer()` returns a copy; the copied scores and
owned input arrays are cleared. Other native/OS buffers and immutable framework
strings cannot be promised forensic zeroization. The repeated run's PSS drop is
evidence against monotonic growth in that run, not proof of absence of leaks.

## Packaging and delivery

Research Debug APKs and SHA-256/package metadata are generated in
`build/private-copy-probe/artifacts/`. They are intentionally not committed or
published as a production release. The plain flavor has no models/native OCR
libraries. The OCR flavor contains only `rec.onnx`, `characters.txt`, the existing
reduced ONNX native libraries and applicable notices. All model/runtime hashes
are checked before building.

The standalone ARM64 OCR APK is approximately **25.85 MiB**; the plain APK is
under **0.11 MiB**. These are standalone research packages, **not** an incremental
production APK estimate: production already has related handwriting/runtime
assets, and actual reuse/packaging has not been designed or measured. Native
ABIs are verified inside the generated packages. ARM64 execution is unverified.

| Delivered Debug package | Bytes | Native libraries |
| --- | ---: | --- |
| OCR ARM64 | 27,117,334 | arm64-v8a only |
| OCR emulator | 27,289,648 | x86_64 only |
| Plain probe | 65,824 | None; identical for both build selections |
| Public fixture | 40,940 | None |

## Decision and next gate

The minimal acquisition prototypes are implemented and have emulator evidence.
They establish viable explicit text handoff and a supported system-authorized
pixel path; they do not establish a finished private-copy product.

Keep source-text handoff as the exact route where available. The capture route
requires two concrete compromises: whole-window/display authorization before
local cropping, and a longer consent/return interaction. OCR additionally gives
approximate text that needs checking and adds model/runtime cost. None of these
compromises authorizes clipboard collection, broader persistent reading, or AI.

Before production integration, require a fixed physical device/OS/app matrix,
correct-target repetition with page changes/rotation/split screen, real
projection revocation, foreground/review interruption, and an expanded public
quality corpus. Measure ARM cold/warm latency, sampled peak PSS/RSS, ongoing
keyboard latency and production package reuse. The present OCR corpus must
improve before adoption. Exact arbitrary source text from applications that
provide no supported handoff remains unavailable under the stated permissions.

No production capture permission, private-vault bridge, PDF/file importer,
system screenshot importer, OCR detector, or background content collector was
added. Those are separate decisions, not implicitly approved by this prototype.
