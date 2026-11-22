# ADR 0042 · Distributed exact-text carriers

Accepted 2026-11-22. New senders and receivers use distributed complete carriers.
Old terminal complete carriers require the previous application. Detached v1
ciphertext remains readable with its original cover. Natural wording is independent.

## Wire format

Operate on exact UTF-8 without normalization. Enumerate internal UTF-16 gaps
where both adjacent code units are in U+0020..007E, U+4E00..9FFF, U+3001..3002
or U+FF01..FF5E. This fixed repertoire keeps segmentation stable across Android
versions and avoids splitting complex character sequences. With no eligible gap,
emit a single frame before the entire cover. Otherwise, for N eligible gaps and
L envelope bytes, use C = min(16, N, L) frames. Frame i, starting at zero, goes
in gap floor((2i+1)N/(2C)) of the zero-based gap list.

For L > 1, the rotation S is 1 + (first two SHA-256 cover digest bytes as an
unsigned big-endian integer modulo (L-1)). For L = 1, S = 0. Column i contains
envelope bytes at (S+i+rC) modulo L for successive rows r with i+rC < L.
Each visible column is canonical unpadded Base64url inside `[ZIS1:2:…]`.
Unicode uses U+2063 U+2060 U+2063 U+2062, the existing byte-to-selector
alphabet and U+2063 U+2061 U+2063. Receivers bound counts, validate column
lengths and compare with canonical re-encoding before inspecting the envelope.

## Security and usability

The cover-dependent ordering is public. A filter can discard frames, and a
format-aware reader can reconstruct the envelope. Confidentiality and integrity
come from the existing credential-backed AEAD and exact cover binding. Visible
Base64url retains a plain-text transport option. Unicode may be altered during
copying, editing or normalization. Copy the complete message. With up to 16
frames, the dense upper bounds are 5,368 UTF-16 units and 11,360 UTF-8 bytes.
