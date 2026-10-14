# Dictionary management validation (2026-10-12)

## Scope

Per-table management of the 16 bundled Wanxiang dictionaries, mandatory `zi`,
installed-source redownload, encrypted names/entry edits/weights/tombstones,
40-row search pages, local undo and original restoration. Public source formats
and existing personal formats are unchanged; new encrypted edits use binary v1.
Regenerable Rime deployments use asset generation version 10.

## Host checks

Completion pass on 2026-10-12: 650 selected unit tests passed with zero failures,
errors or skips. `privacyCheck`, Debug Lint, all three Debug ABI packages and the
merged Release manifest check passed with `-PrequireRime=true`. The final
post-fix host run is `build/dictionary-management/build-dictionary-completion-final.log`;
the APK assembly run is `build-dictionary-interaction-fixed-build.log` at the
repository root. Each ABI package was also inspected with Python's ZIP reader.

From the repository root (JDK 17 and the configured Android SDK/NDK):

```powershell
./gradlew.bat :engine-api:test :engine-english:test :engine-dictionary:test `
  testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug `
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  -PrequireRime=true --no-parallel
```

The native source requirement remains enabled. Debug packages are built for
arm64-v8a, armeabi-v7a and x86_64. Each APK is inspected for exactly the intended
Rime library ABI, all 16 XZ dictionary assets and the reviewed built-in source
manifest. Merged Debug/Release manifests retain disabled backup and non-exported
secure dictionary Activities; Release remains non-debuggable. No permissions or
network allowlist entries were added.

Unit coverage includes encrypted format bounds/corruption/duplicates, temporary
buffer clearing, failed persistence/deletion, clear during an active write and
stale saves after reset. Archive tests cover fixed hashes, requested-table
selection, missing entries and path rejection. Candidate tests cover empty filtered
pages, replacement ranking, canonical readings for corrected spelling, private
bypass, pending queries, Space and punctuation commits. Spelling tests cover full,
abbreviated, fuzzy, nine-key and both double-pinyin schemes.

## Android checks

The available Android API 36 x86_64 emulator is used with the Debug and test APKs:

```powershell
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class dev.zeroinput.ime.DictionaryManagementPlatformTest,dev.zeroinput.ime.PublicDictionaryPlatformTest,dev.zeroinput.ime.PublicDictionaryStoreTest,dev.zeroinput.ime.DictionaryEntryInteractionTest,dev.zeroinput.ime.DictionaryManagementUiTest dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Platform cases check the required base table, logical deletion/offline restoration,
preserved disabled state and encrypted edits on reinstall, dedicated-key loss and
ciphertext tampering. Native cases verify base-only conversion, replacement table
identity, extra dictionary activation/removal and full/nine-key/double-pinyin
conversion. UI fixtures contain only fixed public test text, exercising search,
secure-window flags and 320x600 / 800x320 dp viewports in English and Chinese,
in light and dark themes. These are measured viewports, not physical rotation tests.
Images and build/device logs are under `build/dictionary-management/` (untracked).

A device regression exposed canonical-path aliasing during base-cache cleanup;
cleanup now compares canonical roots and keeps files needed by dynamic schemas.
Visual inspection moved long help text into the scroller and Add into the toolbar
to preserve entry space in short landscape windows.

## Completion regressions

- Search lost local replacements when redownloaded originals no longer contained
  the original row. A failing unit test reproduced this. Changes now appear first,
  independently of upstream rows, and edited public keys are skipped. Four search
  tests cover retained changes, 40-row pagination, tombstone undo, reading search,
  dictionary isolation and cancellation before opening originals.
- An old form could adopt the latest deletion generation when Save was clicked,
  recreating reset edits before its queued UI revocation arrived. A device test
  reproduced the write with publication notification deferred. Entry and rename
  forms now retain their opening generation; entry Delete and Undo retain it too.
- Completion callbacks close only their submitting dialog, protecting a newer
  editor opened while an earlier operation completes. Built-in original restoration
  finishes public-state changes before edit reset notifies and refreshes the UI.
- Built-in dictionary actions now show source, reviewed version and public size.
- Device interaction tests exercise Add, renaming an added word, weight changes,
  deletion, tombstones and undo through actual controls. The focused post-fix run
  passed both interaction tests and the bilingual/theme viewport test (3 tests).
  Seven storage/native tests had passed in the earlier combined run; that run
  exposed an intermittent interaction failure, retained in its original log.
  These are staged results, not a claim of one uninterrupted full-suite pass.
- Viewport capture now invalidates retained drawing state and asserts nonempty
  rendered controls, preventing blank screenshots from counting as UI evidence.
  The final viewport-only rerun passed (1 test); eight locale/theme/size images
  were captured and representative portrait/landscape images visually inspected.

Failure reproduction logs and post-fix logs are retained under
`build/dictionary-management/`. The interaction result is
`build-dictionary-interaction-fixed-device.log`; the search reproduction is
`build-dictionary-search-regression.log`; the old-form reproduction is
`build-dictionary-revocation-regression.log`. Screenshots use
`dictionary-fixture-{en|zh-CN}-{1|2}-{320|800}.png`.
The final capture/Lint logs are `build-dictionary-visual-final-device.log` and
`build-dictionary-visual-final-build.log` in the same evidence directory.

## Remaining limits

No physical ARM device run, full instrumented suite or Release APK build/signing
is claimed. ARM Debug packaging and merged Release manifest checks are covered.
Physical rotation, other Android API levels and real ARM layout/rendering remain
outside this pass. English and Chinese measured viewports are covered above.
Live downloads against every upstream website/version were not exercised; tests
verify parser/store and reviewed-archive failure boundaries locally. Source
websites and network availability can still prevent a download. Older installed
extras without a source receipt can require selecting the same dictionary again.

Deletion preserves encrypted edits for reinstallation, as its confirmation states.
Use restore-original or global personal-data clear to erase edits. APK originals
cannot be physically deleted. Entry weights order local replacements; native
Rime weights have different semantics. Candidate provenance is unavailable, so
same-text/same-reading duplicates are filtered together. Other synthesized phrases
containing a deleted word remain independent entries.
