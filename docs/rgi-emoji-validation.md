# Unicode RGI emoji data and offline artwork

## Pinned public sources

The catalog targets the formally released **Unicode Emoji 18.0**, published with
Unicode 18.0.0 on **2026-09-16**. It contains exactly the 3,963 `fully-qualified`
rows and 9 `component` rows in the official `emoji-test.txt`: **3,972 entries**.
Minimally qualified and unqualified spellings are not additional catalog entries.
Kaomoji and the existing mathematical infinity text expression remain separate
public expressions and are excluded from the RGI coverage and suffix-matcher set.

The final release status was independently rechecked on **2026-09-30**; the
observations and response SHA-256 values are recorded in
`tools/emoji/release-verification.json`. The version page still includes a
preliminary-draft paragraph in its HTML template, but its active CSS sets
`--status_display: none` and the paragraph's `.status` class uses that variable.
The draft notice is therefore hidden in the rendered release page; extracting
HTML text without applying CSS can misleadingly surface it. The more direct
official <https://www.unicode.org/Public/18.0.0/ReadMe.txt> explicitly identifies
this directory as containing final data for Unicode 18.0.0 and its synchronized
technical standards. The version page displays the 2026-09-16 release date and
links an announcement; an independent fetch of that blog article returned HTTP
429, so the announcement body was not used as verification evidence.

The freshly downloaded `emoji-test.txt` is byte-for-byte identical to the locked
fixture. Its header data-generation date is 2026-04-30 and its version is 18.0;
that generation date is not the publication date. The file contains 3,963
fully-qualified entries, 9 components, 1,029 minimally qualified spellings and
243 unqualified spellings. The latter two categories are display test variants,
not additional catalog entries. GitHub's Noto release API reports the fixed tag
as neither a draft nor a prerelease, published at 2026-09-24T17:51:26Z; its Git
tag still resolves directly to the exact commit recorded below. No version,
catalog rows, artwork, or asset hashes needed changing after this review.

- Unicode release: <https://www.unicode.org/versions/Unicode18.0.0/>
- Official test data: <https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt>
  SHA-256: `8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a`.
- Noto Emoji tag: `v2026-09-24-unicode18_0`;
  commit: `e20cbc2bbec1926686be9f9bee7d1d2cfa1fea0e`.
  Most artwork comes from `2D/png/128`; 262 region/subdivision flags come from
  `3D/png/128` at the same commit because the 2D PNG directory does not contain
  those flags. Both paths are recorded per image in the lockfile.
- CLDR tag: `release-48`;
  commit: `acd6d88ae493633240e19a87a721076a8a75c310`.
  English, Simplified Chinese, and Traditional Chinese `annotations` and
  `annotationsDerived` supply names and keywords. The 19 Emoji 18.0 sequences
  absent from this stable CLDR release have explicit local Chinese labels and
  tone labels. These local translations are not represented as CLDR data.

`tools/emoji/sources.lock.json` records every exact URL and SHA-256, including
original PNG checksums and the generated WebP checksums. The images are converted
at their original 128 by 128 resolution to lossless WebP with Pillow 12.2.0,
method 6. There is no font provider, downloadable font, remote image loader,
network dependency, new Android permission, or runtime asset download.

The 3,972 WebP images occupy **15,533,352 bytes (14.81 MiB)**. The uncompressed
catalog is approximately 1.45 MiB. APK packaging overhead and compression are
measured by the central APK build rather than inferred from these source sizes.

## Licensing and reproducibility

Bundled license texts are under `ime-ui/src/main/assets/emoji/licenses`.
Noto's README identifies image resources as Apache-2.0 and flags as public domain
or otherwise exempt from copyright; its root license at this pinned commit is
OFL-1.1 and its `2D/svg/LICENSE` contains the Apache-2.0 artwork notice. Both texts
are preserved, together with the upstream flag license and flag sourcing README.
Unicode/CLDR data retains its Unicode License V3 text. The lockfile pins all of
these texts; the application license page should also link these sources.

From the repository root:

```powershell
python ./tools/prepare-emoji.py --verify
```

This is an entirely offline audit of the exact official sequence set, count,
duplicates, every asset checksum, decoded dimensions, transparency mode, and
nonempty image content. Python and Pillow are preparation/test tools only and do
not ship in the application. Normal Gradle builds require neither tool nor
network access. `:ime-ui:verifyEmojiAssets`, attached to `preBuild`, verifies the
official fixture hash and all packaged catalog/image SHA-256 values.

To reproduce missing generated files from the pinned sources:

```powershell
python ./tools/prepare-emoji.py
```

Regeneration downloads only the pinned public resources, verifies their SHA-256
before using them, retains cached verified source files under `build`, and fails
if the WebP encoder produces a different output hash. Existing historical pinyin
aliases live in `tools/emoji/pinyin-aliases.json` and are merged into the generated
metadata. No personal expressions, favorites, history, editor text, or user data
participates in preparation or source fixtures.

## Runtime and integration

`EmojiCatalog.prepare(context, onReady)` prepares the bounded catalog and immutable
lookup structures on a single background worker. It invokes its completion on
the main thread and returns a `Closeable` to cancel the callback when the owner
is destroyed. The worker retains the application context; callback handles and
panel query/artwork callbacks cannot retain a detached panel.

`EmojiPanelView` owns this lifecycle and displays a loading or unavailable state
until preparation succeeds. An expression editor must also prepare the catalog
before permitting a save that checks built-in duplicates. Kaomoji remain available
without loading the larger emoji catalog. No persistent data format changes.

Category lookup uses prepared indexes. Search runs on one panel worker with one
queued request, a bounded query, and an immutable captured public/personal
snapshot. Each new request cancels the previous one and advances a UI generation.
Privacy revocation clears visible rows synchronously; generation checks prevent
an old search result from repopulating a new session or panel. Detached panels
cancel callbacks, shut down their filter worker, clear rows, and dismiss menus.

Artwork uses a two-worker, 128-request bounded executor and a 4 MiB LRU bitmap
cache. Binding/recycling cancels obsolete requests and removes queued tasks;
decoding reads only packaged public images. Recycled cells also compare their
bound key before accepting a bitmap. No asset reads or bitmap decoding run on
the input/main thread. A selected cell submits its exact Unicode string, not a
bitmap. The receiving application still chooses its own font and presentation.

Long press exposes a variants action independently of personalization permission.
Its scrollable, accessible grid includes skin tones, genders, hair presentations,
families, kissing/couples, and mixed-tone handshakes. Every variant is a complete
official sequence with its own offline image. Favorite/edit actions retain the
existing privacy gate. Flags have a dedicated category and every full sequence
can also be found directly through multilingual names or code point keywords.

`EmojiCatalog.longestEmojiSuffixLength(text)` uses a reverse trie prepared with
the catalog to return the longest complete RGI/component suffix in UTF-16 units.
The maximum official length is 15 UTF-16 units. It scans at most that suffix,
performs no I/O or whole-catalog iteration, and excludes kaomoji/custom text.
The editor adapter can combine this with its existing grapheme fallback for
deletion on Android versions predating the current Unicode release. Before
catalog readiness it returns zero. Historical text presentation that omitted
variation selectors retains its exact saved string and shares canonical artwork;
the plain mathematical infinity expression remains distinct from the infinity
emoji and keeps its existing favorite/history identity.

## Verification and remaining device acceptance

Executed during asset preparation:

- Exact set equality against the checksum-pinned official Unicode file: passed.
- 3,972 unique entries and 3,972 expected images, with zero missing/extra images:
  passed.
- All image hashes, all image decoding/dimensions and nonempty alpha content:
  passed.
- Visual contact sheet containing Emoji 18 additions, ordinary/subdivision flags,
  ZWJ gender/tone, families, keycaps and directional sequences: inspected locally.

Unit tests cover full official coverage, checksums/format, bilingual and pinyin
search, variant membership, every full sequence as a deletion suffix, malformed
and tampered metadata, captured personal snapshots, and historical text identity.
Gradle, Lint, APK, and device validation run centrally with the other input feature
changes; this document does not claim those checks passed independently.

Device acceptance must include Android 8/API 26 and a modern Android image:

1. Open every category offline in light/dark themes and narrow/full/floating
   widths. Confirm loading resolves, graphics do not clip, and all cells remain
   at least 48 dp touch targets.
2. Search the new cracking face and eraser by English/Chinese names; select new
   emoji, a country flag, a subdivision flag, keycap, mixed-tone handshake,
   gender/hair variant, family, and directional sequence. Verify exact submitted
   code points independently of what the recipient font displays.
3. Long-press with personalization disabled, open variants, scroll and select;
   confirm favorite/edit actions remain unavailable. Switch session, hide the
   keyboard, or revoke personalization while a popup/search is active; old clicks
   and delayed results must not commit or repopulate the new editor.
4. Delete each selected sequence with one explicit backspace, including the new
   Unicode 18 additions on API 26. Verify ordinary text and adjacent emoji remain
   intact. Verify the full delete adapter's existing privacy policy separately.
5. Rapidly type searches and scroll/recycle cells while opening/closing panels;
   confirm no wrong images, blocked key dispatch, retained stale personal rows,
   permanent loading indicators, or worker growth after repeated detachment.

An exact asset mapping and successful decode prove bundled display coverage;
they do not by themselves prove device layout, TalkBack interaction, latency,
or receiving-application font coverage. Those remain explicit device checks.
