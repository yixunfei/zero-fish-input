"""Reversible bit layouts for the P2 public corpus experiment."""
from collections import Counter
from dataclasses import dataclass

from p1_positions import ROW_BASE, ROW_COUNT, SHORT_BASE, SHORT_COUNT
from rime_location_codec import event, tokens as position_tokens


ASCII, SHORT, ROW, LITERAL, KEY = range(5)
PREFIXES = ((0, 1), (2, 2), (6, 3), (14, 4), (15, 4))
ROW_WIDTHS = {SHORT: 13, ROW: 22}
ROW_LIMITS = {SHORT: SHORT_COUNT, ROW: ROW_COUNT}
BLOCK = 32
COMMON_CODES = 64
MAX_KEYS = 128
MAX_KEY_LENGTH = 12
MAX_RANK = 32
FIELDS = ("type", "payload", "boundary", "exception", "padding")
LAYOUTS = (
    "prefix", "bitmap", "bitmap-width", "bitmap-sparse", "freq-fixed", "freq-reseg",
    "key-direct", "key-rank",
)


@dataclass(frozen=True)
class Token:
    kind: int
    value: int | bytes | tuple[str, int]


class Bits:
    def __init__(self):
        self.body = bytearray()
        self.head = self.pending = 0
        self.cost = dict.fromkeys(FIELDS, 0)

    def put(self, value, width, field):
        if not 0 <= value < (1 << width):
            raise ValueError("bit field range")
        self.cost[field] += width
        self.head = (self.head << width) | value
        self.pending += width
        octets = self.pending // 8
        self.pending %= 8
        if octets:
            self.body.extend((self.head >> self.pending).to_bytes(octets, "big"))
            self.head &= (1 << self.pending) - 1

    def finish(self):
        self.put(0, -self.pending % 8, "padding")
        return bytes(self.body), self.cost


class Reader:
    def __init__(self, stream):
        self.stream = stream
        self.offset = 0

    def take(self, width):
        end = self.offset + width
        if end > 8 * len(self.stream):
            raise ValueError("truncated bits")
        start, last = self.offset // 8, (end + 7) // 8
        value = int.from_bytes(self.stream[start:last], "big")
        self.offset = end
        return (value >> (-end % 8)) & ((1 << width) - 1)

    def finish(self):
        remaining = 8 * len(self.stream) - self.offset
        if remaining >= 8 or self.take(remaining):
            raise ValueError("noncanonical tail")


def row_token(code):
    value = int.from_bytes(code, "big")
    return Token(SHORT, value - SHORT_BASE) if len(code) == 2 else Token(ROW, value - ROW_BASE)


def row_code(token):
    if not 0 <= token.value < ROW_LIMITS[token.kind]:
        raise ValueError("unknown row")
    if token.kind == SHORT:
        return (SHORT_BASE + token.value).to_bytes(2, "big")
    return (ROW_BASE + token.value).to_bytes(3, "big")


def p1_tokens(stream):
    result = []
    for kind, value in position_tokens(stream):
        if kind == "row":
            result.append(row_token(value))
        elif kind == "literal":
            result.append(Token(ASCII, value[0]) if len(value) == 1 and value[0] < 128
                          else Token(LITERAL, value))
        else:
            raise ValueError("expected static P1 positions")
    return result


def p1_stream(items):
    out = bytearray()
    for token in items:
        if token.kind == ASCII:
            out.append(token.value)
        elif token.kind == LITERAL:
            out.extend(bytes([255, len(token.value) - 1]) + token.value)
        elif token.kind == KEY:
            out.extend(event(*token.value))
        else:
            out.extend(row_code(token))
    return bytes(out)


def p1_cost(items):
    counts = dict.fromkeys(FIELDS, 0)
    for token in items:
        if token.kind == ASCII:
            counts["type"] += 1
            counts["payload"] += 7
        elif token.kind == LITERAL:
            counts["type"] += 8
            counts["boundary"] += 8
            counts["payload"] += 8 * len(token.value)
        else:
            width = ROW_WIDTHS[token.kind]
            counts["type"] += 16 - width if token.kind == SHORT else 24 - width
            counts["payload"] += width
    return counts


def blocks(items):
    for start in range(0, len(items), BLOCK):
        yield items[start:start + BLOCK]


def put_bitmap(bits, kinds):
    # Bit planes preserve the order of all values, including repeated types.
    for shift in (1, 0):
        plane = 0
        for kind in kinds:
            plane = (plane << 1) | ((kind >> shift) & 1)
        bits.put(plane, len(kinds), "type")


def get_bitmap(reader, size):
    high, low = reader.take(size), reader.take(size)
    return [2 * ((high >> i) & 1) + ((low >> i) & 1) for i in reversed(range(size))]


def sparse_types(bits, kinds):
    default = min(Counter(kinds).items(), key=lambda pair: (-pair[1], pair[0]))[0]
    exceptions = [(i, t) for i, t in enumerate(kinds) if t != default]
    index_width = (len(kinds) - 1).bit_length()
    sparse = 2 + len(kinds).bit_length() + len(exceptions) * (index_width + 2) < 2 * len(kinds)
    bits.put(int(sparse), 1, "boundary")
    if not sparse:
        put_bitmap(bits, kinds)
        return
    bits.put(default, 2, "type")
    bits.put(len(exceptions), len(kinds).bit_length(), "boundary")
    for i, kind in exceptions:
        bits.put(i, index_width, "exception")
        bits.put(kind, 2, "exception")


def read_sparse(reader, size):
    if not reader.take(1):
        return get_bitmap(reader, size)
    default = reader.take(2)
    count = reader.take(size.bit_length())
    if count >= size:
        raise ValueError("type exceptions")
    kinds, previous = [default] * size, -1
    for _ in range(count):
        index = reader.take((size - 1).bit_length())
        kind = reader.take(2)
        if not previous < index < size or kind == default:
            raise ValueError("type exception position")
        kinds[index] = kind
        previous = index
    return kinds


def get_prefix(reader):
    for kind in range(4):
        if not reader.take(1):
            return kind
    return KEY


class Layout:
    def __init__(self, name, frequencies):
        if name not in LAYOUTS:
            raise ValueError("position layout")
        self.name = name
        self.bitmap = name.startswith("bitmap")
        self.frequency = name.startswith(("freq-", "key-"))
        self.keys = name.startswith("key-")
        self.rank_footer = name == "key-rank"
        self.rows = frequencies
        self.ranks = {kind: {value: i for i, value in enumerate(values)}
                      for kind, values in frequencies.items()}

    def token_bits(self, token):
        kind, value = token.kind, token.value
        if kind == ASCII:
            return 8
        if kind == LITERAL:
            return 10 + 8 * len(value)
        if kind in ROW_WIDTHS:
            width = ROW_WIDTHS[kind]
            if self.frequency:
                width = 1 + (6 if value in self.ranks[kind] else width)
            return PREFIXES[kind][1] + width
        keys, rank = value
        if not self.rank_footer:
            return 4 + 8 * len(event(keys, rank))
        return 4 + 4 + 5 * len(keys) + 1 + (0 if rank == 0 else 3 if rank <= 7 else 8)

    def encode(self, items):
        if not 1 <= len(items) <= 2048:
            raise ValueError("token count")
        bits = Bits()
        bits.put(len(items), 12, "boundary")
        if self.bitmap:
            for group in blocks(items):
                self._put_block(bits, group)
        else:
            ranks = []
            for token in items:
                bits.put(*PREFIXES[token.kind], "type")
                if token.kind == KEY:
                    self._put_key(bits, token.value, ranks)
                else:
                    self._put_value(bits, token, ROW_WIDTHS)
            if self.rank_footer:
                put_ranks(bits, ranks)
        return bits.finish()

    def _put_block(self, bits, group):
        kinds = [item.kind for item in group]
        if any(kind == KEY for kind in kinds):
            raise ValueError("event in static layout")
        if self.name == "bitmap-sparse":
            sparse_types(bits, kinds)
        else:
            put_bitmap(bits, kinds)
        widths = ROW_WIDTHS.copy()
        if self.name == "bitmap-width":
            for kind in ROW_WIDTHS:
                values = [t.value for t in group if t.kind == kind]
                if values:
                    widths[kind] = max(values).bit_length()
                    bits.put(widths[kind], ROW_WIDTHS[kind].bit_length(), "boundary")
        for token in group:
            self._put_value(bits, token, widths)

    def _put_value(self, bits, token, widths):
        kind, value = token.kind, token.value
        if kind == ASCII:
            bits.put(value, 7, "payload")
        elif kind == LITERAL:
            bits.put(len(value) - 1, 6, "boundary")
            bits.put(int.from_bytes(value, "big"), 8 * len(value), "payload")
        else:
            code = self.ranks[kind].get(value) if self.frequency else None
            if self.frequency:
                bits.put(int(code is None), 1, "exception")
            if code is None:
                bits.put(value, widths[kind], "payload")
            else:
                bits.put(code, 6, "payload")

    def _put_key(self, bits, value, ranks):
        keys, rank = value
        if not self.keys or not 1 <= len(keys) <= MAX_KEY_LENGTH or not 0 <= rank < MAX_RANK:
            raise ValueError("event bounds")
        if self.rank_footer:
            bits.put(len(keys) - 1, 4, "boundary")
            for letter in keys:
                bits.put(ord(letter) - 97, 5, "payload")
            ranks.append(rank)
        else:
            size = len(keys)
            packed = event(keys, rank)
            padding = -5 * size % 8
            bits.put(packed[0], 8, "boundary")
            bits.put(int.from_bytes(packed[1:1 + (5 * size + 7) // 8], "big") >> padding,
                     5 * size, "payload")
            bits.put(0, padding, "padding")
            if rank:
                bits.put(rank, 8, "exception")

    def parse(self, stream, size):
        if not 1 <= size <= 2048 or not 1 <= len(stream) <= 4 * size + 2:
            raise ValueError("position bounds")
        reader = Reader(stream)
        count = reader.take(12)
        if not 1 <= count <= size:
            raise ValueError("token count")
        if self.bitmap:
            items = self._read_blocks(reader, count)
        else:
            items = []
            for _ in range(count):
                kind = get_prefix(reader)
                items.append(self._read_key(reader) if kind == KEY else
                             self._read_value(reader, kind, ROW_WIDTHS))
            if self.rank_footer:
                ranks = iter(read_ranks(reader, sum(t.kind == KEY for t in items)))
                items = [Token(KEY, (t.value[0], next(ranks))) if t.kind == KEY else t for t in items]
        reader.finish()
        return items

    def _read_blocks(self, reader, count):
        result = []
        for start in range(0, count, BLOCK):
            size = min(BLOCK, count - start)
            kinds = read_sparse(reader, size) if self.name == "bitmap-sparse" else get_bitmap(reader, size)
            widths = ROW_WIDTHS.copy()
            if self.name == "bitmap-width":
                for kind, cap in ROW_WIDTHS.items():
                    if kind in kinds:
                        widths[kind] = reader.take(cap.bit_length())
                        if widths[kind] > cap:
                            raise ValueError("row width")
            result.extend(self._read_value(reader, kind, widths) for kind in kinds)
        return result

    def _read_value(self, reader, kind, widths):
        if kind == ASCII:
            return Token(kind, reader.take(7))
        if kind == LITERAL:
            size = reader.take(6) + 1
            return Token(kind, reader.take(8 * size).to_bytes(size, "big"))
        if self.frequency and not reader.take(1):
            rank = reader.take(6)
            if rank >= len(self.rows[kind]):
                raise ValueError("frequency code")
            return Token(kind, self.rows[kind][rank])
        value = reader.take(widths[kind])
        if value >= ROW_LIMITS[kind] or (self.frequency and value in self.ranks[kind]):
            raise ValueError("row ID")
        return Token(kind, value)

    def _read_key(self, reader):
        if not self.keys:
            raise ValueError("unsupported event")
        if self.rank_footer:
            size = reader.take(4) + 1
            if size > MAX_KEY_LENGTH:
                raise ValueError("key length")
            codes = [reader.take(5) for _ in range(size)]
            if max(codes) >= 26:
                raise ValueError("key alphabet")
            return Token(KEY, ("".join(chr(97 + char) for char in codes), 0))
        tag = reader.take(8)
        if not 0xe0 <= tag < 0xfc or (tag & 15) >= MAX_KEY_LENGTH:
            raise ValueError("legacy key tag")
        size = (tag & 15) + 1
        octets = (5 * size + 7) // 8 + int(tag >= 0xf0)
        payload = reader.take(octets * 8).to_bytes(octets, "big")
        kind, value = next(position_tokens(bytes([tag]) + payload))
        if kind != "event" or event(*value) != bytes([tag]) + payload:
            raise ValueError("legacy key canonical form")
        return Token(KEY, value)


def put_ranks(bits, ranks):
    for group in blocks(ranks):
        for rank in group:
            bits.put(int(rank != 0), 1, "exception")
        for rank in group:
            if rank:
                bits.put(rank if rank <= 7 else 0, 3, "exception")
                if rank > 7:
                    bits.put(rank, 5, "exception")


def read_ranks(reader, count):
    result = []
    for start in range(0, count, BLOCK):
        present = [reader.take(1) for _ in range(min(BLOCK, count - start))]
        for flag in present:
            rank = reader.take(3) if flag else 0
            if flag and not rank:
                rank = reader.take(5)
                if rank <= 7:
                    raise ValueError("rank escape")
            result.append(rank)
    return result


def recover(items, positions, expected, replay=None):
    keys = {token.value[0] for token in items if token.kind == KEY}
    if keys and (replay is None or len(keys) > MAX_KEYS):
        raise ValueError("replay unavailable or out of budget")
    candidates = replay(keys) if keys else {}
    result = bytearray()
    for token in items:
        if token.kind == ASCII:
            part = bytes([token.value])
        elif token.kind == LITERAL:
            part = token.value
        elif token.kind == KEY:
            key, rank = token.value
            words = candidates.get(key, ())
            if rank >= len(words) or not words[rank]:
                raise ValueError("unknown key candidate")
            part = words[rank].encode()
        else:
            word = positions.get(row_code(token))
            if not word:
                raise ValueError("unknown row")
            part = word.encode()
        if not 1 <= len(part) <= expected - len(result):
            raise ValueError("declared length")
        result.extend(part)
    if len(result) != expected:
        raise ValueError("declared length")
    result.decode("utf-8")
    return bytes(result)
