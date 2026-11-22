"""Host-only lossless wording body using the frozen Rime Essay and ZWC1 frequencies."""
from collections import Counter
from copy import copy
from hashlib import sha256

from binary_prototype import bounded_file
from p2_bits import Bits, Reader
from prose_window_slot_probe import NORMALIZATION, OPENCC
from verse import ROOT
from word_codec import WordCodec


ESSAY = ROOT / "engine-rime/src/main/assets/rime/essay.txt"
ESSAY_SHA = "196d508a9bb12fc6d711eed83f5ddc4d5c2d382d92d15b4e51175e4d3802cfab"
MODE = 8
SIMPLIFIED_MODE = 9
COPY = b"\xff"  # Cannot collide with any substring of valid UTF-8.


def build_essay(tables, path=ESSAY):
    data = bounded_file(path, 5887784)
    if sha256(data).hexdigest() != ESSAY_SHA:
        raise ValueError("Rime Essay source hash")
    counts = Counter()
    for row in data.splitlines():
        token, weight = row.rsplit(b"\t", 1)
        count = int(weight)
        if count > 0 and 1 <= len(token) <= 18:
            counts[token] = count
    scale = sum(counts.values()) // tables.prefix[-1]
    for index, token in enumerate(tables.symbols):
        count = tables.prefix[index + 1] - tables.prefix[index]
        if count > 1 or not token:
            counts[token] += scale * count
    counts[COPY] = sum(counts.values()) // 128
    return EssayBody(WordCodec(counts))


def build_models(tables):
    original = build_essay(tables)
    data = bounded_file(OPENCC / "TSCharacters.txt", 100_000).replace(b"\r\n", b"\n")
    if sha256(data).hexdigest() != NORMALIZATION["TSCharacters.txt"]:
        raise ValueError("essay character table hash")
    translation = {}
    for row in data.decode("utf-8").splitlines():
        char, replacements = row.split("\t")
        translation[ord(char)] = replacements.split()[0]
    # Keep the wire tree. Multiple leaves may render the same simplified token.
    projected = copy(original.words)
    projected.codes = {}
    for token, code in original.words.codes.items():
        surface = project(token, translation)
        old = projected.codes.get(surface)
        if old is None or (len(code), code) < (len(old), old):
            projected.codes[surface] = code
    projected.widths = sorted({len(token) for token in projected.codes if token})
    return {MODE: original, SIMPLIFIED_MODE: EssayBody(projected, translation)}


def project(token, translation):
    if not translation or token in (b"", COPY):
        return token
    return token.decode("utf-8").translate(translation).encode("utf-8")


def put_code(bits, code):
    bits.put(int(code, 2), len(code), "type")


class EssayBody:
    def __init__(self, words, translation=None):
        self.words = words
        self.translation = translation

    def encode(self, raw):
        """Price copies on token boundaries; their source may overlap the current output."""
        tokens = self.words.tokenize(raw)
        codes = self.words.codes
        offsets = [0]
        for _, part in tokens:
            offsets.append(offsets[-1] + len(part))
        costs = [0] * len(offsets)
        path = [None] * len(tokens)
        plain_bits = 0
        for i in reversed(range(len(tokens))):
            literal, part = tokens[i]
            price = len(codes[b""]) + 4 + 8 * len(part) if literal else len(codes[part])
            plain_bits += price
            costs[i], path[i] = price + costs[i + 1], (i + 1, 0)
            start = offsets[i]
            if start == 0:
                continue
            copy_price = len(codes[COPY]) + 6 + (start - 1).bit_length()
            for end in range(i + 1, len(offsets)):
                stop = offsets[end]
                if stop - start > 66:
                    break
                if stop - start < 3:
                    continue
                # stop - 1 admits overlapping matches, but never this occurrence.
                previous = raw.rfind(raw[start:stop], 0, stop - 1)
                if previous < 0:
                    break
                if copy_price + costs[end] < costs[i]:
                    costs[i], path[i] = copy_price + costs[end], (end, start - previous)

        writer = Bits()
        i = copies = 0
        while i < len(tokens):
            end, distance = path[i]
            literal, part = tokens[i]
            if distance:
                put_code(writer, codes[COPY])
                writer.put(offsets[end] - offsets[i] - 3, 6, "boundary")
                writer.put(distance - 1, (offsets[i] - 1).bit_length(), "payload")
                copies += 1
            elif literal:
                put_code(writer, codes[b""])
                writer.put(len(part) - 1, 4, "boundary")
                writer.put(int.from_bytes(part, "big"), 8 * len(part), "payload")
            else:
                put_code(writer, codes[part])
            i = end
        stream, _ = writer.finish()
        return stream, {"lexical_only_bytes": (plain_bits + 7) // 8, "copy_count": copies}

    def decode(self, stream, size):
        if not 1 <= size <= 2048 or not 1 <= len(stream) <= 2045:
            raise ValueError("essay body bounds")
        bits, out = Reader(stream), bytearray()
        nodes = self.words.nodes
        while len(out) < size:
            index = len(nodes) - 1
            while isinstance(nodes[index], tuple):
                index = nodes[index][bits.take(1)]
            token = nodes[index]
            if token == COPY:
                if not out:
                    raise ValueError("copy lacks a prefix")
                length = bits.take(6) + 3
                distance = bits.take((len(out) - 1).bit_length()) + 1
                if distance > len(out) or length > size - len(out):
                    raise ValueError("copy outside body")
                for _ in range(length):
                    out.append(out[-distance])
            else:
                if not token:
                    length = bits.take(4) + 1
                    token = bits.take(8 * length).to_bytes(length, "big")
                else:
                    token = project(token, self.translation)
                if len(token) > size - len(out):
                    raise ValueError("essay token exceeds original length")
                out.extend(token)
        bits.finish()
        out.decode("utf-8")
        return bytes(out)
