# Context prediction at word-code boundaries

## Comparison

The offline probe trains on 32 documents from the locked P2 training split
and evaluates 40 P2 calibration excerpts, nine P1 regression documents and
80 short public controls visible during association curation. The 60 P2
holdout excerpts remain sealed. The encoder has access only to the frozen
public resources and the prefix of the current message. Each new message
starts with an empty context.

The Huffman baseline and all rANS arms use the exact same segmentation into
5,410 possible symbols, including phrases, characters, separators and the
literal escape. Unknown bytes carry the same four-bit run length and
literal payload in each arm. The rANS streams include a four-byte state.
All modes are charged the same three-byte body header and the current
64-byte padding formula for the default two encryption layers. The
reported padding buckets are computed from the actual compressed bytes.
[Previous bit-packing baseline](bit-packing-probe.md) ·
[rANS](https://arxiv.org/abs/1311.2540)

The unigram frequencies come from the actual encoded training tokens.
The full bigram arm adds the observed counts for the previous token to a
backoff weight of 16 unigram observations. The sparse arm keeps up to eight
successors per context, each seen at least twice. The public association
arm adds one count to each eligible candidate for the longest matching
suffix ending at the current token boundary. This lookup includes
multi-character Chinese prefixes and space-delimited English prefixes.
If a continuation spans several frozen tokens, the first one is predicted.
The message-local arm adds a weight of four to the successor last seen
after this token in the current message. The unigram-based cache arm uses
the unigram model on a cache miss. A separate ablation limits the cache to
pairs in which both tokens contain letters. All CDFs use integer arithmetic
with positive frequency for every symbol.
[Language model smoothing](https://aclanthology.org/P96-1041/)

## Results

The 40 P2 excerpts use 35,102 Huffman stream bytes. Every test input
restored byte-for-byte with every codec.

| Strategy | P2 stream change vs Huffman | P2 envelopes smaller / larger vs Huffman | P2 padded bytes saved vs Huffman |
| --- | ---: | ---: | ---: |
| Unigram rANS | -144 B | 5 / 1 | 256 B |
| Full frozen bigram rANS | -279 B | 10 / 4 | 384 B |
| Up to eight supported successors | +147 B | 3 / 6 | -192 B |
| Full bigram with public association prior | -278 B | 10 / 4 | 384 B |
| Unigram with message-local successor cache | -1,439 B | 22 / 0 | 1,472 B |
| Full bigram with message-local successor cache | -1,291 B | 19 / 2 | 1,344 B |
| Full bigram with lexical-only successor cache | -1,197 B | 18 / 2 | 1,216 B |
| Shortest of Huffman and the two unrestricted cache modes | -1,766 B | 25 / 0 | 1,856 B |

Comparing the same rANS coder, the full frozen bigram saves 135 stream
bytes relative to unigram across the 40 excerpts. Within that total it
saves 576 bytes on work records and adds 441 bytes on free narrative. The
message-local cache over unigram saves 1,295 stream bytes relative to
unigram. It correctly predicts 1,794 of 11,465 eligible successors in P2.
The lexical-only cache hits 1,284 of 7,098 eligible successors. It has
higher precision and weaker total savings with the same frozen bigram
base. The public table produced 144 opportunities at exact token boundaries
in P2 and zero hits. It had one hit on the nine P1 documents.

The three-way selector reduced 15 of 20 work-record envelopes and 10 of
20 free-narrative envelopes. It cut three of nine P1 envelopes, saving
192 padded bytes. Huffman already attained the shortest stream size for
all 80 curation controls. The rANS state generally made the stream longer
and all modes were in the same padding bucket. The broader selector saved three extra
stream bytes on P2 and reached the same envelope sizes in aggregate.

## Encoding candidate

The simplest single candidate is unigram rANS with one remembered
successor per preceding token, scoped to the message. Its model uses the
global table and a bounded cache. The frozen bigram adds 13,507 edges.
The three-way selector is worth testing as a stronger candidate. It can
select by padded envelope size and prefer the simpler mode when sizes tie.
All choices fit one mode field in the estimate. The existing shared
vocabulary and literal escape preserve byte-for-byte recovery and make
sender and receiver context updates deterministic.

Everyday-message generalization and rendered-carrier readability remain
unverified. A future Android format needs a pinned symbol table, agreement
on the accepted body modes and tuning checked on fresh holdout input.

The probe checks extended Han, emoji, combining characters, CRLF, a
2,048-byte literal fixture, context reset, invalid initial state,
incorrect lengths, truncation and trailing
bytes. Running it with different Python hash seeds reproduces both the
metrics and the fixed fixture payload hashes.

```powershell
python -B tools/themed-verse/word_context_probe.py .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final
```

The directory must contain `lock.json`, `training.zip` and
`calibration.zip` matching the [P2 corpus lock](p2-corpus-lock.json).

## Android Debug port comparison

The Kotlin body codec produced the same selected packets as the Python
reference across the 129 public inputs, including its rule to retain the
simpler mode inside a padding bucket. This table compares the compiled
Kotlin codec with the prior GB18030 body writer. The rows use the default
two-layer envelope size. They measure body and envelope bytes before
rendering the carrier.

| Corpus | Previous / new envelope total | Smaller / larger |
| --- | ---: | ---: |
| 40 P2 excerpts | 49,248 B / 44,256 B | 37 / 1 |
| 9 P1 technical documents | 11,948 B / 15,788 B | 0 / 9 |
| 80 short curation controls | 24,576 B / 21,440 B | 49 / 0 |

The Kotlin sender chose Huffman, local rANS and frozen bigram with local
rANS on 15, 19 and six of the 40 P2 excerpts respectively. The v3
keyboard accepts these three modes under a binding that includes the
public model digest. The GB18030 measurements serve as the historical
comparison. [Authenticated body mode contract](../adr/0041-natural-wording-trial.md#word-context-sender-and-authenticated-body-modes)

Regenerate the read-only public model into a new file and compare it
with the source asset before proposing a change:

```powershell
python -B tools/themed-verse/export_word_context.py .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final .tmp-evidence/word-context-v1-rebuilt.bin
Get-FileHash tools/themed-verse/android-test-profile/word-context-v1.bin, .tmp-evidence/word-context-v1-rebuilt.bin
```

## Rime Essay host body

The calibration probe adds two body candidates to the host research envelope.
Mode 8 emits exact dictionary spellings. Mode 9 renders dictionary leaves with
the first per-character replacement in the pinned OpenCC TSCharacters table.
The sender matches those rendered bytes. Both variants can mix scripts,
combining marks and line endings through the same literal escape.
[Encoder and receiver](../../tools/themed-verse/essay_body.py) ·
[Independent JVM decoder](../../tools/themed-verse/EssayBodyCheck.java)

Both processes rebuild the codebook from Rime Essay at the repository SHA-256
`196d508a9bb12fc6d711eed83f5ddc4d5c2d382d92d15b4e51175e4d3802cfab` and
the ZWC1 source model above. They keep positive Essay weights for words of
1–18 UTF-8 bytes. The ZWC1 multiplier is the retained Essay weight sum
divided by the total ZWC1 weight, rounded down. Add the scaled ZWC1 weights
for counts above one and for the empty literal marker. Add the single
byte FF as a copy leaf with weight equal to the combined total divided by
128, rounded down. Unsigned byte order determines leaf indexes. Huffman
nodes are queued by weight then index. New internal nodes receive increasing
indexes, with the first child on the zero branch. These inputs give 373,244
leaves with maximum depth 29. Duplicate output spellings in mode 9 use the
shortest code and then the numerically smallest code on a tie.
[Rime Essay attribution](../../SOURCES.md) · [Model source](../../tools/themed-verse/android-test-profile/ATTRIBUTION.md)

The stream follows the authenticated mode and two-byte original UTF-8 length.
The empty leaf introduces a four-bit byte count minus one and up to 16
literal bytes. The copy leaf introduces a six-bit length minus three and
an unsigned distance minus one in `ceil(log2(bytes_already_output))` bits.
A copy needs at least one previous byte. Overlap reads bytes in forward
order as they are produced. Copies never span messages. All fields are
most-significant-bit first. The original length ends the stream, followed
by zero to seven zero bits. Invalid UTF-8, leftover bytes, nonzero padding
and out-of-range reads or expansions fail the decode.

The encoder first segments the original bytes by minimum token/literal cost.
It then prices replacing one or more adjacent tokens with a bounded copy in
a second dynamic program. The selector charges the complete padded envelope,
retains the existing modes on a tie, then prefers 8 over 9. The profile
digest hashes four length-prefixed fields with SHA-256: the ASCII domain
`host-essay-lexical-copy/v1`, the prior binary route digest, the binary Essay
source hash and the binary TSCharacters hash. The three-byte body header,
AEAD, per-layer randomness, limits and verse layout retain their original
cost. Reader scores, held-out results and Android resource budgets remain open.
[Calibration results and commands](../project-progress.md#lossless-narrative-body-compression-on-the-host-2026-11-22)
