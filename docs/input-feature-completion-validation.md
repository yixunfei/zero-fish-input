# Offline input feature completion validation

Date: 2026-09-30. The approved scope includes English, full pinyin, Microsoft and
Ziranma double pinyin, and nine-key glide; quality-first single-character Chinese
handwriting; floating/left/right one-hand keyboards; and complete offline RGI art.

## Implemented behavior

- Glide captures bounded geometry from actual key viewports and returns explicit
  spelling/reading choices. English replay commits through the engine and space;
  Chinese codes append through the active Rime conversion session. All five
  layouts have real-editor platform coverage. The public dictionary index loads
  off the main thread, with cancellable work and session/interaction generations.
- Handwriting combines simplified/traditional public stroke models with corrected
  PP-OCRv5 preprocessing and CTC scoring. Up to 16 candidates are offered. Touch
  down revokes old recognition immediately; resizes preserve aspect ratio. Strokes
  and temporary buffers remain local and are cleared after use.
- One host wraps the keyboard and its panels. Floating mode supports drag, width
  and height resizing, minimize/restore and dock; one-hand mode supports either
  side and width changes. Preferences are stored separately by orientation.
  Accessibility actions move in four directions or adjust size. The IME touch
  region excludes the rest of the screen; no overlay window or permission is used.
- The pinned Emoji 18.0 catalog has 3,963 fully-qualified RGI sequences and 9
  components, with 3,972 bundled lossless WebP images. Variants, flags, mixed tones,
  local search and exact Unicode submission are supported. Newer emoji suffixes
  supplement Android ICU for atomic deletion. Receiving apps choose their font.

## Verification record

Environment: Windows, JDK 17, Android SDK/NDK from repository configuration; API 36
x86_64 emulator `emulator-5554`, 1080 x 2400 pixels, density 420. Native checks use
`-PrequireRime=true`. All Gradle and device runs are centralized and serial.

| Check | Evidence and result |
| --- | --- |
| Initial JVM tests, privacy and Lint | `build/feature-checks.log`: successful |
| First full device run | `build/feature-device-full.log`: 241 run, 2 stale/nondeterministic test assertions failed |
| Corrected assertions plus placement/rotation | `build/feature-device-recheck.log`: 24 passed |
| Final full device run | `build/feature-device-final.log`: 243 passed, 330.179 seconds |
| Final Debug/Release unit tests, privacy and Lint | `build/feature-package-final.log`: successful; no checks skipped |
| Three-ABI native build and packaging | Same packaging log: arm64-v8a, armeabi-v7a and x86_64 with real Rime required |
| Distributed universal APK installation/smoke | `build/feature-packaged-smoke.log`: 8 passed in 19.98 seconds after installing the exact distributed APK |
| Emoji audit | `python tools/prepare-emoji.py --verify`: exact official set, hashes, decoded images all passed |
| Handwriting host regression | `python tools/test-handwriting-quality.py`: 6 passed |
| Scoped source-size audit | 52 gesture/handwriting/layout/service Kotlin files examined; no block-bodied function over 100 lines |

The handwriting panel assertion now checks both immediate empty cancellation and
the completed stroke callback. The English association test now uses the explicit
Space commit contract; a same-named personalized candidate and engine candidate
have different existing spacing contracts. Assertions still require exact text
before/after Enter, cleared predictions and the editor action. No production
behavior or security check was relaxed to make these assertions pass.

Platform coverage includes five-layout glide-to-editor round trips; tap versus
glide ownership; cancellation and sensitive-editor suppression; floating outside
touch pass-through; both one-hand modes; minimize/restore; bounded dragging and
resizing; accessibility movement; orientation-specific save/reload; offline emoji
art, public variants with personalization disabled, and Unicode deletion. Existing
privacy, clipboard, session, data-clear and engine regression tests remain enabled.

## Measured quality and performance

The final full API 36 run measured the independent 20-character traditional stroke
corpus at 13/20 top 1 and 18/20 top 16. Model loading took 1,224 ms; recognition median
was 29 ms and p95 83 ms. Java heap increased by 42,056,288 bytes and native heap by
23,951,728 bytes across loading. These are allocation deltas, not peak PSS, and
emulator timing is not a physical-device performance promise.

The same final run's input latency fixture reported dispatch p50 698 us and p95
4,585 us across 1,140 samples. This measures the fixture's input path, not full
gesture recognition latency. The glide host corpus comprises 388 synthetic paths;
all expected codes appeared in the first eight candidates. The dictionary includes
316,355 codes across five layouts. Natural gestures, repeated letters/digits,
out-of-vocabulary words and uncommon readings remain ambiguous. See the scoped
[glide report](glide-decoder-validation.md) for per-layout results and limitations.

Twenty traditional samples cannot establish general handwriting accuracy. The
simplified regression fixtures overlap source training data and are not an
independent quality estimate. See [handwriting validation](handwriting-quality-validation.md).
The [emoji report](rgi-emoji-validation.md) includes exact sources, image coverage,
version status and licenses. Public stroke tables add 46,068,784 uncompressed
bytes; WebP artwork adds 15,533,352 bytes. APK size is recorded with the artifact.

## Reproduce

```powershell
$env:JAVA_HOME='D:/env/jdk17'
./gradlew.bat testDebugUnitTest :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck :app:lintDebug "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
./tools/test-input-experience.ps1 -Serial emulator-5554
python tools/prepare-emoji.py --verify
python tools/test-handwriting-quality.py
./tools/package-test-apk.ps1
```

Replace the local JDK path and device serial for another host. Asset preparation
commands are in README. Packaging verifies the debug identity, ABI, permissions
and signature and emits APK, notices, Tegaki corresponding sources and SHA256SUMS.
The source archive includes original training XML/model archives and adaptation
scripts, so binary redistribution does not rely only on upstream links.

## Remaining limits and follow-up acceptance

- No Android 8/API 26 device image or physical ARM device was available for UI or
  touch-recognition acceptance. Bundled artwork avoids dependence on system emoji
  fonts, but oldest-version rendering still needs device verification.
- Human finger/stylus evaluation, larger independent multi-writer handwriting
  corpora, long-session low-memory pressure and background process death remain
  unmeasured. Public glide/model caches are retained during service lifetime;
  this build does not claim low-memory optimization or a bounded whole-app peak.
- Manual device acceptance should cover dark theme, small landscape, each panel
  after drag/resize, interruptions, palm/multi-pointer input and editor switches.
- Debug artifacts are debuggable and use the debug application ID/signature.
  Release unit tests and merged-manifest checks passed; `:app:assembleRelease`
  was not run and no production Release APK is delivered.
- The pre-existing lifecycle service remains a complex 1,629-line file (below
  the exceptional 2,500-line ceiling). New decoding/window responsibilities are
  separate classes, and view binding is split by responsibility; a broader service
  decomposition is outside this change. No new function exceeds 100 lines.

## Final run and distribution

The final full device suite passed all 243 tests. Platform-gated tests retain their
existing SDK/credential assumptions; this does not substitute for the untested
device and manual scenarios listed above. The final package command ran both
Debug and Release unit tasks, `privacyCheck`, `:app:lintDebug`, and three native
ABI builds, without `-SkipChecks`. No production sources changed after these runs.

Distribution directory: `app/build/outputs/test-apk/20260930-193124/`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `zero-fish-input-0.4.0-debug-universal.apk` | 189,418,185 | `9fd42d909578d8ffd24371ff05bf22c55080a6e3d28e6c33d2455f8df9701378` |
| `zero-fish-input-0.4.0-debug-notices.zip` | 154,209 | `d9b61bdc7974f665453043bad33b40d114bf0e22c97f1cfcbf81f79c17ef9925` |
| `zero-fish-input-0.4.0-debug-handwriting-sources.zip` | 43,749,748 | `d9682cc40c252bfbb1d9510cfd752c86cbc4c036c266aacc1b093069368b1e27` |

`SHA256SUMS.txt` accompanies the three artifacts. APK signature, debug identity,
permission allowlist and exact ABI set were verified. ZIP inspection confirmed
3,972 WebP images, all new license texts, four handwriting assets and exactly two
ONNX graphs (handwriting plus the existing Mini scorer). Handwriting OCR is not
duplicated under `mini-int8`; the independent quality fixtures do not enter the
main APK. Handwriting assets occupy 49,357,630 compressed bytes and the complete
emoji directory 16,003,194 compressed bytes. The universal APK is 180.64 MiB.

Debug and Release merged manifests retain `allowBackup=false`. Debug is
debuggable; Release is not. The permission sets contain only existing approved
AI, clipboard reminder/overlay, authentication and generated receiver permissions.
The offline input features add no permission or network adapter.

The exact universal APK was installed on the API 36 emulator after packaging.
Its eight smoke checks passed: five-layout glide-to-editor flow, four RGI emoji
checks, handwriting model quality, floating touch routing and rotation restore.
The model quality count remained 13/20 top 1 and 18/20 top 16. No APK was rebuilt
after computing the published checksum.
