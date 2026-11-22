# Wording body bit-packing audit

## Information per byte

One byte holds eight bits and has 256 values. A seven-bit field has 128
values. Two arbitrary letters from a 26-letter alphabet have 676 possible
pairs and need at least 10 bits when the length is known. Letter strings of
length three and four need at least 15 and 19 bits respectively. Longer
strings can pack across byte boundaries. A 32-symbol alphabet takes exactly
five bits per symbol. [Byte and prefix coding definition](https://www.rfc-editor.org/rfc/rfc1951#section-2) ·
[Five-bit groups in Base32](https://www.rfc-editor.org/rfc/rfc4648#section-6)

A public codebook can represent a whole known phrase with one ID. A seven-bit
code can select one of 127 fixed phrases with one value reserved for literal
input. If both sides know the same prefix and its ordered list of eight
continuations, an index takes three bits before any escape or boundary
overhead. Pronunciation or an alphabetic label still needs a way to
distinguish homophones. The probe assigns IDs to the exact terms and uses
bounded UTF-8 literals when a term is absent.

The existing reference key-replay codec already packs spelling at five
bits per symbol. A five-letter spelling still occupies 25 bits before
recording its length and any non-default candidate index. The phrase
probe packs variable-length codes consecutively, with padding only at
the end of the stream.
[Prior spelling and candidate-rank accounting](p2-plan.md)

## What the app actually encodes

1. The current Android body mode converts the original UTF-8 to reversible
   GB18030 and emits a zlib stream using its frozen preset. The packet
   carries one mode byte, two bytes of original UTF-8 length and the
   compressed stream. The word-level Huffman codec and the secondary
   encoders below are host research code. [DEFLATE format](https://www.rfc-editor.org/rfc/rfc1951#section-3.2) ·
   [zlib dictionary field](https://www.rfc-editor.org/rfc/rfc1950#section-2.2)
2. Add 36 bytes of body-frame data and round up to a multiple of 64
   bytes. Authenticate and encrypt with the chosen stages. The default
   AES-256-GCM and XChaCha20-Poly1305 combination adds 204 bytes of framing,
   metadata and tags outside the padded body. A stream must cross a
   padding boundary to reduce the envelope size.
3. Map the complete envelope to a mixed-radix integer with one leading
   sentinel bit. This frame preserves leading zero bytes. The secondary
   compression experiments apply exclusively before authentication and
   encryption.

## Experiments on public input

The host trained frequency tables on 32 locked P2 training excerpts.
The 127-entry table uses full association phrases ranked by training hits.
The Huffman arm combines the existing word-and-character counts with a
one-count prior for each of the 1,994 default association phrases, yielding
5,410 symbols. Only one multicharacter phrase in that expanded model
received a code of eight bits or fewer. The second passes test rANS with
one shared byte histogram, rANS conditioned on the previous byte's high
four bits, and raw LZMA2 with a 4 KiB window. Both rANS tables train on
Huffman codes from the 32 training excerpts. The measured stream sizes
include the two-byte intermediate length and four-byte rANS state, or
the two-byte intermediate length and native raw LZMA2 framing. Padding
uses the default envelope formula. The probe uses no holdout examples.
[rANS algorithm](https://arxiv.org/abs/1311.2540) ·
[Raw LZMA stream and bounded decoder](https://docs.python.org/3/library/lzma.html)

The baseline for this table is the direct word-level Huffman byte stream.

| Strategy | P2 stream change, 40 excerpts | P2 envelopes smaller / larger | P1 envelopes smaller / larger, 9 documents | Short controls smaller, 80 messages |
| --- | ---: | ---: | ---: | ---: |
| Seven-bit IDs with an escape | +50,288 B | 0 / 40 | 0 / 9 | 0 |
| Huffman followed by global rANS | +233 B | 0 / 5 | 0 / 2 | 0 |
| Huffman followed by context rANS | +563 B | 0 / 8 | 0 / 3 | 0 |
| Huffman followed by raw LZMA2 | +222 B | 1 / 4 | 2 / 0 | 0 |
| Shortest stream, including direct Huffman | -8 B | 1 / 0 | 2 / 0 | 0 |

The short controls were visible during association curation. The fixed
IDs spend most of their bits on literals in the independent excerpts.
Selecting the shortest stream saved one 64-byte envelope bucket across
40 calibration excerpts. It saved 192 padded bytes across nine regression
documents. The second pass adds no benefit to the short controls. The
global rANS result would still add 153 bytes across calibration even if
the two-byte intermediate length were free.
The limited gain supports retaining direct Huffman as the research
baseline and testing context probabilities at word boundaries.
Adopting either word-code format on Android requires a new authenticated body
mode and a pinned codebook. The APK contains the menu change.

All streams round-tripped byte for byte before their sizes were counted.
The probe also checks the literal fallback with extended Han, emoji,
combining characters and three newline variants. The secondary decoders
reject truncated streams, appended bytes and invalid declared lengths.
These results measure compression and envelope size. Independent
everyday-message coverage and a reader evaluation of complete carriers
remain open.

```powershell
python -B tools/themed-verse/bit_packing_probe.py .tmp-evidence/themed-verse-p2-corpus-20261120/frozen-final
```

The directory must hold `lock.json`, `training.zip` and `calibration.zip` from
the [P2 corpus lock](p2-corpus-lock.json).

[Follow-up using context probabilities at word-code boundaries](word-context-probe.md)
