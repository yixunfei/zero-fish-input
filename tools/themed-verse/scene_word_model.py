"""Reload the frozen public ZWC1 model without the training or sample corpus."""
from hashlib import sha256
from itertools import accumulate
from struct import calcsize, unpack_from

from prose_ngram import CapacityError
from word_codec import WordCodec
from word_context_codec import ContextTables, Successors, TOTAL, WordContextCodec


MODEL_SHA = "be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f"


def load_model(data):
    if sha256(data).hexdigest() != MODEL_SHA:
        raise ValueError("public word model hash")
    offset = 0

    def take(fmt):
        nonlocal offset
        size = calcsize(fmt)
        if offset + size > len(data):
            raise ValueError("truncated public word model")
        values = unpack_from(fmt, data, offset)
        offset += size
        return values

    magic, symbol_count, row_count = take(">4sHH")
    if magic != b"ZWC1" or not 1 < symbol_count < TOTAL:
        raise ValueError("public word model header")
    symbols, counts, codes = [], [], {}
    for _ in range(symbol_count):
        length, width, value, count = take(">BBIH")
        if offset + length > len(data) or not 1 <= width <= 32 or value >= 1 << width or not count:
            raise ValueError("public word model symbol")
        token = data[offset:offset + length]
        offset += length
        token.decode("utf-8")
        symbols.append(token)
        counts.append(count)
        codes[token] = f"{value:0{width}b}"
    if symbols != sorted(set(symbols)) or symbols[0] != b"":
        raise ValueError("public word model alphabet")
    rows = {}
    for _ in range(row_count):
        previous, size = take(">HH")
        previous -= 1
        if previous in rows or not -1 <= previous < symbol_count or not 1 <= size <= symbol_count:
            raise ValueError("public word model row")
        entries = {}
        for _ in range(size):
            symbol, count = take(">HH")
            if symbol >= symbol_count or symbol in entries or not count:
                raise ValueError("public word model transition")
            entries[symbol] = count
        rows[previous] = Successors(entries)
    if offset != len(data):
        raise ValueError("trailing public word model bytes")

    tree = {}
    for token, code in codes.items():
        node = tree
        for bit in code:
            if "token" in node:
                raise ValueError("word model prefix conflict")
            node = node.setdefault(bit, {})
        if node:
            raise ValueError("word model duplicate or prefix")
        node["token"] = token
    nodes = []

    def append_node(node):
        if set(node) == {"token"}:
            nodes.append(node["token"])
        elif set(node) == {"0", "1"}:
            left, right = append_node(node["0"]), append_node(node["1"])
            nodes.append((left, right))
        else:
            raise ValueError("incomplete word model tree")
        return len(nodes) - 1

    append_node(tree)
    word = WordCodec.__new__(WordCodec)
    word.codes, word.nodes = codes, nodes
    word.widths = sorted({len(token) for token in symbols if token})
    tables = ContextTables.__new__(ContextTables)
    tables.symbols = tuple(symbols)
    tables.ids = {token: index for index, token in enumerate(symbols)}
    tables.known = range(1, len(symbols))
    tables.prefix = tuple(accumulate(counts, initial=0))
    tables.global_cdf = tuple(tables.normalize(i, tables.prefix[i], tables.prefix[-1])
                              for i in range(len(tables.prefix)))
    tables.bigram = rows
    return word, {
        6: WordContextCodec(tables, cache_tokens=tables.known),
        7: WordContextCodec(tables, rows, cache_tokens=tables.known),
    }


def encode_body(raw, word, codecs):
    tokens = word.tokenize(raw)
    streams = {5: word.encode_tokens(tokens),
               **{mode: codec.encode(tokens)[0] for mode, codec in codecs.items()}}
    streams = {mode: data for mode, data in streams.items() if len(data) <= 2045}
    if not streams:
        raise CapacityError("body capacity")
    # Same padded-size and mode-order tie break as the current Debug body.
    mode = min(streams, key=lambda i: ((len(streams[i]) + 39 + 63) // 64, i))
    return bytes([mode]) + len(raw).to_bytes(2, "big") + streams[mode]


def decode_body(packet, word, codecs):
    if not 4 <= len(packet) <= 2048:
        raise ValueError("body packet size")
    mode = packet[0]
    size = int.from_bytes(packet[1:3], "big")
    if mode == 5:
        return word.decode(packet[3:], size)
    if mode not in codecs:
        raise ValueError("body mode")
    return codecs[mode].decode(packet[3:], size)
