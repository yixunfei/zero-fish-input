# Pinyin editing and continuous candidate browsing

Date: 2026-10-11
Status: implemented; automated and emulator acceptance passed.

## Approved behavior

- Remove candidate previous/next buttons, including the expanded panel footer.
  Preserve continuous horizontal/vertical scrolling, expansion/collapse and
  accessibility scrolling. The strip prefetches near an edge without requiring
  a second swipe; candidate identity/pixel anchors survive bounded updates.
- Remove the tools entry for single-syllable selection. During full/double-pinyin
  composition Shift becomes `Split` and invokes the existing segment selection.
  Idle and English keyboards retain case switching and Caps Lock.
- Tap or drag the displayed pinyin to move its caret. Long-press and drag to
  select a range; drag a selection endpoint to adjust it. Typing inserts or
  replaces; Backspace deletes the selection or preceding character. The mark in
  `c|haxun` represents a cursor, not an inserted apostrophe.
- Editing selected segments restores their original raw reading and discards
  previous segment choices. Selection offsets never address an external editor.
  Separate AI/search drafts keep separate controllers and no personalization.

The stacked header now reserves two 48 dp rows to provide a usable pinyin touch
target without moving letter keys between idle/composing states. Short/wide
windows keep their single 48 dp header. The expanded footer's 48 dp is returned
to candidate content. Existing unrelated workspace edits were preserved.

## Implementation boundaries

`engine-api/CompositionCursorEngine` carries an optional immutable raw-input
selection. The Rime and fallback adapters support bounded insertion/replacement,
and the expanding wrapper forwards the optional port. Rime clears confirmed
segments before restoring an edited reading. Microsoft double-pinyin restoration
accepts its existing semicolon code at the JNI boundary.

`ime-core` rejects stale expected input and invalid offsets. A positioned cursor
prevents prepared-engine takeover until it reaches the end or composition ends.
Offset-zero Backspace is consumed without deleting outside the composition.
The focus-free view disables saved text, autofill and content capture, and has
no platform editing/clipboard menu. Accessibility uses the existing AndroidX
view-action mechanism. No permissions, dependencies, network paths or persisted
formats changed; privacy check rules were not relaxed.

## Verification

Windows/JDK 17; real bundled librime, Android 16/API 36 x86_64 emulator.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck `
  :app:lintDebug -PrequireRime=true --no-parallel
```

- Build succeeded. Unit-test XML across the 11 requested modules records **625
  tests, zero failures/errors/skips**. Unchanged tasks reused Gradle results.
- `privacyCheck` passed with Debug and Release merged manifests; Lint reports
  no issues. All three native ABIs built with `requireRime=true`.
- The final platform run passed **53 tests in 46.984 seconds**, including real
  Rime cursor/range editing, selected-segment restoration, paging preservation,
  Shift switching, same-editor single commit, English case gestures, candidate
  continuation, invalid/cancelled gestures, isolated drafts and both double-pinyin
  schemes. The real editor test explicitly chooses Chinese before typing.
- Layout fixtures cover 240/320/360/411 dp portrait and 720 dp landscape in light
  and dark themes, plus candidate density at 240/360/720 dp. Reviewed rendered
  pinyin selection/caret and expanded candidate images; no text clipping found
  in the inspected fixtures. Existing tests also exercise larger fonts.
- Three APK ZIPs contain the correct single ABI and `libzeroinput_rime.so`.
  ARM64 metadata identifies `dev.zeroinput.ime.debug`, version `0.4.0-debug`;
  its Android Debug certificate verifies.

Evidence (generated and ignored): `build/pinyin-edit-validation.log`,
`build/pinyin-device-validation.log`, `build/pinyin-apk-verification.json`,
`build/pinyin-visuals/`, and module `build/test-results/` directories.

The platform test source already lacked required `LocalDraftInput` cancellation
callbacks; those test fixtures were updated to the current constructor. Older
UI fixtures were updated to enter secondary actions through the existing tools
menu. Their behavioral assertions remain covered.

## Artifacts and limits

Debug APKs: `app/build/outputs/apk/debug/app-<ABI>-debug.apk`.

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | `556c6ab63d7e57abc5236de0c7da5a63f297a6dfad5754e6f1f4baad752fa234` |
| armeabi-v7a | `f6116ea81a89c2c40acaec74a5b684e3d974d8e84a0d0afeecf94d4f7165bf81` |
| x86_64 | `4e6edcf46b3e1c9c8472ab71682fe45df62fafa28476fa34a03cb1a7c85eafc8` |

No physical Android device is connected. OEM input hosts, finger feel and TalkBack
spoken navigation still need physical-device acceptance; accessibility actions
were exercised programmatically. This task did not build Release APKs or create
release signing keys. These artifacts are Debug test packages.
