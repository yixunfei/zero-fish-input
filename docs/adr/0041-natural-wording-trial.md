# ADR 0041 · Natural wording carriers in Debug

Status · Debug trial concluded on 2026-11-22. The five-character verse, seven-character verse and window observation profiles are maintained in the Debug keyboard. Release activation still requires the protocol, held-out capacity, independent reading, detection and device reviews. The original single-layer format below belongs to the public five-character verse sample.

The three keyboard profiles share the wrapped envelope, explicit carrier selection, complete-text preview and authenticated receiver. Preview reports the actual original and carrier UTF-8 byte counts and their ratio. The 20% benchmark belongs to research and public-fixture reporting. Existing profile identifiers, authenticated domain strings and public vectors retain their original bytes.

## Context

The controlled Android trial needs to accept actual drafts in the existing
steganography workspace. P2 selected the five-character same-or-next-family
pool and the frozen GB18030 preset dictionary. The existing two exact-text
transports authenticate a user-entered cover. The generated verse depends
on the ciphertext, so an exact-cover digest cannot serve as its input.
[Implementation plan](../themed-verse/implementation-plan.md) · [Calibration](../themed-verse/p2-theme-calibration.md)

## Trial format and binding

- Both sides explicitly select the five-character verse trial. The opening
  table is 79,074 sorted five-character fragments. Each subsequent pool
  contains fragments from the last scenery family in the previous fragment
  or its successor. Mixed-radix digits are emitted least-significant first.
  The public layout uses fullwidth commas and stops, with LF after every
  fourth fragment except the last. The recipient reconstructs the exact
  canonical text from the recovered envelope and compares it to the input.
- The profile digest is SHA-256 over the ASCII bytes of
  `ZeroInput/natural-wording-trial/profile/v1`, a zero byte, then the three
  binary SHA-256 digests of `opening.bin`, `gb-map.bin` and `gb18030.zdict` in
  that order. The asset hashes and canonical byte representation are pinned
  in the offline builder. Version 1 covers the ordered families, terminal
  layout and packet rules implemented by the trial. A changed rule requires
  a distinct version. The frozen digest is
  `474dafd74732f7533dfe1cd1df60c049098a7ccae368a90c84c7da22a75fd229`.
  [Asset builder](../../tools/themed-verse/prepare_android_verse.py)
- The binding is `SHA-256(fields(domain("natural-wording-trial"), profileDigest))`
  using the [existing envelope's](0039-text-steganography.md) domain prefix and
  big-endian length-delimited fields. The authenticated metadata's binding
  byte is `2`. The same digest participates in metadata derivation and AAD,
  the layer transcript and the authenticated inner frame. The exact-text
  and public P2 domains have binding byte `1` and a different digest input.
- The trial uses one AES-256-GCM body layer and existing XChaCha20-Poly1305
  metadata authentication. The credential is exactly 32 random bytes encoded
  as 43 canonical unpadded Base64url characters. The header's public bytes
  at offsets 16–19 are `02 00 00 26` (random master, KDF none, 38-byte
  encrypted metadata). The authenticated descriptor must specify the single
  AES-256 layer. The trial validates one selected suite and one profile.
- The inner binary payload is `04 || u16be(original UTF-8 byte count) || stream`,
  with a GB18030 transformation followed by one RFC 1950 zlib stream and the
  pinned preset dictionary. The payload is at most 2,048 B and the original
  at most 2,048 B. The envelope is `98 + 64 × ceil((36 + payload bytes) / 64)`
  bytes. The final digit must cross the capacity threshold for exactly one
  eligible envelope bucket. The entire input is limited to 8,192 UTF-16 units
  and 16,384 UTF-8 bytes. Only authenticated payloads reach bounded decompression.
  Recovering a different original byte count, trailing compressed data or an
  invalid Unicode sequence fails the operation without publishing plaintext.

## Entry and consequences

Debug keeps the exact-text default and offers an explicit carrier picker in
the protected keyboard workspace. The sender generates with a user-supplied
or locally generated random key, reviews the complete verse and deliberately
inserts it or invokes the existing authenticated save. The recipient selects
the verse mode before loading a selection or using an approved paste source.
The secure clipboard handoff carries the transient mode in the same one-use,
generation-checked request. Production sharing and AI import remain governed
by their existing marker recognition rules.

Whole-carrier hard limits reject generation. The initial trial reported whether
`original UTF-8 bytes / complete UTF-8 bytes` reached 20%. The maintained
keyboard reports the measured percentage and permits deliberate insertion below
that research target. The renderer's visible length exposes the framing
bucket. A recipient needs a byte-preserving text channel. Capacity and texture
results are calibration evidence. Release activation requires review of the
formal profile and entry, held-out capacity, independent naturalness and
detector results and device validation.

## Wrapped Debug trial (2026-11-21)

The entries in this section describe the initial wrapped Debug build.
See [Seven-character verse and current receive choices](#seven-character-verse-and-current-receive-choices)
for the current keyboard picker.

The sender selects five-character verse or daily notes. The receiver selects
the same profile. The previous single-layer carrier remains readable through
the explicit legacy verse entry with its original 256-bit random key.
Each profile uses the same frozen GB18030 codec and dictionary. The new
profile digest hashes the ASCII prefix in the original format with version
`v2/verse` or `v2/daily`, then a zero byte and SHA-256 of the surface table,
the GB map and the zlib dictionary in that order.

| Profile | Surface table | Profile SHA-256 |
| --- | --- | --- |
| Five-character verse | `opening.bin` | `43db59e71130de344d7b6fb1306f2bb17dee89aa74eb6168fc5ecbf61a4731c8` |
| Daily notes | `daily.bin` | `40534ac0e43f37412270be7ee10645bcdcca7d8ad0a8ed6c44a15b3b16b4834e` |

- Binding byte 3 uses `Hash(F(D("natural-wording-wrapped"), profileDigest))`.
  The 44-byte public bootstrap and authenticated padded body frame follow
  ADR 0039. The encrypted metadata appends each stage's raw `bits/8`-byte
  key immediately after its nonce. Each key and nonce is a separate
  `SecureRandom` draw per message. Metadata remains authenticated and encrypted
  with XChaCha20-Poly1305 under the credential-derived key. The transcript
  includes the complete metadata and still authenticates each body layer.
  Open processes layers in reverse and releases the GB18030 packet after all
  tags and the frame binding pass. The metadata length is 54–296 bytes, and
  the complete envelope is 178–2,548 bytes. The writer adds the key bytes
  to the ADR 0039 size formula. Mutable key and metadata buffers are cleared.
- The workspace starts with AES-256-GCM and XChaCha20-Poly1305. It allows
  one to six unique algorithms in the existing order editor. Widths are
  set per algorithm, or a user-selected option samples a width independently
  from each selected algorithm's allowed widths on the worker. The five
  portable credential types use ADR 0039 derivation and cost profiles.
  New passphrases can be any nonempty strict UTF-8 up to 1,024 bytes without
  NUL. The receive worker inspects bounded framing before deriving a key.
  Credential or cost changes return to the workspace before a retry.
  A higher Argon2id profile requires an explicit confirmation and enough
  memory. The password-derived wrapping key remains the offline guess target.
- Mixed-radix digits represent the unsigned big-endian integer
  `0x01 || envelope`, least-significant digit first. The sentinel restores
  leading zero bytes, and the most significant digit must be nonzero.
  The receiver checks both whole-carrier budgets and the exact canonical
  rendering before opening the envelope. Verse uses the original pools
  and layout. The daily asset starts with a u16 count of sorted two-character
  time phrases, then a u8 topic count and a u16 count plus sorted four-character
  phrases for each topic. Text fields are UTF-16BE and counts are big-endian.
  On an opening clause the digit selects the time and the globally sorted
  phrase using `timeIndex * phraseCount + phraseIndex`. A second clause selects
  from the first phrase's topic. An opening clause takes a fullwidth comma
  when followed by a second clause. Each sentence ends in a fullwidth stop.
  An LF follows every sixth clause except at the end. The offline builder
  checks the pinned Wanxiang base lexicon and generates four lists with
  700, 605, 475 and 338 reviewed-form combinations and 50 time phrases.
  [Daily data builder](../../tools/themed-verse/wording_daily.py)
- Settings can explicitly generate and save a standalone raw 32-byte random
  key file through the local document picker. The private host accepts one
  selected new empty document and writes after foreground confirmation. The
  file is usable through the existing bounded key-file reader, including as
  the independent factor alongside a passphrase. The file's raw bytes are
  sensitive. The workspace keeps the profile and direction for the one-use
  matching-editor return. The user reacquires message drafts after navigation.

The extra key bytes and authentication tags count toward the carrier budget.
Preview reports the complete carrier length, including all punctuation and
line breaks. The Debug trial retains the capacity and naturalness review gates
for release and requires a channel that preserves the complete text.

## Receive and explicit paste recovery (2026-11-21)

The Debug receive workspace can hold bounded pasted or selected text before
the recipient chooses its wording profile. A text value with exact-carrier
markers still follows the exact format parser. With unmarked text staged,
Start decryption opens the wording style picker directly. Selecting a wording
profile checks the canonical text and public envelope header on the crypto
worker. The result guides the recipient to the required credential and
preserves the text if they select a different profile.

The header inspection uses no credential and performs no KDF. Start
decryption verifies the selected profile, cost and complete authentication
again before returning plaintext. System clipboard acquisition remains one
explicit menu click through the existing opt-in adapter and workspace lease.

## Seven-character verse and current receive choices

This revision of the Debug keyboard offered exact carriers, five-character verse,
seven-character verse and daily notes. The wrapped five-character and daily
profiles retain the digests above. The keyboard accepts only wrapped envelopes
under its wording profiles. The single-layer format remains in the public
fixture workbench for offline regression tests.

Seven-character verse follows the same scenery family and punctuation rules as
five-character verse. The pinned Wanxiang poetry data yields 42,153 distinct
opening lines and 33,468, 24,291 and 36,323 continuations in the three pools.
`seven.bin` stores 590,142 bytes of sorted UTF-16BE data and is verified before
use. The profile digest uses `v2/verse-seven`, the SHA-256 of `seven.bin` and
the same map and compression dictionary. Its value is
`3a7a873d5efe498a9d3bfd349025b25c2325a5d68ce60bdb015ea55fcfedf530`.
Send and receive apply the same canonical layout, authenticated binding and
16,384-byte carrier budget. Independent readability and detection tests remain
release requirements.

[Capacity and default association measurements](../themed-verse/association-probe.md)

## Wording category

The sender and receiver choose Natural wording at the carrier level, then
select five-character verse, seven-character verse or window observations. Opening
the category preserves the active draft. Confirming the concrete style
starts the usual receive profile inspection. The daily notes trial was
retired from the Android sender and receiver. Its public host benchmark
remains available for reproducing earlier results.

At this stage the APK retained the GB18030 body mode for wrapped
messages. The host tool measured secondary coding of Huffman bytes.
[Body bit-packing audit](../themed-verse/bit-packing-probe.md)

## Word-context sender and authenticated body modes

The Debug keyboard selects a lossless body mode for each outgoing
natural-wording message. The v3 profile uses the existing wrapped
envelope. The mode byte and a big-endian UTF-8 byte count
precede the compressed stream. This three-byte header is encrypted and
authenticated with the rest of the body. Packet and original UTF-8
lengths are bounded at 2,048 bytes.

| Mode | Meaning |
| --- | --- |
| 5 | Frozen word Huffman, with a four-bit literal-run length and zero tail padding |
| 6 | Word rANS with a global prior and a message-local last successor |
| 7 | Word rANS with the frozen adjacent-token row and the message-local last successor |

Modes 5–7 identify model ZWC1. Its 153,939-byte asset is pinned to
SHA-256 `be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f`.
The model contains exactly the 5,410 symbols and 2,819 context rows from
the locked public training split and public association lexicon. Changes
to the model or coding rules require a new authenticated profile binding.
The model and per-work attribution are Debug assets.
[Model export and training lock](../../tools/themed-verse/export_word_context.py)

Each profile digest is SHA-256 over the ASCII string
`ZeroInput/natural-wording-trial/profile/` followed by `v3/verse`,
`v3/verse-seven` or `v3/prose-window-attested-slots`, a zero byte, the 32-byte SHA-256
of the surface table, and the 32-byte SHA-256 of ZWC1, in that order.
The digest feeds the existing wrapped binding. The keyboard derives a
single binding for the chosen style and accepts only the three
authenticated body modes above. The standalone public-fixture workbench
uses its separate research assets.

| Binding label | Surface asset | v3 profile SHA-256 |
| --- | --- | --- |
| `v3/verse` | `opening.bin` | `787982af5f4d1528f92ae81e2f482f785644b2e273901a113555b36f4d32a27c` |
| `v3/verse-seven` | `seven.bin` | `41c2538dfef35d10b6855c01bbc053458877e5cc43c37d29c7a2767bad362874` |
| `v3/prose-window-attested-slots` | `window-observation.bin` | `d215c735861e022dceee9e8a5213c91e3b5a498e6f4676e73720b562513d455e` |

The sender runs the existing minimum-bit segmentation once for all
three codecs. It selects the lowest wrapped size for the chosen
encryption stages and keeps the earlier mode in the table when sizes
tie. A Huffman packet in the lowest frame bucket already wins. All
other candidates use the same literal bytes and model. For modes 6
and 7, rANS uses total 2^18, initial and terminal state 2^23 and a
four-byte little-endian transmitted state. Its integer CDF interpolates
the global token frequencies with the optional row using backoff
weight 16 and adds weight four for the remembered successor. A literal
has a uniform four-bit length-minus-one followed by uniform bytes.
The receiver reconstructs context from decoded symbols of that message.

The sender recovers and compares the chosen packet before applying
its credential and encryption. The receiver dispatches the body mode
only after the existing full AEAD open. The new reader separately checks
the model hash, mode, lengths, UTF-8, completion state and trailing
bits. Previously generated trial carriers use earlier bindings and
need to be regenerated to exchange with the v3 keyboard.
[Compression experiment](../themed-verse/word-context-probe.md)

## Window observation profile

The Debug keyboard offers Window observations under Natural wording.
The frozen graph builds on the refined window arm. An authored window
corpus supplies noun spans and typed clause frames, joined with the 16
locked Wanxiang tables. Public n-gram counts and Mini rank eligible
forms offline. The graph binds the object and condition to a matching observation
action. A window-frame obstruction must lead to seeing the whole object.
Seven optional paired observations add nearby-to-distant views and
physical events such as leaf movement, ripples and changes in light.
Their first sentences lead to a required follow-up in the next phase.
The graph preserves the five-cycle optical schedule.
The host also screens additional noun slots against the locked Mandarin
corpus and OpenCC mapping. Reviewed objects enter authored sentences
only after checking the complete normalized source for an exact match.
[Host validation](../natural-wording-steganography.md#20-语料词槽与受限重复排名)

The `ZWO1` asset is 235,920 bytes and pinned to SHA-256
`3a8b44dfe515c5d1498fa09cf9b07a0fdc41937598bc718feab9be1e6b01ca1d`.
The exporter stores it as `window-observation-v4.bin`. Android uses
the stable asset name in the binding table above.
All integers are big-endian. Two u16 counts give the sentence and state
totals. Each sentence has a u8 length and UTF-16BE BMP Han text. Each state
has a u8 accepting flag and u16 group count. A group stores its u8 width
including the fullwidth stop, u16 destination and u16 member count followed
by that many u16 sentence indices. State zero is the start. Within each
length bucket the rank follows the frozen group and sentence order.
Only concatenated sentences ending with a fullwidth stop are canonical.
[Public graph exporter](../../tools/themed-verse/export_window_observation.py)

The rank is the unsigned integer for `01 || envelope` minus one. Decode
recovers the exact envelope length, including leading zeros, and verifies
canonical rendering. The app limits the complete wrapped envelope to 833
bytes and rejects larger envelopes before encryption. Every 833-byte value
fits the existing carrier budget. The all-`FF` 524-byte and 833-byte test
vectors occupy 10,302 and 16,371 UTF-8 bytes respectively. Older window
and daily carriers require their corresponding trial APK.

The existing worker runs rank and recovery with cancellation checks.
Send builds exact counts after sealing and releases them after immediate
readback. Receive validates the sentence path before building counts and
releases counts before KDF. Android uses the frozen graph without a Mini
model or dictionary scan. The generated asset and codec are Debug-only.
The approved wrapped cryptography and existing per-profile credential
workflow also apply to this profile. Complete-carrier language and
detection review and the 20% utilization target remain open.
