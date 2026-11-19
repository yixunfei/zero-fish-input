# Stego defaults and explicit paste validation (2026-11-19)

## Delivered behavior

The user approved long-press paste for public cover, secret text and encoded
message fields. Keys/passphrases and key-length fields remain excluded. The
private-vault source defaults on within stego, while the global vault feature
keeps its existing default-off state. Each private read still authenticates and
requires foreground confirmation. Navigation clears the previous drafts and
credentials; return requires the matching editor and another explicit Stego tap.
Secret text returns masked and never writes to the external editor.

System paste defaults off. Settings > Steganography independently controls both
sources. The existing exact `StegoSystemPaste` adapter reads one bounded text item
only after explicit selection, rechecking settings and the operation lease.
There is no clipboard listener, automatic import, write-back or AI connection.
Source settings changes revoke pending handoffs and reject queued results.

Settings also persist the default random master length and ordered cipher/width
combination. Initial values are 256 bits and AES-256. Length accepts 256..4096 in
multiples of 8, using presets or a validated custom dialog. Each new workspace
copies the defaults; temporary edits do not overwrite settings. Cipher controls
start collapsed with an ordered summary. Decryption uses received parameters.
No credential, message text, encrypted format or vault migration is persisted.

`StegoPastePolicy` rejects empty, NUL, malformed Unicode and over-budget input
without truncation: cover 512 UTF-16 units / 1536 UTF-8 bytes; secret 2048 units /
2048 bytes; carrier 8192 units / 16384 bytes. Credential targets always reject.

## Build and automated checks

Real-Rime x86_64 Debug and instrumentation APKs build successfully. Scoped app/UI
JVM reports contain 615 cases: 613 passes, two opt-in provider skips, no failures
or errors. `privacyCheck` and Debug Lint pass with zero issues. Debug/Release main
merged manifests retain disabled backups, a private source Activity and a
non-debuggable Release. There are no new permissions, dependencies or exports.

Commands from the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:testDebugUnitTest :ime-ui:testDebugUnitTest privacyCheck :app:lintDebug `
  :app:processReleaseMainManifest "-Pandroid.injected.build.abi=x86_64" `
  -PrequireRime=true --no-parallel
```

Public-resource wording was subsequently rebuilt and rechecked with
`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug privacyCheck`.
Evidence: `.tmp-evidence/stego-defaults-verified-build.log` and
`.tmp-evidence/stego-defaults-final-resources-build.log`.

## Platform checks and corrections

API 37 emulator checks cover settings persistence/recreation, default algorithm
order/widths, default folding, invalid custom lengths, enabled-source menus,
credential exclusion, real IME authenticated secret return, masking and an empty
external editor, encryption/insertion, cancellation, lifecycle revocation and
composition/caret interactions. The final 27-case run passes without skips or
failures; five repeated field/menu checks also pass in landscape, dark mode and
1.5 font scale. The settings matrix covers both languages and themes at both font
scales. Evidence: `.tmp-evidence/stego-defaults-final-platform.log` and
`.tmp-evidence/stego-defaults-landscape-dark.log`. The installed main APK matches
the current artifact, SHA-256
`451eb966adc5e14d418fcffb837ea777cbc24e4dd9410ee7697aaabd60d3d22b`.

The initial menu test asserted unlaid-out list children; it now inspects the
actual choice adapter. The settings preset test waits for dialog layout and
selects the actual ListView. The initial standalone test used an IME-themed host
with an Activity-token dialog; the fixture now uses its matching IME attachment.
No production authorization was relaxed for these test corrections.

An older real-touch encryption test tapped while posted field scrolling was
moving controls. It now waits for UI idle after scroll and taps the visible
rectangle. The complete encryption/decryption assertion then passes. A combined
authentication-window test timed out once and passed in isolation; both runs are
retained. Small-screen layout testing found English large-font clipping of the
Earlier button; its width and minimum-height layout now accommodate the label.
The settings matrix then passes English/Chinese, light/dark and 1.0/1.5 font scale.

System-clipboard prototype tests refuse to overwrite any pre-existing item.
Their skipped runs are retained and are not counted as passes. A newly created,
isolated emulator verifies the production source-worker path with fixed public
text (`stego-defaults-fresh-system-device.log`, one pass and one skip). A separate
empty-device matrix run starts successfully but conservatively skips before
replacing its next sample because fixture ownership cannot be verified from the
metadata (`stego-defaults-fresh-system-matrix.log`). This matrix is not a pass;
full real-platform URI/Intent/multi-item rejection remains unverified in this
run. These unchanged adapter checks remain enforced in code; shared Unicode,
length, credential-target and handoff revocation negatives pass JVM tests.
An independent default-off adapter test avoids acquiring clipboard data and
passes for every target before even consulting authorization
(`stego-defaults-system-disabled.log`); the subsequent instrumentation build,
privacy check and zero-issue Debug Lint also pass
(`stego-defaults-system-guard-build.log`). Across final runs, 29 distinct platform
cases pass, plus five repeated landscape/dark/large-font executions. The skipped
system rejection matrix is excluded from that count.

Temporary device PINs were cleared, display/font/rotation/theme and original IME
settings restored, and task-owned emulator instances stopped. The fresh matrix
instance retained no device credential. Settings/vault fixtures restore their
configuration and remove the entries they create. No physical device or existing
system-clipboard content was modified. `git diff --check` passes; unrelated
pre-existing work remains preserved.

## Remaining scope

Physical ARM/OEM and older-Android acceptance are not performed for this change.
No Release APK, publication or Git commit is requested or made.
The earlier 15:05 ARM package predates these defaults/paste changes.

## User-requested ARM64 repackage (2026-11-19 16:04 China time)

The subsequent repackage request produced
`app/build/outputs/test-apk/20261119-160438/zero-fish-input-0.7.7-debug-arm64-v8a.apk`
using the standard package script with `-Abi arm64-v8a -MaxApkBytes 180000000`
and no SkipChecks. Size: 96,406,675 bytes. SHA-256:
`9a8b776091bcf91635cb67bee6149ea033285eb1aa9383de97c61570202ba188`.

Full Debug/Release unit reports contain 2,278 passes, four opt-in provider skips,
zero failures/errors, including cached/up-to-date suites. Privacy, zero-issue
Debug Lint, real-Rime ARM64 assembly, full resource verification, signature,
ABI/package/permissions and three distribution checksums pass. The 180 MB limit
passes; the independent 80 MB target is exceeded. Provenance matches the current
Gradle output. This Debug-signed testing artifact was not installed on a physical
device or published. Earlier distributions remain preserved. Evidence:
`.tmp-evidence/stego-defaults-repackage.log` and
`.tmp-evidence/stego-defaults-repackage-verification.json`.
