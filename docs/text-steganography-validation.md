# Text steganography implementation validation

Date: 2026-11-18. Status: exact-text implementation and local validation complete.
Authority for current project progress remains [project-progress](project-progress.md).

## Single-page repair and automatic encoded insertion (2026-11-19)

This repair follows the user's report that the previous flow remained unusable.
The main Encrypt and insert action now automatically fills the original editor
once encryption self-check succeeds, without Send. Generate only preserves the
preview and four authenticated private-save commands. All applicable cover,
secret, carrier and key/passphrase fields share one bounded scrollable page;
algorithm checkboxes are expanded inline and numbered in selection order. Valid
width changes and earlier-layer moves are direct. Optional modes/KDF/transport
settings remain under More options. Private temporary input masks every committed
character, including short drafts, until explicit reveal.

The [code self-audit](audits/2026-11-19-stego-usability.md) was completed before
platform acceptance. It added per-workspace callback generations and updated all
old visible-suffix regression expectations. The crypto format, authenticated save,
private-vault storage and service binding/security checks are unchanged.

The first device invocation accidentally installed an older test APK from the
stale outputs directory and failed class loading; it is not acceptance evidence.
Subsequent installs use Gradle's current intermediates/apk directory. A preliminary
14-test run passed 13 tests; its new selection-revocation test incorrectly waited
for selectedLength=0, whereas the interface returns null without a selection.
The corrected fixture waits for an explicit nonempty selection acknowledgement.
The next 55-test run passed all 50 non-authentication tests, but five authentication
tests were skipped because the invocation used clipboardTestPin instead of the
required stegoTestPin. Only the corrected final run is authoritative below.

The authoritative final API 37 x86_64 run passes all 55 platform tests with no
failures or skips in 104.059 seconds (`final-platform.log`). It includes an actual
touch on Encrypt and insert, exact decoded round-trip output, no Send, one-shot
rejected-commit consumption, revocation after a changed editor selection, four
private output transfers, authenticated vault save/read/lifetime, stale callbacks,
fully masked short drafts and existing grapheme/caret/native-draft regressions.

Additional attached layout probes pass both inline encryption controls and the
source/result controls at 240x480 dp, dark, font scale 1.3 and 640x320 dp, light,
landscape, font scale 1.0. All controls remain scrollable with at least 48-dp touch
targets. The records explicitly report inline night=32/orientation=1 and
night=16/orientation=2 respectively. After the full suite, only test layout theme
selection and reporting were adjusted; audited production hashes are unchanged.

Full standard packaging checks pass 2,212 unit-test executions, with four opt-in
live-AI skips and no failures/errors. privacyCheck/testPrivacyBoundary, final Debug
Lint (zero issues), real-Rime x86_64 Debug/AndroidTest and ARM64 Debug builds pass.
The standard package script ran without SkipChecks; signature, ABI, permissions,
resources, size, provenance and all three distribution checksums were verified.

The repaired ARM64 Debug test delivery is
[zero-fish-input-0.7.6-debug-arm64-v8a.apk](../app/build/outputs/test-apk/20261119-101438/zero-fish-input-0.7.6-debug-arm64-v8a.apk),
96,348,751 bytes, SHA-256
`8258e194e8a9dee91fe242625767749834b48719078b029c7da53340863c6132`.
The 180 MB test budget is met; the 80 MB target remains exceeded. It was not
installed on ARM64/OEM hardware. No new Release APK or older-API runtime acceptance
is claimed. The x86_64 production APK installed for these tests has SHA-256
`f5add30c0c6edd6339fadd598747e7922b5ed8422126f4763098e34f89815b73`,
matching its device-side checksum. The original full-suite test APK hash is
`7c029921bde9eca60ce73afc78db88ceda38632c61d2a8183cba21f4e10aace8`.

Cleanup cleared the temporary public PIN and verified the empty credential;
restored the Latin IME, 320x640 physical display without overrides, density 160,
font scale 1.0, automatic rotation/user rotation 0 and light mode; and stopped only
the task-started read-only emulator-5580 without wiping AVD data.

Reproduction from the repository root:

```powershell
./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug `
  :app:assembleDebugAndroidTest -PrequireRime=true --no-parallel `
  "-Pandroid.injected.build.abi=x86_64"
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

Local raw evidence in `.tmp-evidence/stego-usability-20261119/` includes
`final-checks.log`, `final-platform.log`, `layout-small-dark.log`,
`layout-landscape-light.log`, `arm64-package.log`, `final-lint-privacy.log`,
`audited-source-hashes.json`, `installed-artifact-hashes.json` and `unit-counts.json`.
Prior sections below describe historical source states.

## Usability completion (2026-11-18)

The approved follow-up adds direct plaintext/cover/detached-ciphertext/full-carrier
private saves, noncredential field save, next-field navigation, continuous ordered
cipher editing and presets, and the requested toolbar order. Detached ciphertext
uses the existing visible v1 block with a separately supplied exact cover; no
encrypted envelope or vault migration is involved. The worker owns the result
bundle until successful transfer, then the workspace clears it on edit, expiry,
revocation or save. The save host still requires a fresh authentication and another
foreground confirmation. No system clipboard or new networking is introduced.

JVM regression coverage includes a custom mixed-width six-cipher order, all four
exact outputs, both transports, detached round trips, wrong/normalized covers,
damaged tags and strict framing/size limits, isolated saved copies and disposal,
and complete/damaged detached AI-import rejection. Existing permutation/vector
tests remain in the full suite. The standard ARM64 packaging script passes 2,208
unit-test executions with four opt-in live-AI skips and no failures/errors.
privacyCheck/testPrivacyBoundary, Debug/Release merged manifests and Debug Lint
pass; no manifest/permission changes were made. The final platform test sources
are separately compiled and linted after the PIN harness changes.

The task-started read-only API 37 x86_64 AVD successfully boots this time. Final
production Debug APK: 96,926,721 bytes, SHA-256
`0c0e6fe93f4f9c5937aa4df115567c8f88ca7ee4e17d28ff535b0a57b3ea7eb0`.
The installed package hash matches this identity after the tests. Platform checks
cover all four one-use output transfers with no editor writes, detached decryption,
credential-save rejection, revoked results, direct toolbar placement, continuous
cipher mutations and stale-dialog rejection, masked results, and one-shot encoded
insertion without Send. A cold external-engine warmup previously revoked the
prepared commit; deferring its adoption fixes the actual device regression.

Attached layout checks pass at 240x480 dp/dark/1.3 font scale and 640x320 dp/light,
as well as the base 320x640 dp/light viewport. All decrypt and new result commands
scroll into view with at least 48-dp touch targets; result labels are not ellipsized.
The initial landscape attempt stayed portrait and is not counted as landscape
evidence; the wide-light record explicitly reports orientation=2 and 640x320 dp.

An earlier complete 28-test run passed 26 tests and reported two system-PIN
confirmation timeouts. The harness now clicks the system keypad confirmation
instead of sending a hardware Enter that can activate a separately focused Cancel
control, and waits for system-window dismissal and UI idle between operations.
After this correction, the focused authentication group passes all five tests,
and the final complete platform suite passes all 28 tests with no failures or
skips (101.707 seconds). The authoritative final record is
`.tmp-evidence/stego-usability-final-platform-suite.log`. Production
authentication, timeout and retry boundaries were not relaxed.

The new ARM64 test delivery is
[zero-fish-input-0.7.6-debug-arm64-v8a.apk](../app/build/outputs/test-apk/20261118-224348/zero-fish-input-0.7.6-debug-arm64-v8a.apk),
96,338,823 bytes, SHA-256
`ac86cc4747ac07a875e0619435d38354451fd9bcae13b3b839a6e80229c7f6fb`.
Signature, real-Rime ABI, permission/resource/size/provenance and all three
distribution checksum checks pass. The 180 MB limit is met; 80 MB remains unmet.
The package is Debug signed and was not installed on an ARM64 device. No current
Release APK, other ABI runtime, OEM/older-API, channel retention or natural-wording
verification is claimed.

Reproduction from the repository root:

```powershell
./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug `
  :app:assembleDebugAndroidTest -PrequireRime=true --no-parallel `
  "-Pandroid.injected.build.abi=x86_64"
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

Local evidence in `.tmp-evidence`: stego-usability-completion-build.log,
stego-usability-arm64-package.log, stego-usability-verified-device.log,
stego-usability-final-all-device.log, stego-usability-system-confirm-roundtrip.log,
stego-usability-auth-settle-tests.log, stego-usability-final-platform-suite.log,
stego-usability-layout-small-dark.log,
stego-usability-layout-wide-light.log and stego-usability-final-lint-privacy.log.
These records use fixed public fixtures only. The earlier refactor and original
implementation evidence below belongs to historical source states.

Cleanup: the temporary public test PIN was cleared successfully. Before shutdown,
the task-owned AVD reported the original Latin IME, physical 320x640/160-dpi display
without overrides, font scale 1.0, automatic rotation with user rotation 0 and light
mode. Only the task-started read-only emulator-5580 was stopped; AVD data was not
wiped.

## Refactor review follow-up (2026-11-18)

The subsequent [second self-check](audits/2026-11-18-refactor-fixes.md#second-self-check-2026-11-18)
adds engine lifecycle and handoff-token fixes. The artifact hashes and 35 device
passes in this section precede those additional changes. This pass could not boot
the isolated API 37 AVD: both attempts failed WHPX partition initialization with
0x80070005. No device test, installation, PIN or device-setting mutation occurred.
Current build identities and host validation are recorded in the disposition.

This addendum records the current fixes; the original implementation evidence and
ARM64 delivery farther below describe an earlier source state. Baseline backup:
`2c86668`. See the [per-finding disposition](audits/2026-11-18-refactor-fixes.md).

The task-started isolated ZeroInput_Clipboard_P1 AVD (API 37, x86_64, original
320x640 dp) passes all 22 steganography tests without skips. Coverage includes
fixed decrypt options, pending carrier composition, a 5.5-second public file
provider, OOM delivery injection, retry after cancelled authentication, a
16-second draft wait plus a 16-second post-authentication confirmation wait,
fresh confirmation, no authentication-callback write, settings/deletion
revocation and late-worker clearing. The selection integration fixture waits
for the IME selection acknowledgement, not only main-looper idle; otherwise a
late Binder selection update correctly revokes the protected panel.

All 13 import/language-pack platform tests also pass. These cover restored
carrier-only review actions and Android streaming-JSON behavior: a 100,000-char
value is skipped while following valid entries remain usable, whereas malformed
syntax, oversized keys and damaged hashes remain rejected. The old platform
expectation that every oversized value invalidates the pack was updated.

Additional attached-window checks pass at 240x480 dp/dark/portrait and
640x320 dp/light/landscape; all ten decrypt commands keep 48-dp touch targets and
scroll fully into view. The base suite also passes at 320x640 dp/light. Import
view tests cover both languages/themes, 1.3 font scale and 320x600/800x320 bounds.
These are 35 distinct platform tests plus two additional layout executions.

Current selected JVM suites: 1,137 passes, two live-AI skips, no failures.
privacyCheck and testPrivacyBoundary pass; Debug/Release Lint have zero issues.
Real-Rime x86_64 Debug/AndroidTest and unsigned Release builds pass. No native
source changed and this run does not repeat ARM builds or native-symbol injection.
Kotlin LinkageError fallback and cancellation races use deterministic tests.

The installed Debug APK was taken from
app/build/intermediates/apk/debug/app-x86_64-debug.apk, not old output copies.
Its SHA-256 is
`b7815be70e867aec4a3e1c3f31882bf3d44fee916473ec9a192d14fcd91faaba`
and size is 96,842,800 bytes. The installed hash matches before and after the
device tests, and its Android Debug signature verifies. The unsigned Release is
84,295,530 bytes with SHA-256
`4494431a1e365d887d84ad6a17a34971653953e54f9e1f60e41c902417bccc82`.
Both are above the separate 80-MB target and below the 180-MB test budget.
No new signed release or ARM64 delivery package is claimed.

Evidence: .tmp-evidence/refactor-review-validation-final.log,
refactor-review-device-tests-final.txt, refactor-review-import-json-platform-final.txt,
refactor-review-layout-240-dark.txt and refactor-review-layout-landscape-final.txt.
The latter four names share the .tmp-evidence directory. These ignored local
records contain public fixtures only. The temporary public PIN was cleared,
original IME/display/rotation/night settings restored, and the task-started
emulator stopped without wiping its AVD data. API 26/29/33, OEM/ARM64 runtime,
cloud-provider UI, real process-wide OOM and external-channel round trips remain
unverified.

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

The latest [ARM64 Debug test APK](../app/build/outputs/test-apk/20261118-205059/zero-fish-input-0.7.6-debug-arm64-v8a.apk)
is version `0.7.6-debug`, application ID `dev.zeroinput.ime.debug`, and
96,327,119 bytes (96.327 MB). It meets the 180,000,000-byte packaging budget and
exceeds the separate 80,000,000-byte optimization target. Its SHA-256 is:

```text
a61b6292ce5902ab2e81d0c1d710e945216c327b816372febe0571bee8b2495f
```

The same directory contains `SHA256SUMS.txt`, `APK-SIZE.json`,
`BUILD-PROVENANCE.json`, license notices and handwriting corresponding sources.
This package supersedes earlier delivery paths in this task. It is a Debug test
build; the separately checked Release is unsigned.

This delivery includes both November 18 refactor repair passes. The standard
package script passed without skipping checks, including full unit tests (2,200
passes, four credential-dependent skips, no failures/errors), privacyCheck,
Debug Lint (zero issues), real-Rime ARM64 build and APK validations. All three
distribution checksums and the current Gradle APK/provenance match. The package
has not been installed or tested on an ARM64 device; the earlier x86_64 platform
results below predate the additional repairs. Automatic approval rejected the
requested old-package deletion (`blocked by policy`); the old packages remain.

Final local evidence is retained in the ignored `.tmp-evidence/` directory:

| Record | Evidence |
| --- | --- |
| Current ARM64 packaging, without `-SkipChecks` | `refactor-arm64-package-final.log` |
| Historical ARM64 packaging before the refactor fixes | `stego-final-package.log` |
| Real-Rime x86_64 Release and Lint after the candidate repair | `stego-final-release.log` |
| All 17 device tests and installed APK identity | `stego-final-device-tests.txt`, `stego-device-installed-identity.txt` |
| Manifest boundaries and device cleanup | `stego-merged-manifests.json`, `stego-device-cleanup.txt` |

The historical device suite's checked x86_64 APK identity was unchanged across
that device run. It also predates the latest fixes, so its runtime results do not
verify this delivery. The current delivered APK hash and packaged permission
boundary were rechecked after packaging; the preceding Debug/Release manifest
verification is recorded in the refactor review addendum.
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


## Paste, custom master material and compact layout (2026-11-19)

The latest explicit request supersedes the earlier system-body-read refusal only
for the two public stego fields. COVER/CARRIER long press opens the source menu.
System Paste replaces that field with one bounded valid plain-text item; no
listener, write, coercion, automatic vault save or AI path exists. Vault Paste
selects an entry, authenticates and confirms the read; original drafts are wiped.
After returning to the original eligible editor, an explicit Stego tap consumes
the one-use isolated-workspace result. No plaintext is inserted into that editor.
Detached ciphertext may accompany its requested exact cover through the detour.

Random master material supports presets 256/512/1024/4096 and custom byte-aligned
256..4096-bit values, generated on the worker. Actual cipher subkeys and the
HKDF-SHA256 ceiling stay unchanged. New material lengths change no serialized
envelope or vault format; canonical old 32-byte credentials continue to work.
Text/credential/cipher controls share a scroll area with fixed primary execution.
Explicit reveal expands long credentials; compact controls retain 48 dp targets.

The [self-review](audits/2026-11-19-stego-paste-keys-layout.md) preceded final
acceptance. It fixed stale source actions, source-deadline failure recovery,
detached-cover association and first-return IME lifecycle cancellation. A real
editor/authentication round trip verifies the returned value survives the first
binding, waits for an explicit Stego open and never writes the editor.

| Final evidence in `.tmp-evidence/` | Result |
| --- | --- |
| `stego-paste-return-lifecycle-build.log`, `stego-paste-finished-test-build.log` | Targeted app unit tests, real-Rime x86_64 build/test APK, privacyCheck/testPrivacyBoundary and Lint passed; zero Lint issues |
| `stego-paste-accepted-platform.txt` | API 37 x86_64: all 36 stego tests passed, zero failures/skips; auto insertion, auth read/save/return, generation, replay, selection/session/settings/deletion and queue checks |
| `stego-paste-accepted-system-mask.txt` | Three passed, zero failures/skips: system text/URI/Intent/multi/size/Unicode/revocation cases, real source-menu touch paste and full short-private masking |
| `stego-paste-accepted-small.txt` | Both editing and prepared/result command layout probes passed at 240x480 dp, dark, 1.3 font scale |
| `stego-paste-final-landscape.txt` | Both probes passed at 640x320 dp, light, font scale 1.0 |
| `stego-paste-final-small-dark.png`, `stego-paste-final-landscape.png` | Public attached layout screenshots inspected; concise mode labels, stable primary row, reachable scroll controls |
| `stego-paste-manifests.json` | Debug/Release allowBackup false, private source nonexported, Release not debuggable; no new permission |
| `stego-paste-accepted-arm64.log` | Packaging without SkipChecks: 2,218 unit passes plus four opt-in AI skips, no errors/failures; privacy/Lint, pinned resources, real Rime, signature/ABI/permissions and size checks passed |

Initial harness failures are retained separately and are not final acceptance:
fixed missing fixture keyboard dispatch and hidden-view matching; explicit UI
confirmation waits inspect the actual protected host before the accessibility
click. System source-menu touch uses its visible bounds because accessibility
ACTION_CLICK sometimes failed while the dialog was present. Production auth and
confirmation requirements were not weakened. The final suite above has no skips.

Current ARM64 Debug package:
`app/build/outputs/test-apk/20261119-112304/zero-fish-input-0.7.6-debug-arm64-v8a.apk`
(96,363,403 bytes), SHA-256
`f4b1899792921c1c1ee587826f78c9c417be547731966932b22d7f1fc80176a3`.
The 180 MB budget passes; the 80 MB target remains exceeded. Temporary public
PIN2468 was cleared; display/density/font/rotation/night and Latin default IME
were restored. The task-owned disposable emulator was stopped.

Not verified in this iteration: OEM/ARM64 or older-API runtime, real third-party
message transport, or a signed Release build. ARM64 packaging is not runtime
proof. No Git commit, publication or external message was made. Existing unrelated
uncommitted changes are preserved.


## Security/UX audit follow-up (2026-11-19)

See the [item-by-item audit resolution](audits/2026-11-19-stego-security-ux-resolution.md)
for the classified findings, corrected behavior, current build/device evidence and
remaining platform gaps. Earlier package paths/hashes above do not identify the
audit-fixed build. No new ARM64 distribution or Release publication is made by
this follow-up. Navigation/selection/recreation still revoke private drafts.
