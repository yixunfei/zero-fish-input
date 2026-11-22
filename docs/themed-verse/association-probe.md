# Default associations and wording capacity

## Experimental setup

The offline probe builds 1,994 distinct phrases from the built-in public
association TSV. Chinese prefixes and continuations are joined directly.
English phrases use a space. The normalized TSV SHA-256 is
`778d14920b9a4f505c42cb29f8394778ea459ad1125562c04107349821da8d23`.
The probe uses GB18030 and the app's zlib level on the host, checks byte-exact
restoration for every trial, and calculates final envelope length for the default
AES-256-GCM and XChaCha20-Poly1305 stages with the three-byte body header.
[zlib preset dictionary format](https://www.rfc-editor.org/rfc/rfc1950#section-8.1)

The current 15,021-byte preset is the baseline. The first comparison replaces
it with an equal-sized slice of association bytes. The second reserves 25%
of the same dictionary budget for associations. Two expanded mixes retain
the full baseline and add association bytes up to the 32,768-byte window,
testing both orders. The corpus includes 40 frozen independent P2 calibration
excerpts, nine P1 regression documents and 80 short positive controls assembled
from the public scenarios visible during association curation. The locked P2
holdout had zero attempts.

| Dictionary | P2 calibration, stream delta | P2 envelopes smaller / larger | P1 regression, stream delta | Synthetic controls with smaller envelopes |
| --- | ---: | ---: | ---: | ---: |
| Associations only | +3,293 B | 0 / 35 | −120 B | 48 / 80 |
| Same-budget mix | +511 B | 0 / 8 | −96 B | 44 / 80 |
| Expanded mix, baseline last | −32 B | 0 / 0 | −115 B | 48 / 80 |
| Expanded mix, associations last | +335 B | 0 / 8 | −141 B | 48 / 80 |

The P2 baseline stream total is 38,205 bytes. The expanded mix with the
baseline last saved 0.084% before padding. None of the 40 excerpts contained
a complete association phrase. All 80 synthetic controls contained one.
The synthetic result shows the benefit on familiar examples. An independent
everyday-message corpus is needed to estimate the
rate at which that benefit occurs in typical use.

A work-record excerpt needed a median 40.5-byte reduction to move down a
64-byte bucket. Free narrative needed a median 30 bytes. For the short
controls above the smallest bucket, the median was seven bytes. These
thresholds explain why a similar compressed-byte gain can have a different
effect on the carrier length.

## Carrier costs

The scenery-linked five-character branches have 43,754 to 66,637 choices
at each step. Comparing the log of that branching range with the average
18.25 UTF-8 bytes per line gives 0.845–0.878 encrypted bits per carrier byte,
excluding the opening line. Seven-character branches have 24,291 to 36,323
choices across 24.25 bytes per line, giving 0.601–0.625. Seven-character
verse adds a form and distinct poetry material. Five-character verse
remains denser for the same encrypted payload. An unconstrained pool of all
92,141 distinct seven-character lines would reach
0.680 bits per carrier byte with this layout. Tests for pool expansion
should measure both branching and readability.

The keyboard suggests at most eight continuations per context. Using only
that list as the choices for a carrier step gives a ceiling of three bits
per choice and needs a public fallback for contexts without continuations.
For plaintext compression, a frozen public table can instead assign short
representations to high-probability phrases when both sides use the same
table. Personal frequency and session state would create different candidate
orders across devices. Literal fallbacks are needed for exact recovery. This
should be evaluated through the 64-byte padding step and against an independent public
everyday-message set before selecting a dictionary. The follow-up evaluates
phrase IDs and secondary coding of word-level Huffman bytes on the host.
[Bit-packing audit](bit-packing-probe.md) ·
[Predictive modeling and lossless compression](https://arxiv.org/abs/2309.10668)

```powershell
python -B tools/themed-verse/association_probe.py .tmp-evidence/themed-verse-p2-corpus-20261120/calibration-input
```

The command accepts a directory with `lock.json` and `calibration.zip`
matching the repository's [P2 corpus lock](p2-corpus-lock.json).
