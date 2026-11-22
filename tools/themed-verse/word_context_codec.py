"""Host-only rANS over exact word-code tokens with deterministic context models."""
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from itertools import accumulate

from huffman_rans import MAX_STREAM, RansReader, encode_intervals


TOTAL = 1 << 18
START = -1
ESCAPE = 0
BACKOFF = 16
REPEAT_WEIGHT = 4


class Successors:
    def __init__(self, counts):
        self.ids = tuple(sorted(counts))
        self.counts = tuple(accumulate((counts[i] for i in self.ids), initial=0))
        self.top = min(counts, key=lambda i: (-counts[i], i)) if counts else None

    @property
    def total(self):
        return self.counts[-1]


EMPTY = Successors({})


class ContextTables:
    def __init__(self, symbols, documents):
        self.symbols = tuple(sorted(symbols))
        self.ids = {symbol: i for i, symbol in enumerate(self.symbols)}
        if not self.symbols or self.symbols[0] != b"" or len(self.symbols) >= TOTAL:
            raise ValueError("word alphabet")
        self.known = range(1, len(self.symbols))
        self.lexical = frozenset(i for i in self.known
                                 if any(char.isalpha() for char in self.symbols[i].decode("utf-8")))
        counts = [1] * len(self.symbols)
        adjacent = defaultdict(Counter)
        for tokens in documents:
            previous = START
            for literal, part in tokens:
                symbol = self.token_id(literal, part)
                counts[symbol] += 1
                adjacent[previous][symbol] += 1
                previous = symbol
        self.prefix = tuple(accumulate(counts, initial=0))
        self.global_cdf = tuple(self.normalize(i, self.prefix[i], self.prefix[-1])
                                for i in range(len(self.prefix)))
        self.bigram = {prev: Successors(row) for prev, row in adjacent.items()}
        self.sparse = {}
        for prev, row in adjacent.items():
            top = sorted(row, key=lambda i: (-row[i], i))[:8]
            retained = {i: row[i] for i in top if row[i] >= 2}
            if retained:
                self.sparse[prev] = Successors(retained)

    def normalize(self, index, numerator, denominator):
        return index + numerator * (TOTAL - len(self.symbols)) // denominator

    def token_id(self, literal, part):
        return ESCAPE if literal else self.ids[part]


class PublicContinuations:
    """A public suffix lookup evaluated only at exact encoded token boundaries."""
    def __init__(self, huffman, data, tables):
        rows = defaultdict(set)
        for line in data.decode("utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            language, prefix, next_word = line.split("\t")
            if language == "zh" and len(prefix) < 2:
                continue
            literal, part = huffman.tokenize(next_word.encode("utf-8"))[0]
            if not literal:
                key = (prefix + (" " if language == "en" else "")).encode("utf-8")
                rows[key].add(tables.ids[part])
        self.rows = {key: tuple(sorted(values)) for key, values in rows.items()}
        self.widths = sorted({len(key) for key in rows}, reverse=True)
        self.limit = max(self.widths, default=0) + 1

    def lookup(self, tail):
        for width in self.widths:
            if width > len(tail):
                continue
            key = bytes(tail[-width:])
            if key.endswith(b" ") and len(tail) > width:
                previous = tail[-width - 1]
                if (48 <= previous <= 57 or 65 <= previous <= 90 or 97 <= previous <= 122 or
                        previous in (39, 95)):
                    continue
            if key in self.rows:
                return self.rows[key]
        return ()


class Distribution:
    """Interpolate a sparse successor row with the global model using an integer CDF."""
    def __init__(self, tables, row, public, recent):
        self.tables = tables
        self.row = row
        self.public = public
        self.recent = recent
        self.context_weight = row.total + len(public) + (REPEAT_WEIGHT if recent is not None else 0)
        self.global_weight = tables.prefix[-1]

    def boundary(self, index):
        if self.context_weight == 0:
            return self.tables.global_cdf[index]
        extra = self.row.counts[bisect_left(self.row.ids, index)] + bisect_left(self.public, index)
        if self.recent is not None and self.recent < index:
            extra += REPEAT_WEIGHT
        weight = BACKOFF * self.tables.prefix[index] + self.global_weight * extra
        return self.tables.normalize(index, weight, self.global_weight * (BACKOFF + self.context_weight))

    def interval(self, symbol):
        start = self.boundary(symbol)
        return start, self.boundary(symbol + 1) - start

    def symbol(self, slot):
        if self.context_weight == 0:
            return bisect_right(self.tables.global_cdf, slot) - 1
        left, right = 0, len(self.tables.symbols)
        while left + 1 < right:
            middle = (left + right) // 2
            if self.boundary(middle) <= slot:
                left = middle
            else:
                right = middle
        return left


class MessageContext:
    def __init__(self, rows, public, cache_tokens):
        self.rows = rows
        self.public = public
        self.cache_tokens = cache_tokens
        self.previous = START
        self.last = {}
        self.tail = bytearray()

    def predict(self):
        return (self.rows.get(self.previous, EMPTY), self.public.lookup(self.tail) if self.public else (),
                self.last.get(self.previous))

    def advance(self, symbol, data):
        if self.previous in self.cache_tokens and symbol in self.cache_tokens:
            self.last[self.previous] = symbol
        if self.public:
            self.tail.extend(data)
            del self.tail[:-self.public.limit]
        self.previous = symbol


class WordContextCodec:
    def __init__(self, tables, rows=None, public=None, cache_tokens=()):
        self.tables = tables
        self.rows = {} if rows is None else rows
        self.public = public
        self.cache_tokens = cache_tokens

    def encode(self, tokens):
        intervals, stats = [], Counter()
        context = MessageContext(self.rows, self.public, self.cache_tokens)
        byte_width, length_width = TOTAL // 256, TOTAL // 16
        for literal, part in tokens:
            symbol = self.tables.token_id(literal, part)
            row, public, recent = context.predict()
            intervals.append(Distribution(self.tables, row, public, recent).interval(symbol))
            if literal:
                intervals.append(((len(part) - 1) * length_width, length_width))
                intervals.extend((b * byte_width, byte_width) for b in part)
            stats.update({
                "tokens": 1, "literal_bytes": len(part) if literal else 0,
                "frozen_contexts": bool(row.total), "frozen_top1": row.top == symbol,
                "public_contexts": bool(public), "public_hits": symbol in public,
                "repeat_contexts": recent is not None, "repeat_hits": recent == symbol,
            })
            context.advance(symbol, part)
        return encode_intervals(intervals, TOTAL), stats

    def decode(self, stream, original_size):
        if not 1 <= original_size <= 2048 or not 4 <= len(stream) <= MAX_STREAM:
            raise ValueError("word frame bounds")
        reader = RansReader(stream, TOTAL)
        context = MessageContext(self.rows, self.public, self.cache_tokens)
        original = bytearray()
        while len(original) < original_size:
            dist = Distribution(self.tables, *context.predict())
            symbol = dist.symbol(reader.slot)
            reader.take(*dist.interval(symbol))
            remaining = original_size - len(original)
            if symbol == ESCAPE:
                length = self.uniform(reader, 16) + 1
                if length > remaining:
                    raise ValueError("literal exceeds original length")
                token = bytes(self.uniform(reader, 256) for _ in range(length))
            else:
                token = self.tables.symbols[symbol]
                if len(token) > remaining:
                    raise ValueError("token exceeds original length")
            original.extend(token)
            context.advance(symbol, token)
        reader.finish()
        original.decode("utf-8")
        return bytes(original)

    @staticmethod
    def uniform(reader, cardinality):
        width = TOTAL // cardinality
        symbol = reader.slot // width
        reader.take(symbol * width, width)
        return symbol
