# Text steganography implementation validation

Date: 2026-11-18. Status: exact-text implementation and local validation complete.
Authority for current project progress remains [project-progress](project-progress.md).

## Scope and decisions

The implementation follows [revision 2](text-steganography-design.md) and
[ADR 0039](adr/0039-text-steganography.md). It provides all six cipher families,
valid key widths, ordered one-to-six-layer authenticated cascades, all five master
credential modes, three explicit Argon2 profiles, key-file factors, two exact-text
carriers, isolated editing, self-check/preview/one-shot encoded commit and masked
local decryption. No user-data storage format or key alias changed.

The user explicitly retained the ban on system-clipboard body reads. Supported
sources are a bounded editor selection, user SEND/PROCESS_TEXT plain-text share,
and one authenticated private-vault entry. No automatic page extraction, system
Paste/Copy, plaintext Intent result, AI/network route or new exported component exists.

Natural wording is unavailable, as required when its design gates are unmet.
No reviewed reversible language resource, tokenizer, held-out corpus or semantic
assessment exists in this repository. An AES-only minimum envelope already needs
1296 carrier bits (162 bytes); even one binary choice for every code point of a
512-unit exact cover would be insufficient. Expansion needs a separate reviewed
resource and the 1000-cover-per-language detector/semantic evaluation; repeated
filler or an unmeasured synonym table cannot establish it. The UI states unavailable
and never silently substitutes another carrier. Unicode/Base64 are detectable.

## Cryptographic evidence

The host-only [RustCrypto tool](../tools/stego-vectors/README.md) pins all crates
and checksums in Cargo.lock. Its checked public fixture is
`security/src/test/resources/message/rustcrypto-v1.txt`; it contributes no APK code.
It agrees byte-for-byte with BC/Tink for all 12 cipher-width modes, three Argon2id
profiles, five credential combinations, the file factor, and complete AES/six-layer
envelopes. JVM tests also verify RFC 5869, RFC 9106, RFC 8998 appendix A.1, NIST
AES-GCM and draft-irtf-cfrg-xchacha-03 appendix A.3.1 vectors.

Negative tests cover each changed envelope byte, exact cover/factor errors,
malformed/trailing/truncated input, every six-layer ordering (720), mixed key widths,
valid outer layers around each damaged inner tag, one KDF including self-check,
pre-KDF invalid covers/profile refusal, strict Unicode/size/marker boundaries,
stream bounds/misreported lengths/provider errors and expiring operation generations.
This is implementation agreement and regression evidence, not a cryptographic audit,
certification, side-channel proof or additive-strength claim.

Reproduction from repository root:

```powershell
cargo run --manifest-path tools/stego-vectors/Cargo.toml --locked --quiet
./gradlew.bat :security:testDebugUnitTest :ime-core:testDebugUnitTest --no-parallel
./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:lintRelease `
  :app:processReleaseMainManifest -PrequireRime=true --no-parallel `
  "-Pandroid.injected.build.abi=x86_64"
```

## Device and build evidence

Validation uses the task-started read-only `Medium_Phone_API_37.0` x86_64 AVD,
real Rime and fixed public fixtures. No personal clipboard is read, no external
message is sent and test-created vault entries are removed. The installed Debug
APK hash is checked before and after the device suite; the raw identity and test
records are in `.tmp-evidence/stego-device-installed-identity.txt` and
`.tmp-evidence/stego-final-device-tests.txt`.

The final JVM result XMLs contain 90 pure JVM tests and 1,040 tests per Android
build variant: 1,038 pass and two opt-in live-AI tests skip in each variant.
In total, 2,166 JVM variant executions pass, with four live-credential skips and
zero failures. No steganography device test skips. Gradle reuses input-matched
test/build caches where appropriate.

The API 37 suite passes all 17 tests, covering keyboard, panel, share/PROCESS_TEXT,
file providers, vault authentication, workers and resource behavior. Keyboard tests verify secure
window flags, no external edits during secret/credential editing, exact encoded
one-shot commit with no Send, selected-carrier preservation, local masked result,
explicit reveal, selection revocation and sensitive/no-personalization exclusions.
Share/PROCESS_TEXT tests verify explicit routing, denied carrier AI permission,
private own-keyboard credential entry, no standalone plaintext insertion and
recreation discard. Source/worker tests exercise cancellation, actual file bounds,
temporary permissions, duplicate/late result clearing and retained execution slots.
Real PIN authentication verifies vault read, local message decryption, a second
authentication for save, and a fresh foreground confirmation before writing.
Cancellation, settings changes and deletion generations prevent stale saves.

Two regressions were reproduced before repair: entering the mode must not replace
an editor selection with an empty composing edit; closing the mode must clear its
private candidate snapshot before an engine status strip can expose it. The UI
also drops an unnecessary retained text-state object. Small-screen testing found
two-line button labels shifted below their row by baseline alignment; rows now
disable that alignment and preserve their full touch areas.

The attached-window layout matrix passes at 411x914 dp/light, 914x411 dp/dark,
320x640 dp/dark and 640x320 dp/light. All ten displayed decrypt commands have at
least 48x48 dp touch areas and can be scrolled fully into view. A public test host
settles rotation before opening the protected source; recreating a secret-bearing
source still deliberately discards it. Raw matrix logs are
`.tmp-evidence/stego-{landscape-dark,small-portrait-dark,small-landscape-light}-tests.txt`.

On the final API 37 run, a standard 64-MiB Argon2id operation with a 2,048-byte
secret, six layers and self-check took 1,014 ms. Sampled managed allocations were
14,148,080 bytes at baseline and 87,018,768 bytes at peak; 89 main-looper probes
observed a maximum delay of 7 ms. This measures probe scheduling, not full key-event
latency. The managed heap cap is 201,326,592 bytes (192 MiB); increased/high profiles
need more capacity and are unavailable on this device. The implementation never
substitutes a cheaper cost profile.

Debug/Release Lint reports contain no issues. Both merged manifests retain
`allowBackup=false`; the new source is private and excluded from recents, Release
is not debuggable, and the source permission set matches the original manifest.
Real-Rime x86_64 Debug/AndroidTest and unsigned, R8-shrunk Release builds pass.
The ARM64 Debug delivery uses the normal packaging checks, including signature,
ABI, permissions, installability metadata, contents, size and checksums. No native
message-crypto dependency was added.

The opt-in system-PIN test is an exact additional platform-UI fixture in the
privacy checker. It runs only with `stegoTestPin=2468` on the task-owned emulator,
searches only the focused system credential UI, and accesses no application page
or clipboard. Negative checker tests continue to reject the same capability in
production/other paths and reject a service in this fixture. No production
accessibility or system-clipboard exception is introduced.

The task's public test PIN was cleared, display size/rotation/night overrides were
restored, and the task-started emulator was stopped with its original AVD data
retained. Cleanup evidence is `.tmp-evidence/stego-device-cleanup.txt`.

Reproduce the device suite after installing the x86_64 Debug and AndroidTest APKs
on a disposable emulator configured with the fixed public PIN (never a personal
device):

```powershell
adb -s emulator-5554 shell am instrument -w -r `
  -e package dev.zeroinput.ime.textstego -e stegoTestPin 2468 `
  dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
./gradlew.bat :app:lintRelease :app:assembleRelease -PrequireRime=true `
  --no-parallel "-Pandroid.injected.build.abi=x86_64"
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

## Checked delivery and final records

The latest [ARM64 Debug test APK](../app/build/outputs/test-apk/20261118-174651/zero-fish-input-0.7.6-debug-arm64-v8a.apk)
is version `0.7.6-debug`, application ID `dev.zeroinput.ime.debug`, and
96,317,775 bytes (96.318 MB). It meets the 180,000,000-byte packaging budget and
exceeds the separate 80,000,000-byte optimization target. Its SHA-256 is:

```text
50f11f0937687e3786c27e996b946a9f938f6f931f61243d7ed3f077029ed25c
```

The same directory contains `SHA256SUMS.txt`, `APK-SIZE.json`,
`BUILD-PROVENANCE.json`, license notices and handwriting corresponding sources.
This package supersedes earlier delivery paths in this task. It is a Debug test
build; the separately checked Release is unsigned.

Final local evidence is retained in the ignored `.tmp-evidence/` directory:

| Record | Evidence |
| --- | --- |
| Normal ARM64 packaging, without `-SkipChecks` | `stego-final-package.log` |
| Real-Rime x86_64 Release and Lint after the candidate repair | `stego-final-release.log` |
| All 17 device tests and installed APK identity | `stego-final-device-tests.txt`, `stego-device-installed-identity.txt` |
| Manifest boundaries and device cleanup | `stego-merged-manifests.json`, `stego-device-cleanup.txt` |

The device suite's checked x86_64 APK differs from the ARM64 delivery by ABI;
its identity was unchanged across the device run. The delivered APK hash and
Debug/Release merged-manifest boundaries were rechecked after packaging.
Earlier logs named `stego-final-validation.log` predate the candidate repair and
are not the final build evidence.

## Known limits and manual checks

- API 26/29/33 and OEM/ARM64 runtime behavior need separate physical/virtual devices;
  building an ARM64 package does not prove ARM64 runtime behavior.
- No real chat/browser transmission or recipient round trip was authorized. Test
  against a chosen destination/version with public fixtures before relying on
  hidden-character retention. Exact cover changes intentionally fail authentication.
- Heap figures are sampled managed allocations, not a native/system peak-memory
  proof. Profiles must refuse current insufficient headroom and never downgrade.
- FLAG_SECURE and lifecycle clearing do not defeat a hostile OS/accessibility
  service or fully erase immutable View/engine/provider/JVM copies. No persistence
  of message credentials or automatic recovery is offered.
- No Release signing, Git commit, publication or third-party message send is part
  of this task. Existing unrelated audit changes remain uncommitted and preserved.

Manual destination acceptance: use public cover/secret, prepare/confirm, inspect
the received exact carrier via explicit selection/share, authenticate locally and
compare the public secret. Repeat after destination close/reopen and on the
recipient platform. Report stripping/normalization as an unsupported channel;
do not silently remove layers, retry uncertain commits or use plaintext fallback.
