# ADR 0009: Personal expressions and offline kaomoji

Status: Accepted by the user on 2026-09-06.

## Context

The existing 77-entry emoji panel has no kaomoji, favorites or custom entries.
History already uses a dedicated encrypted store, but its 32-UTF-16-unit limit
rejects longer expressions. The user approved an expanded catalog, nested
kaomoji categories, search, favorites and custom editing, with personal data
following the existing editor privacy policy.

## Decision

- Keep the public catalog and bounded in-memory search in ime-ui. Public data
  never comes from editor contents, personal storage or a runtime download.
  Kaomoji data has a pinned LGPL-3.0 source; original and adapted source data are
  distributed in APK assets with license and rebuild instructions.
- The app builds a bounded public kaomoji keyword index for built-in Rime
  composition and offers at most four matches after conversion candidates.
  An injected core port supplies the current permitted custom-expression
  snapshot without storage I/O on key dispatch. Personal matches receive up
  to two places before public matches. Selection rechecks the current item and
  candidate identity; sensitive or privacy-tightened sessions cannot retain
  personal matches. This does not alter the encrypted expression format.
- Favorites and custom expressions belong to user-data. A separate
  personal-expressions.bin uses the zeroinput.personal-expressions.v1 Keystore
  alias and the existing AES-256-GCM EncryptedStore under noBackupFilesDir.
  It has no dependency on the UI, engine or platform input session.
- Use a new, versioned, length-prefixed binary format for this new store. It
  limits custom and favorite counts to 256 each, text to 128 UTF-16 units, names
  to 48, keywords to 256, group keys to 32, and file size to 512 KiB. Decode with
  strict UTF-8 and reject duplicate identities/values, trailing data, invalid
  lengths and unknown versions. Reject control characters, invalid surrogates
  and directional overrides. Preserve ordinary spaces, combining characters,
  variation selectors and ZWJ sequences.
- Repositories perform serialized background I/O without a plaintext process
  cache. Publish only completed snapshots; failures must not become an empty
  dataset that a later update can overwrite. Clear invalidates queued work
  before taking the storage lock and removes the file and its dedicated key.
  Failed deletion blocks further access until retry succeeds.
- The app owns session and privacy leases, background operations, management
  and user-visible failures. The IME rechecks leases before reads/writes and
  before applying results. Personal selections also require a current library
  revision; a removed or edited custom entry cannot be committed through an old
  visible binding. Adapter replacement clears retired text and invalidates old
  gestures and menus synchronously.
- Management is a nonexported, secure Activity excluded from recents. Fields
  disable saved state, autofill, content capture and personalized IME learning.
  Leaving cancels pending work, clears rows and dismisses/clears drafts. Explicit
  management in settings is available even when editor personalization is off;
  the IME itself never reads or shows personal entries under that policy.
- Keep emoji-history.bin, its key alias and JSON format 1. Extend only validated
  text length, add strict bounded decoding and remove the unsafe pre-write map
  mutation. Recent results resolve against current catalog/custom values, so
  deleted or edited custom text is no longer offered. Historical values remain
  in the bounded encrypted history until eviction or explicit data clearing.
- The existing Clear personal data command deletes phrases, history, favorites,
  custom expressions and their dedicated keys. Its confirmation names all of
  those data types. User initiated expression export/import serializes the
  validated format and encrypts it with a dedicated
  `zeroinput.personal-expressions-export.v1` Android Keystore alias. Export
  files are device-bound; clearing app data or that alias makes them
  unrecoverable. Import remains bounded and fails closed on authentication or
  format errors.

## Consequences

Long expressions use variable column spans and wrapping while retaining complete
text. Public expressions remain available in private editors; favorites, custom
entries and history are hidden. Automatic learning and manual favorites are
separate commands, but both IME write paths require permitted personalization.
There is no new module, runtime dependency, permission or exported component.
The export key is separate from the on-device repository key, so sharing an
export does not expose the storage key or permit use on another device.

The new file needs no migration. Existing emoji history remains readable and no
dual-format or dual-write compatibility path is introduced. Future schema changes
must be explicitly decided; malformed input cannot silently migrate or reset.

## Alternatives

Putting all data in the history file would couple manual content to recency
eviction and require a format migration. Using preferences would expose custom
text and favorites outside encrypted storage. Putting kaomoji in Rime would make
the panel dependent on the active Chinese engine and complicate session policy.
Downloading a catalog at runtime would violate the project's offline boundary.

## Verification

Unit tests cover catalog/search/subtags, personal filtering, Unicode, format
bounds, cancellation, failed persistence and clear/write races. Device tests
cover real Keystore corruption/loss, long history values, stale cells, narrow
layouts and the management workflow. All use public fixtures; diagnostics contain
no personal expressions, keywords or actual editor text.
