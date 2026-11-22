"""Host verse rank with one ordered pair per eight lines and free lines elsewhere."""
from bisect import bisect_left, bisect_right
from hashlib import sha256
import json
import re

from binary_prototype import bounded_file
from prose_ngram import CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, CapacityError
from prose_window_slot_probe import normalizer
from verse_scene_probe import SceneVerse, banks, fixed_bound, layout, parse, unpack, visible_bytes


TANG_SHA = "8ccf0783888b15bec60d0253e278cd4b67e94a77725df8ed079d4d8062d050b4"
TANG_REV = "b8594f81a89752241442f2ce267d6f66f96704ee"
PERIOD = 8
HAN_PAIR = re.compile(r"([一-鿿]{5}|[一-鿿]{7})，([一-鿿]{5}|[一-鿿]{7})。")


def attributed_pairs(source):
    raw = bounded_file(source, 477671)
    if sha256(raw).hexdigest() != TANG_SHA:
        raise ValueError("Tang source hash")
    normalize = normalizer()
    works = {5: {}, 7: {}}
    for poem in json.loads(raw):
        if not all(poem.get(key) for key in ("author", "title", "id")):
            continue
        for i, paragraph in enumerate(poem["paragraphs"]):
            match = HAN_PAIR.fullmatch(paragraph)
            if not match:
                continue
            pair = tuple(normalize(line) for line in match.groups())
            width = len(pair[0])
            if width not in works or len(pair[1]) != width or pair[0] == pair[1]:
                continue
            if not all("一" <= c <= "鿿" for line in pair for c in line):
                continue
            works[width].setdefault(pair, []).append((poem["id"], poem["author"], poem["title"], i + 1))
    return works


def index_of(ordered, value):
    index = bisect_left(ordered, value)
    if index == len(ordered) or ordered[index] != value:
        raise ValueError("verse choice outside the schedule")
    return index


class SparseVerse:
    def __init__(self, width, lines, *, scenes=None, ordered=()):
        self.width = width
        self.lines = lines
        self.scenes = scenes
        self.ordered = ordered
        self.pair_count = scenes.offsets[2][-1] if scenes else len(ordered)
        # Each bucket ends at a complete choice. A required pair is indivisible.
        self.counts = [0]
        self.ends = [0]
        self.radices = []
        span = 1
        while True:
            position = self.counts[-1]
            paired = self.pair_count > 0 and position % PERIOD == 0
            count = position + (2 if paired else 1)
            units = count * (width + 1) + (count - 1) // 4
            if visible_bytes(width, count) > CARRIER_BYTES or units > CARRIER_UNITS:
                break
            radix = self.pair_count if paired else len(lines)
            span *= radix
            self.radices.append(radix)
            self.counts.append(count)
            self.ends.append(self.ends[-1] + span)
        self.guaranteed = min(MAX_ENVELOPE_BYTES, fixed_bound(self.ends[-1]))

    def worst(self, size):
        step = bisect_right(self.ends, (1 << (8 * size + 1)) - 2)
        return visible_bytes(self.width, self.counts[step]) if step < len(self.ends) else None

    def guaranteed_in(self, byte_budget):
        if byte_budget < 0:
            raise ValueError("negative carrier budget")
        step = bisect_right(self.counts, byte_budget, key=lambda n: visible_bytes(self.width, n)) - 1
        return max(0, min(MAX_ENVELOPE_BYTES, fixed_bound(self.ends[step])))

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("verse envelope length")
        rank = int.from_bytes(b"\x01" + packet, "big") - 1
        stop = bisect_right(self.ends, rank)
        if stop == len(self.ends):
            raise CapacityError("sparse verse exceeds the carrier limit")
        rank -= self.ends[stop - 1]
        lines = []
        for step in range(stop):
            rank, digit = divmod(rank, self.radices[step])
            if self.counts[step + 1] - self.counts[step] == 1:
                lines.append(self.lines[digit])
            elif self.scenes:
                lines.extend(self.scenes.stanza_lines(2, digit))
            else:
                lines.extend(self.ordered[digit])
        return layout(lines)

    def recover(self, surface):
        lines = parse(surface, self.width)
        stop = index_of(self.counts, len(lines))
        # Reverse Horner evaluation uses only the observed choices and public schedule.
        value = 0
        for step in reversed(range(stop)):
            start, end = self.counts[step:step + 2]
            if end - start == 1:
                digit = index_of(self.lines, lines[start])
            elif self.scenes:
                digit = self.scenes.stanza_rank(lines[start:end])
            else:
                digit = index_of(self.ordered, tuple(lines[start:end]))
            value = value * self.radices[step] + digit
        return unpack(self.ends[stop - 1] + value + 1)

    def profile_parts(self):
        yield "sparse-verse/length-first/v1"
        yield f"period={PERIOD}/width={self.width}/single-lines={len(self.lines)}"
        yield from self.lines
        if self.scenes:
            for row in self.scenes.rows:
                yield str(len(row))
                yield from row
        if self.ordered:
            yield bytes.fromhex(TANG_SHA)
            for left, right in self.ordered:
                yield left + right


def sparse_routes(resources, lexicon, works):
    routes = {}
    for width, lines in ((5, resources.free), (7, resources.seven)):
        scenes, _, _ = banks(lines, lexicon)
        routes[f"{width}-free"] = SparseVerse(width, lines)
        routes[f"{width}-sparse-scene"] = SparseVerse(width, lines, scenes=SceneVerse(width, scenes))
        routes[f"{width}-sparse-couplet"] = SparseVerse(width, lines, ordered=sorted(works[width]))
    return routes
