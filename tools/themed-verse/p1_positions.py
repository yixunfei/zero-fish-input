"""Static positions over a complete, frozen public vocabulary."""
from bisect import bisect_left
from struct import Struct

from rime_location_codec import runs


ROW_BASE = 0x800000
SHORT_BASE = 0xc000
ROW_COUNT = 2214029
SHORT_COUNT = 8192
REVERSE_HEADER = Struct(">8sII")
REVERSE_MAGIC = b"ZIVROWS1"
FORWARD_MAGIC = b"ZIVFWD01"
U32 = Struct(">I")
OFFSETS = Struct(">II")
REVERSE_FILE = "position-reverse.bin"
FORWARD_FILE = "position-forward.bin"


class PositionIndex:
    def __init__(self, reverse, forward=None):
        self.reverse = reverse
        self.forward = forward
        self.short_start = REVERSE_HEADER.size + U32.size * (ROW_COUNT + 1)
        self.words_start = self.short_start + U32.size * SHORT_COUNT
        if (len(reverse) <= self.words_start
                or REVERSE_HEADER.unpack_from(reverse) != (REVERSE_MAGIC, ROW_COUNT, SHORT_COUNT)
                or U32.unpack_from(reverse, REVERSE_HEADER.size)[0] != 0
                or U32.unpack_from(reverse, self.short_start - 4)[0] != len(reverse) - self.words_start):
            raise ValueError("position reverse index")
        if forward is not None:
            size = len(forward) - len(FORWARD_MAGIC)
            if not forward.startswith(FORWARD_MAGIC) or size < 4 or size % 4:
                raise ValueError("position forward index")
            self.entries = range(size // 4)

    def _word(self, row):
        offset = REVERSE_HEADER.size + U32.size * row
        start, end = OFFSETS.unpack_from(self.reverse, offset)
        return self.reverse[self.words_start + start:self.words_start + end]

    def _short_row(self, code):
        return U32.unpack_from(self.reverse, self.short_start + U32.size * (code - SHORT_BASE))[0]

    def get(self, token):
        code = int.from_bytes(token, "big")
        if len(token) == 3 and ROW_BASE <= code < ROW_BASE + ROW_COUNT:
            return self._word(code - ROW_BASE).decode("utf-8")
        if len(token) == 2 and SHORT_BASE <= code < SHORT_BASE + SHORT_COUNT:
            return self._word(self._short_row(code)).decode("utf-8")
        return None

    def _code_at(self, index):
        return U32.unpack_from(self.forward, len(FORWARD_MAGIC) + U32.size * index)[0]

    def _word_at(self, index):
        code = self._code_at(index)
        row = code - ROW_BASE if code >= ROW_BASE else self._short_row(code)
        return self._word(row)

    def terms(self, raw):
        """Find the locked Han terms matching substrings of this message."""
        matches = {}
        for run in set(runs(raw)):
            for start in range(len(run)):
                lower = 0
                for end in range(start + 1, min(len(run), start + 64) + 1):
                    word = run[start:end]
                    prefix = word.encode()
                    lower = bisect_left(self.entries, prefix, lower, key=self._word_at)
                    if lower == len(self.entries):
                        break
                    hit = self._word_at(lower)
                    if not hit.startswith(prefix):
                        break
                    if hit == prefix:
                        code = self._code_at(lower)
                        matches[word] = code.to_bytes(3 if code >= ROW_BASE else 2, "big")
        return matches
