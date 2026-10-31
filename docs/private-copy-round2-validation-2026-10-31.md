# Private-copy second-round validation

Scope: the user-approved bounded round after `c563fb6`, on
`zn/private-copy-prototype`. Independent IME and OCR agents implemented disjoint
files; a third agent reviewed read-only. Gradle and device access were serialized.
Production source, permissions, private-vault behavior and model versions are
unchanged. This is research acquisition, not a completed private-copy feature.
See the [plan](clipboard-product-repair-plan.md) and
[first-round evidence](private-copy-prototype-validation-2026-10-31.md).

## Environment and interpretation

Only the API 37 x86_64 emulator from the first round was connected. No ARM phone,
OriginOS build or real conversation was tested. All captures used the public
fixture. IME enabling, selection and restoration used normal system UI. No
accessibility, overlay, shell-granted capture permission or runtime ADB helper
was introduced. ADB was development deployment/control only.

The research IME does not call InputConnection text-reading APIs and disables
fullscreen extraction. Framework-supplied EditorInfo/InputConnection objects are
not evidence of arbitrary-page access. MediaProjection still requires its own
per-session consent; full authorized-window pixels enter memory before cropping.

## Return-to-preview result

Normal APK launches, outside instrumentation, produced the following results:

| Condition | Observation | System launch reason |
| --- | --- | --- |
| Non-IME, immediate return | Actual protected Review entered | BAL_ALLOW_GRACE_PERIOD |
| Visible research IME, immediate return | Actual protected Review entered | BAL_ALLOW_NON_APP_VISIBLE_WINDOW |
| Visible research IME, delayed return | Review entered after 15,014 ms | BAL_ALLOW_NON_APP_VISIBLE_WINDOW |
| Hidden, previously bound research IME, delayed return | Review entered after 15,015 ms | BAL_ALLOW_ALLOWLISTED_COMPONENT |
| Non-IME, delayed return | Frame acquired; delayed callback not observed on time | No demonstrated BAL rejection |
| Screen off or switch to Gboard while return pending | No return attempt; later manual review was empty | Request invalidated |

The non-IME delayed behavior is consistent with cached-process freezing; it is
not proof of that cause and must not be reported as a platform launch rejection.
Successful trials record timer execution, the actual system launch verdict,
Review claim and the real foreground Activity. Projection was already stopped
before the delayed return; it was not kept alive to obtain launch eligibility.

Android 16 AOSP uses a current input-method window/owner check, not just the
DEFAULT_INPUT_METHOD setting. Its recent-Activity grace constant is 10 seconds;
the 15-second diagnostic separates that mechanism from the observed hidden-IME
exception on this API 37 image. A default IME that has never established a window
was not tested. Neither AOSP nor this experiment establishes OEM behavior.
Sources: [official Activity launch rules](https://developer.android.com/guide/components/activities/secure-bal),
[AOSP 16 launch controller](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/BackgroundActivityStartController.java#L1071),
[AOSP 16 grace constant](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/ActivityTaskManagerService.java),
[projection consent and release](https://developer.android.com/media/grow/media-projection).

With the research keyboard already visible, the immediate-return implementation
has a nominal six-action flow:
start, disclosure, system Next, source selection, crop and explicit confirmation.
Crop release starts OCR automatically; selection is locked while it runs.
The QS entry retains its manual default as a control, so choosing immediate
return there takes 8-9 actions. Setup, showing the keyboard, corrections and
future private-vault authentication are additional. The 15-second delay is a
diagnostic option, not part of the immediate flow.
IME return trials used the plain flavor. Automatic OCR and confirmation were
separately exercised through the real manual-tile capture flow in the OCR flavor;
one complete IME-plus-OCR six-action run was not measured.

The original Gboard selection and enabled-IME list were restored, including
disabling the research IME. The capture service was gone and projection was null.

## OCR comparison

The fixed corpus contains the original eight strings plus 120 different public
strings. Four font sizes (18/24/30/42 pixels), both polarities and vertical margins
are represented. Styles were fixed before the first device run. Corpus source
SHA-256: `a3f2f1ca05f6a7ad706d0a483c02bbd0caaf322f35adb77b6106224859e31d09`.

Each pair uses the same bitmap, with alternating execution order and separate
warmup. Baseline remains the existing height-48/max-width-640 path, preserving
aspect until the width cap; overlong inputs can be compressed. The sole
candidate combines vertical foreground cropping, bounded margins
and a maximum width of 1280. This comparison does not isolate those changes'
individual contributions. No language correction or character normalization is
used. Tests report only indices, counts, edit positions/types and timing.

| Public group | Cases | Baseline exact | Candidate exact |
| --- | ---: | ---: | ---: |
| Original regression set | 8 | 4 | 5 |
| Chinese | 12 | 10 | 12 |
| English | 12 | 5 | 12 |
| Mixed scripts | 12 | 2 | 8 |
| Amounts | 12 | 3 | 9 |
| URLs | 12 | 3 | 12 |
| Whitespace | 12 | 0 | 0 |
| Emoji | 12 | 0 | 0 |
| Multiple lines | 12 | 0 | 0 |
| Long lines | 12 | 4 | 10 |
| Punctuation | 12 | 6 | 9 |
| **All cases** | **128** | **37** | **77** |

Unicode-code-point edit errors were 969/2253 versus 187/2253 (CER 43.01% versus
8.30%). The candidate reduced edits in 62 cases, increased them in six and tied
in 60. It gained 42 exact cases and lost two (indices 43 and 51, deleted spaces).
All four originally exact cases in the original eight-string set stayed exact.
Null outputs, 13 baseline and three candidate, count as full deletions; they are
not omitted. Unsupported-character/multiline boundary cases remain in all totals.
This is a synthetic crop corpus, not a real QQ/WeChat/LINE accuracy estimate.
Loose margins deliberately expose the baseline's downscaling weakness; comparing
these totals to the first round's eight tightly constructed lines is invalid.

The substantial geometry improvement is real within this comparison, but the
candidate fails the no-regression gate and cannot preserve common whitespace or
multiline content. It remains experimental, with baseline still the default.
No further tuning, detector, replacement model or semantic repair was added.

## Cost optimization retained

The public model and character table are now reused after checking size and
SHA-256 on every request. Missing/corrupt files are written to bounded temporary
files, verified, synced and atomically replaced. Cancellation cleans temporary
files; native sessions still close per recognition. A hit avoids **16,608,794
bytes of writes per request**, rather than bypassing integrity checks.

Eight paired measurements compared the new protected atomic/fsync writer with
verified reuse: preparation median 59.14 versus 17.38 ms; total median 180.80
versus 139.51 ms; all reuse mtimes remained unchanged. This is not the old
implementation's direct performance baseline, because the old writer lacked
atomic replacement/fsync. No cross-version speedup percentage is claimed.

Across the 128 warm paired requests, baseline/candidate total P50 was
133.28/154.37 ms and P95 186.37/261.93 ms. Native session loading alone had a
median near 91 ms in both paths. First warmup was 1093.69 ms including environment
initialization and preparation, excluded from the warm distribution. These
timings exclude user actions, capture and crop selection.

PSS was 18,209 KiB initially, at most 79,904 KiB in post-request samples, and
51,662 KiB at the end. These are not peaks or production-IME increments. No
physical typing latency, thermals or production package increment was measured.
Process splitting, persistent session caching and quantization were not pursued
after the correctness gate failed.

## Validation and decision

Passed: plain nine JVM tests; OCR 18 JVM tests including nine new cache
integrity/cancellation cases; 30 existing device boundary assertions on both
flavors; eight return/selection guard assertions; isolated builds/Lint/strict
Debug/Release manifest checks; production privacyCheck. The real system-consent,
public-Canvas capture, tile return, automatic crop recognition, exact expected
line and confirmation/discard passed all 11 UI assertions. The first UI-driver
attempt exceeded the existing 60-second wait while navigating QS pages; its
failure log was retained. The second attempt passed without relaxing timeout or
content assertions. A real system Cancel passed three assertions with no draft.
Final service checks for both probe packages explicitly returned `(nothing)`,
projection was null, and the OCR sandbox contained only the two public model
files. No whole-production build/test rerun was needed: production implementation
did not change, and its privacyCheck was rerun successfully.

Debug delivery artifacts, package metadata and SHA-256 checksums are under
`build/private-copy-probe/artifacts-round2/`. Signatures, actual native ABI and
packaged permission lists were checked. The OCR ARM64/x86_64 packages are
27,135,476/27,307,790 bytes; the architecture-independent plain APK is 123,062
bytes and the public fixture is 40,940 bytes. They are research packages, not a
production APK increment or a release publication. ARM64 packaging succeeded;
ARM64 execution remains unverified.

The user-facing private-copy promise remains unmet. Retain the supported return
experiment and integrity-preserving model reuse; retain the candidate only for
comparison. Do not integrate this OCR path into the production private vault.
Further work would require an explicit decision to offer fallible, reviewed
image-to-text assistance, and physical-device acceptance, rather than assuming
that these optimizations provide exact arbitrary-source copying.

Evidence: `.tmp-evidence/private-copy-round2-ime-*.log`,
`.tmp-evidence/private-copy-round2-ocr-device.log`, its metadata-only parsed JSON,
the OCR build/boundary logs, and `private-copy-round2-production-privacy.log`.
No acquired payload is included in repository reports or diagnostic logs.
