# ADR 0020: Explicit public dictionary downloads

Status: accepted (user approval, 2026-10-09)

The build-time-only grammar delivery decision below is superseded by
[ADR 0021](0021-optional-public-resources.md) for reviewed optional resources.

## Decision

Dictionary settings may fetch public catalogs and selected data from Wanxiang,
Rime Ice, fcitx5-pinyin-zhwiki, QQ and Sogou. The user confirms browsing and
installation in the foreground. QQ/Sogou use native category and pagination rows;
there is no arbitrary URL input or WebView. Leaving the page cancels unfinished
work. Publication already committed to disk is retained.

The dedicated `app/dictionaries/DictionaryDownloadTransport` is the additional
network adapter. It accepts HTTPS on named source/file hosts, rejects credentials,
fragments and nonstandard ports, validates every redirect, and bounds time and
response size. Requests contain only public catalog/file identifiers. They never
read editor text, learning data, clipboard, package identity or AI credentials.
Sources can observe the network address and selected dictionary.

Downloaded Rime YAML and QQ/Sogou cell data pass bounded parsers and become
uniform text/code/weight entries. Safe YAML loads only header data; no tags,
aliases, scripts, schemas or upstream configuration are executed. SQLite dedup
and atomic registry publication retain the previous item on parsing failure.
Public data lives separately in noBackupFilesDir and never enters personal stores.
Source snapshots hold the store lock while Rime copies their immutable files.

The APK includes all 16 Wanxiang core tables and the current simplified Chinese
LTS grammar model. These CC-BY-4.0 assets are pinned by source revision, download
SHA-256 and normalized file SHA-256. Only this trusted build-time `.gram` data is
loaded by the statically linked Octagram component; arbitrary language packages
and runtime downloads cannot install grammar models or code. Contextual
suggestions and Rime user dictionaries stay disabled; grammar ranks current
composition without saving prior editor text.

Immutable base assets are shared by app-created private references between
deployment generations; imported paths can never create or select these links.
The engine worker applies changed dictionaries only with no live editor engines,
verifies conversion, and removes obsolete generations. In-process deployment
failure restores the last verified generation, also recorded atomically for
subsequent process starts. Public settings publication and
native activation are distinct; downloads remain installed if deployment fails.

Weekly GitHub Actions resolve upstream assets, convert and verify them, and open
or update a review PR. No automatic merge or APK publication is permitted. The
reviewed lock is the package input; ordinary Gradle builds do not fetch data.
Other sources are opt-in device downloads and are not redistributed in the APK.

## Consequences

The complete model is about 380 MiB; the APK and first native deployment are large.
Preparation needs network/disk space; installed conversion remains fully offline.
Source website layout changes may make browsing unavailable and must fail closed.
Legacy/unknown cell encodings are rejected rather than guessed. Repository owners
must enable Actions PR creation, review licensing changes and run native/device
checks before merging data updates.

## Alternatives

A small core/model subset would reduce size but violates the confirmed scope.
WebView browsing and arbitrary downloads enlarge the attack surface. Automatic
upstream merges bypass license and input-quality review.
