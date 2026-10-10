# Optional resources validation (2026-10-09)

## Package measurements

All sizes below are physical file bytes, not uncompressed assets. Debug packages
are installable development builds; Release packages are unsigned.

| ARM64 contents | Debug bytes | Unsigned Release bytes |
| --- | ---: | ---: |
| Full | 466,906,429 | 456,662,634 |
| Lite | 52,325,694 | 45,015,435 |

Compared with the previous ARM64 packages, the current Lite Release is 50.1%
smaller and the Full Release is 6.8% smaller. Full includes approximately
352.3 MB of compressed LTS, 42.1 MB of compressed handwriting data and 11.97 MB
of compressed Mini data. Both retain all 16 Wanxiang tables and the reduced CPU
runtime; Lite omits those three optional data packs.

`tools/resources/verify-apk.py` verified Debug full/lite and all three ABI Release
full/lite packages: exact table set, optional resource inclusion/exclusion, old
Luna/essay exclusion, shared glide index, Mini model and bounded ZIP overhead.
Switching resource contents with Android incremental packaging initially retained
removed ZIP bytes. The delivery script now recreates packaging state and checks
physical size, so a nominally lite archive cannot retain that unused space.

## Validation

- 542 Android-module Debug JVM tests and 57 pure-JVM tests passed.
- `privacyCheck`, app Debug Lint, full/lite builds with `-PrequireRime=true`, and
  all three ABI unsigned Release builds passed.
- x86_64 instrumentation verified real, hash-pinned LTS and handwriting ZIP
  installation, native grammar initialization, handwriting recognition and
  continued Chinese input after both resources were removed.
- Resource-store instrumentation checks rollback, archive/file hash mismatch,
  path traversal, missing/duplicate entries, oversized output, cancellation,
  publication signals and leased-file protection during removal.
- Final ARM64 Debug deliveries pass signature verification, exclude `testOnly`
  and keep backup disabled. Their Debug signing is not a release signature.
- Native dictionary instrumentation verified full pinyin, nine-key and double
  pinyin plus installation/removal of a synthetic public dictionary on lite.
- Resource settings were inspected in light/dark themes, portrait/landscape and
  a 720 x 1280 viewport. Content wraps and scrolls within system insets.
- Python publication tools compile; workflow YAML parses. Manual GitHub resource
  publication passed in [run 37875975549](https://github.com/yixunfei/zero-fish-input/actions/runs/37875975549).
  An initial run correctly rejected platform-dependent ZIP metadata; fixing the
  archive creator field preserved reviewed hashes and enabled Linux reproduction.
- Both public releases are published (not drafts). Their asset sizes and GitHub
  SHA-256 digests match the reviewed catalog; public download requests returned
  ZIP headers and the online catalog matches the repository manifest.
- The weekly update workflow is committed but has not been executed on GitHub.
  The repository Actions PR creation setting is enabled (GitHub combines this
  with review approval capability); the workflow never approves or merges PRs.

The resource integration test used the exact prepared release archives copied to
the emulator, isolating installation/native behavior from network availability.
This is not evidence of a complete large-file download on Android. Previous live
source checks had intermittent QQ failures and GitHub download timeouts.
ARM devices were not available; native device execution used x86_64.

## Data reuse and limits

See [ADR 0021](adr/0021-optional-public-resources.md) and [ADR 0022](adr/0022-compressed-resources-and-reduced-runtime.md) for the comparison and
lease/publication design. Wanxiang replaces packaged legacy vocabulary and
supplies the deduplicated glide index, but does not replace librime, schemas or
OpenCC. Main dictionary weights remain intact. The small syllable index retains
nine rare legacy readings. Removing an optional download does not remove bytes
inside a full APK; use lite for package-size reduction.

Resource catalog/scripts/licenses are tracked in Git; binary data and companion
sources are published as immutable GitHub Release assets. App downloads are
explicit, with fixed contracts, byte bounds and archive/per-file SHA-256 checks.
Weekly source updates open review PRs and never automatically merge.

## Further compression assessment

The Lite ARM64 Release payload is approximately 19.3 MB of XZ Wanxiang tables,
4.8 MB of compressed native libraries, 15.9 MB of Emoji assets, 4.2 MB of
code/resources and 0.78 MB of ZIP overhead. The Full package's remaining size is dominated
by the LTS grammar and handwriting stroke data, not duplicate core dictionaries.
Removing those data from Full would make it equivalent to Lite and would lose
the offline convenience that distinguishes Full.

The next lossless opportunities are below the measurement noise for the APK:
the XZ tables are stored without a second ZIP compression pass, native libraries
are DEFLATE-compressed and ABI-split, and the reduced runtime already removes
unused ONNX operators. Further meaningful reductions require a product choice:
Lite as the default download, optional LTS/handwriting/Mini packs, or a separate
ABI download. A separate ABI download is not suitable for normal Android
installation, and additional dictionary deduplication would change ranking
weights. The current split therefore keeps Full offline-capable while making
the default Lite package small.

The reproducible size check is `tools/resources/verify-apk.py`; the delivery
script recreates packaging state so removed optional files cannot leave stale ZIP
space behind. See [package compression validation](package-compression-validation.md)
for component measurements and [ADR 0022](adr/0022-compressed-resources-and-reduced-runtime.md)
for the runtime and XZ trade-offs.
