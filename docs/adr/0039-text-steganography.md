# ADR 0039: Offline text steganography and authenticated messages

Status: accepted for implementation, 2026-11-18.

## Context

The user requested implementation of revision 2 of the
[design](../text-steganography-design.md): an independent portable format, six
cipher families, ordered cascades, valid key widths, one master credential and
optional key files. The user explicitly declined the proposed system-clipboard
body-read exception. Selection, plain-text sharing and authenticated private-vault
reads are the supported import sources. Existing uncommitted audit work is retained.

## Decision

- Keep all message operations offline. No provider, AI context, system clipboard,
  page extraction, telemetry, new permission or exported component is added.
  The existing SEND/PROCESS_TEXT gateway offers an explicit local-decrypt branch;
  recognized and damaged carrier markers deny AI import at the input model boundary.
- `security/message` owns the bounded portable envelope, credentials and crypto.
  `ime-core/stego` owns strict exact-text transport and operation phases. `ime-ui`
  renders focus-free fields and intentions; `app/textstego` owns foreground
  workflows, source/authentication coordination and live editor binding. Neither
  the engine controller nor user-data storage understands the message format.
- Use pinned Bouncy Castle 1.87 lightweight GCM/Argon2/HKDF and Tink Android 1.21.0
  explicit-nonce XChaCha20-Poly1305. Do not register/replace a JCE provider, store a
  Tink keyset, add native crypto or import the reference application's code.
  Dependency licenses/hashes are recorded in THIRD_PARTY and SOURCES. No custom
  GHASH, HChaCha or block cipher is implemented. Generic GCM profiles have no
  certification or aggregate-strength claim. Managed table engines and JVM copies
  are not guaranteed constant-time or fully erasable.
- AES, Twofish and Camellia support 128/192/256-bit keys; Kuznyechik and XChaCha20
  support 256; SM4 supports 128. A cascade contains one to six unique algorithms,
  encrypted in the chosen order and decrypted in reverse. Every layer authenticates;
  there is no partial success, trial-decrypt fallback or algorithm substitution.
- Credentials are transient: passphrase, canonical unpadded Base64url 32-byte random
  key, file only, passphrase plus file, or random key plus file. Creation requires
  a passphrase of at least 12 Unicode code points. Exact UTF-8 is used without
  normalization. A key-file factor requires the same 32-byte..1-MiB file contents;
  names, URIs and permissions are not persisted. Predictable files add no entropy.
- Argon2id v19 profiles are (64 MiB, 3), (128 MiB, 3), (256 MiB, 4), all p=1 and
  32-byte output. Higher profiles require an operation-specific acknowledgement.
  UI checks heap capacity; the sole crypto worker checks actual headroom, optionally
  reclaims completed managed work, then refuses inadequate memory. It never lowers
  the requested profile. One Argon2 call serves all layers and preparation self-check.
- Preserve the two exact-text carriers. Unicode is detectable and may be stripped;
  visible Base64url is explicitly recognizable. Natural wording remains unavailable:
  no reviewed resource/tokenizer/corpus satisfies section 3.4's capacity, semantic
  and detector gates. The UI explains this and requires an explicit carrier choice.
- Secrets and credentials use independent, unpersonalized local conversion/input
  sessions. They never traverse another IME or external InputConnection. Protected
  windows use FLAG_SECURE, no autofill/content capture/view-state persistence, and
  default masked text. Field switching is internal; mode/session/settings/lock and
  source navigation revoke drafts and results. Active drafts expire after 120 s;
  prepared results after 30 s, checked against elapsed time on consumption.
  Leaving the mode restores the editor candidate snapshot before status rendering;
  the panel does not retain a second text-state object after clearing its views.
- Preparation authenticates/decrypts its own envelope before preview without a
  second KDF. Explicit confirmation commits only the encoded text once to the
  original actual connection, session and interaction. It never invokes Send,
  learns, rereads output or retries an uncertain edit. Entering with a selection
  must not clear that selection through an empty composing edit.
- Decryption defaults to local masked viewing. Plaintext insertion is a separate
  warning and fresh current-editor check. A private Activity cannot insert or
  return plaintext to a caller. Vault read/save each uses a new system-authentication
  grant followed by another foreground click and deletion-generation check.
- Cross-screen handoffs contain an opaque one-use ID only. The bounded pending
  save owns one mutable result under the existing PendingClipboardAddition policy;
  no Activity, InputConnection or secret is put in an Intent/saved state. A temporary
  file factor may return only to a matching public editor identity, followed by an
  explicit new operation. Settings, lock, timeout and vault generation revoke it.
- Crypto and source I/O each have one process-wide nonqueueing slot. Cancellation
  revokes publication and closes file descriptors/signals but does not free a slot
  until running work returns. Workers own buffers and weak foreground references;
  stale/duplicate/late deliveries clear their owned mutable result.

## Frozen v1 format

All integers use unsigned big-endian encoding. `F(a,b,...)` is a concatenation of
`u32be(length) || bytes` for every field. `D(s)` is the ASCII bytes
`ZeroInput/message/v1/` followed by `s`. Hash/HMAC are SHA-256/HMAC-SHA-256.

Public bootstrap: random 16-byte salt, credential ID, KDF ID, u16 encrypted-metadata
length, fresh 24-byte metadata nonce (44 bytes). Credential IDs 1..5 follow the order
above. KDF IDs are 0=none, 1=standard, 2=increased, 3=high memory. Only password modes
allow nonzero KDF. Encrypted metadata length is 38..120 including its 16-byte tag.

File factor is `Hash(F(D("key-file"), u64be(actualLength), Hash(fileBytes)))`.
Password+file factor is `HMAC(fileFactor,F(D("passphrase-file"),passwordUtf8))`;
random+file uses `F(D("random-key-file"),random32)`. Single modes use their exact
password/random/file factor. Password modes run Argon2id(factor,salt,profile).
HKDF extract is `prk=HMAC(salt,derivedOrDirectFactor)`.

Exact-cover binding is `Hash(F(D("exact-cover"),coverUtf8))`. The metadata key is
HKDF-expand(prk,`F(D("metadata-key"),bootstrap,binding)`,32); AAD is
`F(D("metadata-aad"),bootstrap,binding)`. Metadata uses XChaCha20-Poly1305, separate
from selected payload layers. Metadata bytes are version=1, count=1..6, flags=0,
binding version=1, u32 padded frame size, then ordered descriptors: algorithm ID,
key-size code, nonce. IDs AES/XChaCha/Kuznyechik/Twofish/Camellia/SM4 are 1..6.
Key-size codes 1/2/3 mean 128/192/256 bits; only matrix-valid pairs are accepted.
XChaCha nonces are 24 bytes; other GCM nonces are 12; all tags are 16 bytes.

Transcript is `Hash(F(D("descriptor"),bootstrap,metadataPlain,binding))`. Layer
identity is four bytes: zero-based index, stage count, algorithm ID, size code.
Layer key is HKDF-expand(prk,`F(D("layer-key"),transcript,identity)`,keyBytes);
AAD is `F(D("layer-aad"),transcript,identity)`. The secret frame is u32 UTF-8
length, 32-byte binding, exact secret UTF-8 and fresh random padding to a 64-byte
multiple. Concatenate bootstrap, encrypted metadata and the fully layered body.
Salt, every nonce and padding come from SecureRandom. Reserved values, duplicate
stages, wrong widths, trailing bytes, invalid UTF-8, NUL and all failed tags reject.

Secret budget is 2048 UTF-8 bytes; cover 512 UTF-16 units/1536 UTF-8 bytes; envelope
2372 bytes; carrier 8192 UTF-16 units/16384 UTF-8 bytes. Check public bounds before
KDF and exact authenticated body length afterward. No truncation or message splitting.

Dense transport appends U+2063 U+2060 U+2063, one selector per byte (0..15 maps to
U+FE00..FE0F; 16..255 to U+E0100..E01EF), then U+2063 U+2061 U+2063. Visible
transport appends `[ZIS1:` + canonical unpadded Base64url + `]`. Require one complete
terminal block and exact cover code points; no Unicode normalization or repair.

## Evidence and consequences

Published RFC 5869/9106/8998, NIST AES-GCM and IETF XChaCha vectors plus independent
RustCrypto comparisons cover every cipher width, every KDF profile, all credential
combinations and complete single/six-layer envelopes. Regression tests cover all
720 six-layer orders, mixed widths and authenticated outer layers around a damaged
inner tag. The [validation record](../text-steganography-validation.md) reports
device/build evidence and untested channel/platform limits. These are implementation
checks, not a security audit, proof of undetectability or channel-delivery guarantee.

Existing encrypted vault formats and Keystore aliases are unchanged. The earlier
single-suite proposal was never deployed; no compatibility reader/migration is added.
The rejected clipboard adapter, persistent message keys, external plaintext export,
automatic decrypt and a resource-free wording substitute remain outside v1.
