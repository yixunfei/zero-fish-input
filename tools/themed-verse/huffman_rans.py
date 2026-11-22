"""Byte rANS for the offline word-code experiment, with shared trained tables."""

TOTAL = 1 << 12
LOWER_STATE = 1 << 23
MAX_STREAM = 4096


def encode_intervals(intervals, total):
    state = LOWER_STATE
    tail = bytearray()
    for start, frequency in reversed(intervals):
        threshold = (LOWER_STATE // total) * 256 * frequency
        while state >= threshold:
            tail.append(state & 255)
            state >>= 8
        quotient, residue = divmod(state, frequency)
        state = quotient * total + residue + start
    return state.to_bytes(4, "little") + bytes(reversed(tail))


class RansReader:
    def __init__(self, data, total):
        self.data = data
        self.total = total
        self.offset = 4
        self.state = int.from_bytes(data[:4], "little")
        if len(data) < 4 or not LOWER_STATE <= self.state < LOWER_STATE * 256:
            raise ValueError("rANS initial state")

    @property
    def slot(self):
        return self.state % self.total

    def take(self, start, frequency):
        quotient, residue = divmod(self.state, self.total)
        self.state = quotient * frequency + residue - start
        while self.state < LOWER_STATE:
            if self.offset == len(self.data):
                raise ValueError("rANS truncation")
            self.state = self.state * 256 + self.data[self.offset]
            self.offset += 1

    def finish(self):
        if self.offset != len(self.data) or self.state != LOWER_STATE:
            raise ValueError("rANS terminal state or trailing bytes")


class Table:
    def __init__(self, counts):
        # Reserve one slot for every byte so unseen text stays representable.
        count = sum(counts)
        weights = [divmod(n * (TOTAL - 256), count) for n in counts]
        self.frequencies = [part + 1 for part, _ in weights]
        remaining = TOTAL - sum(self.frequencies)
        order = sorted(range(256), key=lambda symbol: -weights[symbol][1])
        for symbol in order[:remaining]:
            self.frequencies[symbol] += 1
        self.starts = []
        self.symbols = bytearray()
        for symbol, frequency in enumerate(self.frequencies):
            self.starts.append(len(self.symbols))
            self.symbols.extend(bytes([symbol]) * frequency)


class HuffmanRans:
    def __init__(self, training, contextual):
        self.contextual = contextual
        counts = [[0] * 256 for _ in range(17 if contextual else 1)]
        global_counts = [1] * 256
        for stream in training:
            previous = None
            for symbol in stream:
                counts[self.context(previous)][symbol] += 1
                global_counts[symbol] += 1
                previous = symbol
        # Shrink sparse conditional histograms toward the global distribution.
        total = sum(global_counts)
        prior = [max(1, n * 64 // total) for n in global_counts]
        self.tables = [Table([n + p for n, p in zip(row, prior, strict=True)]) for row in counts]

    def context(self, previous):
        if not self.contextual:
            return 0
        return 16 if previous is None else previous >> 4

    def encode(self, stream):
        if not 1 <= len(stream) <= MAX_STREAM:
            raise ValueError("rANS input length")
        intervals = []
        for index, symbol in enumerate(stream):
            table = self.tables[self.context(stream[index - 1] if index else None)]
            intervals.append((table.starts[symbol], table.frequencies[symbol]))
        return len(stream).to_bytes(2, "big") + encode_intervals(intervals, TOTAL)

    def decode(self, encoded):
        size = int.from_bytes(encoded[:2], "big")
        if not 1 <= size <= MAX_STREAM or not 6 <= len(encoded) <= 2 * MAX_STREAM + 6:
            raise ValueError("rANS frame length")
        reader = RansReader(memoryview(encoded)[2:], TOTAL)
        previous = None
        result = bytearray()
        for _ in range(size):
            table = self.tables[self.context(previous)]
            symbol = table.symbols[reader.slot]
            reader.take(table.starts[symbol], table.frequencies[symbol])
            result.append(symbol)
            previous = symbol
        reader.finish()
        return bytes(result)
