"""Bounded byte-preserving prefix code. Empty symbol introduces 1..16 literal bytes."""
from collections import Counter
import heapq
import re

from utilization_probe import public_training_files


def word_counts(texts=None, associations=()):
    chars = Counter()
    phrases = Counter()
    if texts is None:
        texts = (file.read_text(encoding="utf-8") for file in public_training_files())
    for text in texts:
        chars.update(text)
        for word in re.findall(r"[\u4e00-\u9fff]+|[A-Za-z0-9_.-]{2,32}", text):
            if "\u4e00" <= word[0] <= "\u9fff":
                for n in (2, 3, 4):
                    phrases.update(word[i:i+n] for i in range(len(word)-n+1))
            else:
                phrases[word] += 1
    ranked = sorted((w for w in phrases if phrases[w] >= 3),
                    key=lambda w: (-phrases[w] * (len(w.encode()) - 1), w))
    counts = {c.encode(): count for c, count in chars.items()}
    counts.update({w.encode(): phrases[w] for w in ranked[:1536]})
    for phrase in associations:
        symbol = phrase.encode("utf-8")
        counts[symbol] = counts.get(symbol, 0) + 1
    counts[b""] = 32
    return counts


class WordCodec:
    def __init__(self, counts=None):
        if counts is None:
            counts = word_counts()
        nodes = sorted(counts)
        heap = [(counts[symbol], i) for i, symbol in enumerate(nodes)]
        heapq.heapify(heap)
        while len(heap) > 1:
            a, left = heapq.heappop(heap)
            b, right = heapq.heappop(heap)
            heapq.heappush(heap, (a+b, len(nodes)))
            nodes.append((left, right))
        self.codes = {}
        self.nodes = nodes
        pending = [(len(nodes)-1, "")]
        while pending:
            index, prefix = pending.pop()
            node = nodes[index]
            if isinstance(node, bytes):
                self.codes[node] = prefix
            else:
                pending.extend([(node[0], prefix+"0"), (node[1], prefix+"1")])
        self.widths = sorted({len(w) for w in counts if w})

    def tokenize(self, raw):
        if not 1 <= len(raw) <= 2048:
            raise ValueError("original length")
        raw.decode("utf-8")
        # Minimum bits for each remaining suffix. Equal cost prefers a token, then shorter span.
        cost = [0] * (len(raw)+1)
        path = [None] * len(raw)
        esc = self.codes[b""]
        for i in reversed(range(len(raw))):
            options = []
            remaining = len(raw) - i
            for n in self.widths:
                if n > remaining:
                    break
                code = self.codes.get(raw[i:i+n])
                if code is not None:
                    options.append((len(code)+cost[i+n], False, n))
            for n in range(1, min(16, remaining)+1):
                options.append((len(esc)+4+8*n+cost[i+n], True, n))
            cost[i], literal, n = min(options)
            path[i] = literal, n
        tokens = []
        i = 0
        while i < len(raw):
            literal, n = path[i]
            tokens.append((literal, raw[i:i+n]))
            i += n
        return tokens

    def encode(self, raw):
        return self.encode_tokens(self.tokenize(raw))

    def encode_tokens(self, tokens):
        bits = []
        for literal, part in tokens:
            if literal:
                bits.extend((self.codes[b""], f"{len(part)-1:04b}", "".join(f"{b:08b}" for b in part)))
            else:
                bits.append(self.codes[part])
        stream = "".join(bits)
        padded = stream + "0" * (-len(stream) % 8)
        return int(padded, 2).to_bytes(len(padded)//8, "big")

    def decode(self, stream, size):
        if not 1 <= size <= 2048 or not 1 <= len(stream) <= 4096:
            raise ValueError("bounds")
        bits = "".join(f"{b:08b}" for b in stream)
        pos = 0

        def take(n):
            nonlocal pos
            if pos+n > len(bits):
                raise ValueError("truncated code")
            num = int(bits[pos:pos+n], 2)
            pos += n
            return num

        result = bytearray()
        while len(result) < size:
            index = len(self.nodes)-1
            while isinstance(self.nodes[index], tuple):
                index = self.nodes[index][take(1)]
            token = self.nodes[index]
            if not token:
                n = take(4)+1
                token = take(8*n).to_bytes(n, "big")
            if len(token) > size-len(result):
                raise ValueError("declared length")
            result.extend(token)
        if len(bits)-pos >= 8 or "1" in bits[pos:]:
            raise ValueError("invalid tail")
        result.decode("utf-8")
        return bytes(result)
