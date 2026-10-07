# Code audit and test package: 2026-10-07

## Scope

Reviewed the current working tree, including the pre-existing uncommitted fixes.
Priority areas were input/personal candidate paging, native lifecycle, AI request
cancellation and storage, authenticated clipboard import/addition, encrypted file
failure handling, and language-pack validation. Existing root tests, privacy checks,
Lint and targeted Android instrumentation provide additional coverage. This was a
targeted audit, not a line-by-line audit of all first-party or upstream source.
No persisted format, permission, network transport or engine ABI was changed.

## Confirmed defects and fixes

1. **Clipboard additions lost their text before the worker ran.** The management
   page queued a closure referencing a `CharArray`, then immediately wiped that
   array in the authentication callback's `finally`. `PendingClipboardAddition`
   now owns the buffer through one write; denial, rejection and destruction cancel
   pending ownership, while the worker clears its consumed buffer in `finally`.
   Vault cancellation checks also see the draft's revoked state. Failure messages
   remain content-free, and rejected work restores the page's usable state.
2. **Repeated keyboard selections were silently discarded.** `take()` cleared the
   transfer token; `offerBack()` restored only the draft, with no consumable token
   or new launch, while the Activity finished. Internal repeated imports now revoke
   the old request and grant before accepting a new review. The external exported
   import retains its existing reject-on-repeat behavior. Regression coverage
   checks one-use tokens, invalid replacements and rejection of old authentication.
3. **Native previous pages became unreachable.** Personal first-page merging
   overwrote `native.hasPreviousPage` with false. The native flag now remains visible
   while native rows are included; evicted personal pages still own their back edge.
4. **A regression test hung indefinitely.** The previous-page test requested pages
   in a loop without calling `publish`, so its state never advanced. A JVM thread
   dump confirmed the loop. The repaired bounded test publishes every requested
   page and checks the first-page prefix of the retained multi-page window.
5. **AI storage accepted ambiguous or incomplete JSON.** Missing required current
   configuration fields became defaults; missing conversation messages became an
   empty list; duplicate fields could override prior values. Readers now reject
   these cases without overwriting storage. Four new negative tests failed before
   the fix and passed afterward. Unicode and malformed UTF-8 are also covered.
6. **Android parsing broke host regression coverage.** Eight repository tests and
   seven app tests failed after the existing switch to `android.util.JsonReader`.
   They now use pinned, host-only Robolectric 4.16.1/API 28, with real Android
   parsing rather than stubs. No test was disabled or weakened.
7. **Temporary plaintext cleanup was incomplete.** UTF-8 buffers are now allocated
   inside the cleanup scope before decoding, including partial decoding failure;
   the String-based vault addition wipes its owned temporary character array.
   Removed the ineffective export `CharBuffer.wrap(String)` cleanup, which had no
   backing array and left an extra encoded buffer, and corrected its misleading
   memory-erasure claim. Export callers still own the returned bytes; JSON strings
   remain subject to garbage collection as documented in the threat model.
8. **The packaging script read the wrong APK directory.** Normal `assembleDebug`
   writes `app/build/outputs/apk/debug`, but the script read an IDE intermediate
   path and failed after a successful build. The script now reads the actual
   assemble output; the complete packaging command succeeds without skipped checks.

## Validation

- `./tools/package-test-apk.ps1`: passed with checks enabled and real Rime required.
  Its root `test` task passed 945 test executions: 450 Debug, 450 Release and 45
  platform-independent JVM cases, with zero failures/errors/skips. Debug/Release
  run the same Android module test cases under both variants.
- `privacyCheck`: passed, including both merged manifests. Both disable backup;
  Release is not debuggable and permissions match the approved whitelist.
- `:app:lintDebug`: passed, `No issues found.`
- Targeted `:app:connectedDebugAndroidTest` on API 36: 35 tests passed, zero skips.
  Classes: `ClipboardRepeatedImportTest`, `ClipboardImportPanelTest`,
  `ClipboardImportVaultTest`, `SecureClipboardVaultReliabilityTest`,
  `AuthenticationBrokerTest`, `UserLexiconBoundaryTest`, and
  `SecureClipboardManagerPrivacyTest`. An initial external-repeat test failure
  caught overly broad replacement handling; limiting it to internal selection
  imports restored the existing external contract and the full selection passed.
- Universal Debug build: passed with `-PrequireRime=true`, all three ABIs;
  signature, package name, permissions and ABI checks passed.
  The final universal APK also installed successfully on the emulator and its
  settings Activity completed a cold launch (`am start -W`: `Status: ok`).

Artifact: `app/build/outputs/test-apk/20261007-090616/zero-fish-input-0.4.0-debug-universal.apk`
(189,633,950 bytes). Package `dev.zeroinput.ime.debug`, version `0.4.0-debug`,
Debug signed. The same directory contains license notices, corresponding handwriting
sources and `SHA256SUMS.txt`.

APK SHA-256: `0f8a33fe83e31abfead718053acd96f4040cdc5fad6e4dace6894d0f25994cf4`.

Build logs are retained under `build/audit-*.log`; final results are in
`build/audit-package-20261007.log` and `build/audit-device-final.log`.

The Android test device is the existing `ZeroInputReview20260928` x86_64 Android
16/API 36 emulator. Tests use public fixture data. Import layout tests cover
320 x 600 and 800 x 320 dp, Chinese/English, light/dark and 1.3 font scale.

Remaining scope: no physical ARM device, OEM input field, real biometric/PIN entry,
Android 8.x device, power-loss or full end-to-end networking validation. A Debug
package with all three native ABIs does not replace runtime validation on each ABI.
No signed production Release APK is requested or produced.
