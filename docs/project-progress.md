# Project assessment and hardening

Date: 2026-09-06

## Private-copy entry follow-up (2026-10-31)

The user retains independent private copying and requests confirmation before
enabling incognito mode. System clipboard body collection is not authorized.
Implemented an enable-time disclosure with explicit confirmation; cancellation
or Activity recreation leaves the setting off. Private editor-selection drafts
now deny AI extraction in the request model and hide the AI import action.
The new negative regression failed before the fix.

Immediate repairs validated: 386 app JVM tests passed (two live-provider tests
skipped), privacyCheck including Debug/Release manifests, Lint and requireRime
x86_64 Debug/test builds passed. Seven emulator tests passed, including consent
cancellation/recreation and repeated private import. The consent dialog image
was inspected. Logs: `.tmp-evidence/privacy-copy-validation.log` and
`.tmp-evidence/privacy-copy-device.log`. No physical ARM or OEM app acceptance.

Remaining product gap: noneditable pages with custom
menus that expose neither PROCESS_TEXT nor text sharing have no private-copy
entry. A separate default-off, user-triggered accessibility page-copy proposal
is under design. The user requests local OCR efficiency/package evaluation before
deciding on screenshot/OCR integration. Research uses a separate, permission-free
benchmark app with generated public text; it does not authorize production capture.
The [OCR feasibility study](ocr-feasibility-2026-10-31.md) is complete at emulator
scope: single-line recognition works with the existing runtime; page detection
requires a different operator set. Full-runtime detection has substantial memory
and package costs. OCR has not passed the production-adoption gate; target ARM
typing/page measurements and a bounded capture implementation remain open.
Do not describe this disclosure as resolving that gap. No system-body capture,
new accessibility capability or permission has been added.

Validation command: `./gradlew.bat :app:testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel`.

## Symbol groups and v0.7.1 (2026-10-31)

Approved: named symbol groups, expanded catalog, commit/push and testing prerelease.
Implemented 12 groups with 1336 curated symbol entries and bounded pages.
Packaging tests, privacyCheck, Lint, universal APK signature/ABI/permission checks
and 21 emulator tests passed. Published [v0.7.1 testing prerelease](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.1)
with the universal APK, SHA-256 list, notices and corresponding sources;
see [v0.7.1 verification](releases/v0.7.1-validation.md). Physical ARM/OEM font
acceptance remains open.

## User-reported input repairs (2026-10-30)

Approved scope: symbol navigation/coverage, prominent private selection copy,
custom emoji deletion, Chinese Shift punctuation and personal frequency defects.
Implementation and automated verification are complete: 901 JVM tests passed
(two live-provider tests skipped), privacyCheck, Lint, real-Rime x86_64 Debug/test
APK builds and 40 emulator tests passed. Fixed-fixture layout images were checked
at narrow/regular/landscape sizes in light and dark themes. Earlier uncommitted
audit fixes remain intact. Next: physical-device and real social-editor custom
label acceptance; host-menu ordering and editors that omit spans/ignore Delete
remain platform limits. See [repair validation](input-bugs-2026-10-30.md).

## Current audit (2026-10-30)

Approved scope: repair reproduced input/interaction defects without new features,
data format changes or changes to the security model. Four defect categories
have fixes and six initially failing regression tests. Final checks passed:
890 JVM tests passed (two credential-dependent live tests skipped), privacyCheck,
Lint, requireRime Debug/test APK builds, and 34 x86_64 emulator tests. No Release
or physical ARM acceptance was performed. Next: maintainer physical-device/editor
acceptance; no known reproduced defect remains in this audit scope. See
[audit record](code-audit-2026-10-30.md) for commands, results and remaining limits.

## v0.3.0 testing prerelease (2026-09-23)

The release includes verified Chinese-engine readiness, persistent preparation
feedback, prediction/conversion privacy separation, offline public associations,
storage reliability fixes, hot-path improvements and corrected zero fish input
branding. Version 0.3.0-debug uses code 3 and the existing Debug signing identity.
See [release notes](releases/v0.3.0.md) and [validation](releases/v0.3.0-validation.md)
for current checks and remaining device boundaries.

## v0.4.0 testing prerelease (2026-09-28)

The release packages the AI workbench, stricter streaming and language-pack
validation, input/editor lifecycle repairs, private clipboard hardening, and
the follow-up audit regressions. Version 0.4.0-debug uses version code 4 and
the existing Debug signing identity. See [release notes](releases/v0.4.0.md)
and [validation](releases/v0.4.0-validation.md) for the final checks and
remaining device/signing boundaries.

## Association coverage follow-up (2026-09-21)

The approved priority is association coverage and evaluation. The project-authored
Chinese/English corpus grows from 286 to 1,728 pairs, including traditional-input
aliases and longer scheduling/help-request chains. Single-character Chinese
suffixes no longer trigger inside unrelated words. Public input fixtures expose
and guard both ranked output and complete editor commit chains. The frozen set
is a development/acceptance benchmark, not a blind quality estimate; broader
independent and physical-device evaluation remains open. See
[word-association-quality.md](word-association-quality.md).

## Storage reliability follow-up (2026-09-21)

The approved security-storage batch added ten JVM security tests and dedicated
device regressions for lost keys, corrupt bodies, silent AtomicFile failures,
clear/retry and concurrent metadata/removal. Reads no longer create replacement
keys; incomplete clear blocks access in the current vault instance and attempts
both dedicated resources. Queued management actions carry deletion generations,
and failed index refresh hides stale cached rows. Grant expiry cannot exceed
30 seconds. Authentication flow, aliases and persisted formats are unchanged.

The current verification, reproduced failures and limitations are recorded in
[security-storage-validation.md](security-storage-validation.md). Body/index
atomicity is per file; process-local failure state does not guarantee recovery
after process death. Hardware-bound authentication remains a separate design
decision, with API 26/ARM and physical authentication acceptance outstanding.
The original assessment below remains dated evidence, not the current test count.

## Initial public prerelease

The public name is now `zero fish input`; internal identifiers and encrypted
data formats retain their existing identity. The v0.1.0 prerelease adds the supplied
logo and launcher artwork, installation/contribution documentation, private GitHub
security reporting and packaged license/source notices. Validation and outstanding
device/signing work are recorded in [release-validation.md](release-validation.md).

## Starting point

The repository is a functional Android IME with ten registered modules, real
librime/JNI, full and nine-key Chinese input, an offline English engine, a small
reference dictionary engine, encrypted personalization, a private authenticated
clipboard and data-only language packs. Dependencies already follow stable
engine ports and bounded background queues. This checkout has no Git metadata.

The initial Gradle test/privacy/Lint run passed. There were 84 distinct unit
tests (160 executions including Android build variants), and the existing 32
device tests passed on the connected Android 17 x86_64 emulator. The missing
coverage was concentrated in encrypted storage and user dictionary boundaries.

## Completed scope

1. Unknown editor classes/variations previously reached the permissive default.
   They now use the sensitive path, clear preceding composition/candidates, and
   never instantiate a suggestion engine or access personal data.
2. Manual phrases could exceed the reader's 20,000-entry limit. Updates now enforce
   that limit and a 5 MiB compact JSON limit before writing; overflow cannot create
   an unreadable file or silently evict manually managed phrases.
3. User dictionary import now uses fixed-schema streaming validation. Malformed
   UTF-8, excessive nesting, duplicate fields/IDs/identities, unsupported languages,
   invalid lengths/scalars and trailing documents are rejected before mutation.
   Merges are indexed by phrase identity and cannot alias another local candidate.
4. Persistence now publishes a new memory snapshot only after a successful
   encrypted write. Clear invalidates older waiting updates and waits for active
   writes before deleting the file/key. A failed deletion blocks reads and writes
   until the user retries successfully. Tests inject storage failures through the
   new EncryptedStore port and use separate real Keystore aliases for crypto tests.
5. The phrase page confirms import merge rules and plaintext export scope before
   file selection. It uses localized safe failures, bounded inputs, lifecycle-aware
   callbacks, standard toolbar actions and a stable delete icon. Recycled rows and
   temporary byte buffers release their contents. Successful management changes
   invalidate the IME's personal-suggestion cache.

Format 1, the encrypted envelope, file name and production key alias are unchanged.
No migration, networking, permissions, external components or native algorithm
changes were introduced. See [ADR 0005](adr/0005-user-lexicon-boundary.md).

## Verification

- New failing regression tests reproduced the permissive editor default,
  over-capacity addition and acceptance of nested unknown JSON before the fixes.
- Core tests cover unknown editors following a composing session and password,
  visible password, web password, PIN, email, URI and privacy preference policies.
- The expanded device suite passed all 50 tests: 11 dictionary boundary tests,
  four real Keystore/storage tests, three phrase UI tests and the original 32.
- Phrase UI tests also passed separately in the actual system light and dark
  modes (three tests each), with 320/411/800 dp geometry, portrait/landscape,
  Chinese/English labels, confirmation gating and text contrast assertions.
  Android 17 synthetic night contexts did not consistently select application
  color resources, so actual system theme changes were used and restored.
- Generated public-fixture images were visually inspected for English/light
  export, Chinese/dark export and long phrase rows. No user data was captured.
- `./tools/package-test-apk.ps1` passed: 88 distinct unit tests (168 executions
  across JVM/Debug/Release variants), Debug/Release merged-manifest privacyCheck,
  Lint, real Rime builds for all three ABIs, and signature/ABI/permission checks.
- Artifact: `app/build/outputs/test-apk/20260906-154348/ZeroInput-0.1.0-debug-universal.apk`
  (28,903,783 bytes), with `SHA256SUMS.txt` beside it. SHA-256:
  `f963cf6d519884f6bd5b82ffdd542218a0382681b55b88f31933210d812ce925`.

## Next priorities

- The next-word suggestion baseline now uses bundled public Chinese/English
  phrase pairs, a memory-only prediction port and a bounded editor-local context.
  It supports tap-to-chain continuations with a separate default-on switch and
  conservative privacy gating. Coverage expansion and representative quality
  evaluation remain open. See [verification](word-association-validation.md)
  and [ADR 0013](adr/0013-offline-word-associations.md).

- Language-pack manifests now use bounded byte streaming with strict UTF-8
  decoding both during import and installed-package discovery; declared ZIP
  sizes remain an early bound, while extraction still verifies actual bytes and
  SHA-256 before activation.
- Continue physical biometric/credential and delayed callback acceptance.
  Automated consent, storage generation and grant-race coverage now exists;
  tests requiring a configured device PIN must be reported separately from the
  synthetic authorization tests. See the current storage validation report.
- Measure Chinese typing and touch latency on physical ARM devices and target
  applications. Emulator timings are not evidence of OEM/device performance.
- The full-pinyin secondary Rime session is now created only when related-reading
  expansion is first requested. Lifecycle generation checks close queued native
  work after a session ends; physical memory measurements are still required.
- Continue localizing the remaining management pages and handling their worker
  failures consistently. Avoid adding more engine features before these checks.

No Release APK was built or signed in this iteration. Physical ARM execution,
real biometric/credential UX and external document-provider overwrite behavior
still require maintainer/device acceptance. The delivered APK is a Debug test build.
