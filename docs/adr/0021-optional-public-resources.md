# ADR 0021: Full and lightweight resource delivery

Status: accepted (user confirmation, 2026-10-09)

## Decision

Both APK contents retain all 16 Wanxiang core dictionaries and offline Chinese
input. `resourceBundle=full` includes the LTS grammar and Chinese handwriting;
`resourceBundle=lite` omits those optional data files. Both use the same package
identity, code and settings. Installing one replaces the other through Android's
normal package update rules. The existing short-word model remains bundled.

Optional data is published to content-versioned GitHub Releases in
`yixunfei/zero-fish-input`. Git stores only manifests, hashes, licenses and scripts.
The user explicitly checks the reviewed catalog and confirms each download in
dictionary settings. There is no startup check, background download or input
upload. The existing dictionary transport enforces HTTPS and redirect host bounds.

This supersedes ADR 0020's build-time-only grammar rule solely for the known
Wanxiang LTS data contract. It also authorizes the known handwriting data files.
The app accepts only named packs, fixed file sets and compatible data formats from
this project's catalog. Every archive and file has a size and SHA-256 bound.
Archive paths never determine extraction targets. Unknown files, duplicates,
truncation, oversized content and digest errors reject the entire installation.
Executable code, native libraries, scripts and arbitrary grammar/model imports
remain prohibited. ONNX and Octagram runtimes stay compiled into the application.

PublicResourceSource in engine-api provides worker-owned file leases. The
language-pack resource store owns immutable hash-addressed blobs and atomic
registry publication. Engine/model consumers retain leases while native readers
are alive. Removal unpublishes a resource without invalidating active readers;
unreferenced blobs are collected on subsequent resource mutations. Rime changes
only with no active editor engine. Handwriting resource revisions cancel stale
results and recreate the recognizer on its worker. No resource I/O is added to
the key event path, and no downloaded data enters private user stores.

The full APK supplies the same resource contracts through its assets. A bundled
resource and identical downloaded resource share the same extracted content.
Rime references the resource-store grammar file rather than extracting another
400 MB copy. Disabling a bundled feature does not reclaim bytes inside an APK;
users choose the lightweight APK to reclaim packaged resources.

## Shared dictionary data

Wanxiang already supplies the native pinyin translator. A build-time projection
of its character/core tables now supplies 24,000 unique weighted glide readings,
shared by full, nine-key and double-pinyin layouts. The older Luna and essay source
files remain build/test inputs but are excluded from APKs. The small syllable
index remains generated from the reviewed legacy source to preserve nine rare
readings absent from Wanxiang. Rime runtime code, schemas and OpenCC conversion
data are not dictionaries and cannot be replaced by Wanxiang tables.

An exact text/reading comparison found 46,453 of 70,803 Luna rows in Wanxiang;
this is not a semantic coverage score because simplified/traditional forms were
not normalized. Wanxiang's 2,214,029 normalized rows contain 11,631 duplicate
text/reading pairs. Main-table weights are retained; merging these pairs without
frequency/ranking analysis risks changing candidates for negligible size gain.
The glide projection deduplicates readings by strongest public frequency.

## Publication and review

Weekly updates continue to open a PR, never auto-merge. The PR includes the
updated optional LTS catalog. After reviewing licenses, hashes and native tests,
run the explicit publication workflow for that reviewed commit, then merge the
catalog. Resource releases include attribution and applicable source/rebuild
archives, including LGPL Tegaki source materials. No APK is published by this
workflow. Older immutable resources remain available.

## Consequences

The light APK loses immediate handwriting and LTS ranking until the user installs
those resources, but normal dictionary-based Chinese input remains available.
The full APK retains first-install offline features. Downloads need temporary
space for the archive and verified output. Online availability depends on GitHub;
failed downloads leave installed data unchanged. New resource formats require a
new app-supported contract and cannot be enabled just by changing a remote URL.
