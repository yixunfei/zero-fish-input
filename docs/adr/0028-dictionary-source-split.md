# ADR 0028: Preserve sixteen dictionary outputs after the upstream source split

Status: accepted (user confirmation, 2026-11-01)

## Context

The weekly update failed on October 19 and 26 because newer rime-wanxiang
releases removed several dictionary files. The upstream data repository carries
fourteen of ZeroInput's sixteen categories; independent lianxiang and renming
tables are absent. Dropping them or publishing empty substitutes would change
the approved coverage and dictionary settings behavior.

## Decision

Retain all sixteen existing output names. Explicit refresh resolves a commit of
`amzxyz/rime-wanxiang-data` and pins its archive SHA-256 for fourteen tables.
The existing reviewed lianxiang and renming inputs retain their original archive,
revision and hashes in `retainedSources`. Each table has exactly one source owner;
unknown, duplicate, missing and empty tables fail preparation. No runtime data
format, category identifier or user-data migration is introduced.

The new repository's CC-BY-4.0 grant applies to its original data, not its program
code. Only named dictionary files and license metadata are consumed. Upstream
programs, schemas and configuration are not executed or integrated. Retain its
license-scope notice in `LICENSES/wanxiang-data-LICENSE-SCOPE.md`; changed license
bytes require a separate review. All current sixteen-table normalized hashes
remain recorded in the proposal, including the retained inputs.

New LTS proposals use a fixed GitHub asset-ID API URL and verify both size and
SHA-256. Resolving a proposal never writes the lock. Tables and model are staged
and fully checked before publishing generated output and the proposal lock;
handled publication failures restore the prior output. This is not an atomic
power-loss transaction across a directory and a lock file: interrupted work must
still pass the existing lock, metadata and prebuilt verifiers before use.

The weekly job builds the pinned native deployer, regenerates the matching
default table/prism/reverse artifacts and verifies them before updating the bot
PR. Only a recognized bot-authored tip may be replaced, using an exact lease.
One shared commit subject is used for both ownership checking and new commits.
The job does not approve, merge or publish resources/APKs. Android/native-device
and performance acceptance remain separate merge requirements. Explicit resource
publication follows reviewed merge under ADR 0021's main-ancestry checks.

## Consequences and alternatives

The retained categories intentionally stop following upstream until a separately
reviewed replacement exists; their provenance remains visible and reproducible.
The update job consumes more host time to rebuild default artifacts. Current
main's reviewed pins and Android behavior are unchanged until a data PR is
accepted. Reducing to fourteen categories was offered and not selected. Empty
tables, unpinned downloads, weakening verifiers and automatic merge were rejected.
