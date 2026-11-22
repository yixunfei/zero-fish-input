"""Public corpus experiment: row locations and optional packed native key/rank events."""
import hashlib
import heapq
import io
import json
import re

from verse import LOCK, TABLE


HAN = re.compile(r"[\u4e00-\u9fff]+")
PINYIN = re.compile(r"[a-z]+(?: [a-z]+)*")


def runs(data):
    return HAN.findall(data.decode("utf-8"))


def wanted_words(messages):
    return {run[i:i+n] for data in messages for run in runs(data) for i in range(len(run))
            for n in range(1, min(64, len(run)-i)+1)}


def dictionary_rows(spec, directory=TABLE.parent):
    for name in spec["tables"]:
        path = directory / (name+".dict.yaml")
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != spec["preparedFiles"]["dicts/"+path.name]:
            raise ValueError("unexpected dictionary")
        with io.TextIOWrapper(io.BytesIO(raw), encoding="utf-8") as f:
            if not any(line.rstrip("\r\n") == "..." for line in f):
                raise ValueError("header")
            for line in f:
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) < 2:
                    continue
                word, spelling = fields[:2]
                weight = int(fields[2]) if len(fields) > 2 and fields[2].isdigit() else 0
                yield name, word, spelling, weight


class ShortLocations:
    def __init__(self):
        self.singles = {}
        self.frequent = []

    def add(self, name, word, weight, row_id):
        if name == "zi" and len(word) == 1:
            previous, first = self.singles.get(word, (weight, row_id))
            self.singles[word] = max(weight, previous), first
        if name == "jichu":
            entry = (weight, word, row_id)
            if len(self.frequent) < 4096:
                heapq.heappush(self.frequent, entry)
            elif entry > self.frequent[0]:
                heapq.heapreplace(self.frequent, entry)

    def ranked(self):
        top = heapq.nlargest(4096, self.singles, key=lambda w: (self.singles[w][0], w))
        return [(word, self.singles[word][1]) for word in top] + [
            (word, row_id) for _, word, row_id in sorted(self.frequent, reverse=True)
        ]


def locations(wanted, requested=frozenset()):
    """Sparse materialization only. Row IDs and frequent IDs depend on the full tables."""
    spec = json.loads(LOCK.read_bytes())
    direct, reading, reverse = {}, {}, {}
    short = ShortLocations()
    row_id = 0
    for name, word, spelling, weight in dictionary_rows(spec):
        if row_id in requested:
            reverse[(0x800000+row_id).to_bytes(3, "big")] = word
        if word in wanted:
            direct.setdefault(word, (0x800000+row_id).to_bytes(3, "big"))
            if PINYIN.fullmatch(spelling) and (weight, spelling) > reading.get(word, (-1, "")):
                reading[word] = weight, spelling
        short.add(name, word, weight, row_id)
        row_id += 1
    if row_id > 1 << 22:
        raise ValueError("row location width")
    for i, (word, _) in enumerate(short.ranked()):
        code = (0xc000+i).to_bytes(2, "big")
        if -1-i in requested:
            reverse[code] = word
        if word in wanted:
            direct[word] = min(direct[word], code, key=lambda b: (len(b), b))
    return direct, {w: p for w, (_, p) in reading.items()}, row_id, reverse


def queries(messages, reading):
    forms = set()
    for word in wanted_words(messages):
        syllables = reading.get(word)
        if syllables:
            pieces = syllables.split()
            forms.update(("".join(pieces), "".join(s[0] for s in pieces)))
    # Try complete Han clauses too, even when no exact phrase entry exists.
    for data in messages:
        for run in runs(data):
            if all(c in reading for c in run):
                parts = [reading[c] for c in run]
                forms.update(("".join(parts), "".join(s[0] for s in parts)))
    return {q for q in forms if len(q) <= 64}


def event(keys, rank):
    n = len(keys)
    if not 1 <= n <= 64 or not 0 <= rank < 32:
        raise ValueError("invalid event")
    count = (5*n+7)//8
    value = 0
    for c in keys:
        value = value*32 + ord(c)-97
    # Low-order canonical zero padding to the next byte.
    body = (value << (count*8-5*n)).to_bytes(count, "big")
    if rank == 0 and n <= 16:
        return bytes([0xe0+n-1])+body
    if n <= 15:
        return bytes([0xf0+n-1])+body+bytes([rank])
    return bytes([0xff, 64+n-1])+body+bytes([rank])


def event_terms(cache, wanted):
    table = {}
    for keys, candidates in cache.items():
        for rank, word in enumerate(candidates):
            if word in wanted:
                code = event(keys, rank)
                if word not in table or (len(code), code) < (len(table[word]), table[word]):
                    table[word] = code
    return table


def encode(raw, terms):
    text = raw.decode("utf-8")
    if not 1 <= len(raw) <= 2048:
        raise ValueError("original size")
    widths = sorted({len(w) for w in terms})
    costs = [0] * (len(text)+1)
    path = [None] * len(text)
    for i in reversed(range(len(text))):
        options = []
        if ord(text[i]) < 128:
            options.append((1+costs[i+1], 1, text[i].encode()))
        for n in widths:
            if i+n > len(text):
                break
            code = terms.get(text[i:i+n])
            if code is not None:
                options.append((len(code)+costs[i+n], n, code))
        literal = bytearray()
        for j in range(i, min(len(text), i+64)):
            literal.extend(text[j].encode())
            if len(literal) > 64:
                break
            options.append((2+len(literal)+costs[j+1], j+1-i,
                            bytes([255, len(literal)-1])+literal))
        costs[i], n, code = min(options)
        path[i] = n, code
    i, out = 0, bytearray()
    while i < len(text):
        n, code = path[i]
        out.extend(code)
        i += n
    return bytes(out)


def tokens(stream):
    pos = 0

    def take(n):
        nonlocal pos
        if pos+n > len(stream):
            raise ValueError("truncated location stream")
        block = stream[pos:pos+n]
        pos += n
        return block

    if not 1 <= len(stream) <= 6144:
        raise ValueError("stream bounds")
    while pos < len(stream):
        tag = take(1)[0]
        if tag < 128:
            yield "literal", bytes([tag])
        elif tag < 224:
            key = bytes([tag])+take(2 if tag < 192 else 1)
            yield "row", key
        else:
            sub = take(1)[0] if tag == 255 else None
            if sub is not None and sub < 64:
                yield "literal", take(sub+1)
            else:
                n = (sub-63) if sub is not None else 1+(tag & 15)
                if not 1 <= n <= 64:
                    raise ValueError("spelling length")
                count = (n*5+7)//8
                packed = int.from_bytes(take(count), "big")
                value = packed >> (count*8-n*5)
                keys = "".join(chr(97+((value >> (5*j)) & 31)) for j in reversed(range(n)))
                rank = take(1)[0] if tag >= 240 else 0
                if not keys.isascii() or not keys.isalpha() or rank >= 32 or packed != value << (count*8-n*5):
                    raise ValueError("invalid key sequence")
                yield "event", (keys, rank)


def decode(stream, reverse, cache, expected):
    if not 1 <= expected <= 2048:
        raise ValueError("original length")
    out = bytearray()
    for kind, token in tokens(stream):
        if kind == "row":
            word = reverse.get(token)
            if word is None:
                raise ValueError("unknown row")
            part = word.encode()
        elif kind == "event":
            keys, rank = token
            candidates = cache.get(keys, ())
            if rank >= len(candidates) or not candidates[rank]:
                raise ValueError("unknown candidate")
            part = candidates[rank].encode()
        else:
            part = token
        if len(out)+len(part) > expected:
            raise ValueError("declared length")
        out.extend(part)
    if len(out) != expected:
        raise ValueError("declared length")
    out.decode("utf-8")
    return bytes(out)
