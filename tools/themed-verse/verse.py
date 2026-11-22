"""Offline host index experiment. Each Resources instance owns its own tables."""
import bisect
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / "engine-rime/build/generated/publicDictionaryAssets/rime/dicts/shici.dict.yaml"
LOCK = ROOT / "tools/dictionaries/sources.lock.json"
MODES = ("free", "theme", "linked")
# Night landscape: look up at the sky, down to the water, across to the hills.
IMAGES = ("月星夜霞云", "江水溪泉波", "山峰岭松林")


def linked_pool(previous, theme, groups):
    if not previous:
        return theme
    for char in reversed(previous):
        for i, image in enumerate(IMAGES):
            if char in image:
                return groups[(i + 1) % len(IMAGES)]
    raise ValueError("unknown context")


class Resources:
    def __init__(self):
        raw = TABLE.read_bytes()
        locked = json.loads(LOCK.read_text(encoding="utf-8"))["preparedFiles"]
        if hashlib.sha256(raw).hexdigest() != locked["dicts/shici.dict.yaml"]:
            raise ValueError("dictionary hash")
        rows = iter(raw.decode("utf-8").splitlines())
        if not any(row == "..." for row in rows):
            raise ValueError("missing data section")
        words = set()
        for row in rows:
            word, tab, _ = row.partition("\t")
            if tab and len(word) in (5, 7) and all("一" <= c <= "\u9fff" for c in word):
                words.add(word)
        five = sorted(w for w in words if len(w) == 5)
        self.seven = sorted(w for w in words if len(w) == 7)
        self.groups = [[w for w in five if any(c in image for c in w)] for image in IMAGES]
        self.theme = sorted(set().union(*self.groups))
        self.free = five

    def pool(self, mode, previous):
        if mode == "free":
            return self.free
        if mode == "theme":
            return self.theme
        if mode == "linked":
            return linked_pool(previous, self.theme, self.groups)
        raise ValueError("unknown configuration or context")


def width(pool):
    if len(pool) < 2:
        raise ValueError("insufficient candidates")
    return len(pool).bit_length() - 1


def encode(resources, mode, data):
    bits = "".join(f"{b:08b}" for b in data)
    lines = []
    offset = 0
    while offset < len(bits):
        pool = resources.pool(mode, lines[-1] if lines else "")
        n = width(pool)
        index = int(bits[offset:offset + n].ljust(n, "0"), 2)
        lines.append(pool[index])
        offset += n
    return "".join(w + "。" for w in lines)


def decode(resources, mode, surface, size):
    if not 1 <= size <= 2372 or len(surface) > 8192 or len(surface.encode()) > 16384:
        raise ValueError("bounds")
    if not surface.endswith("。"):
        raise ValueError("punctuation")
    previous = ""
    value = count = 0
    for line in surface[:-1].split("。"):
        if count >= size * 8:
            raise ValueError("extra line")
        pool = resources.pool(mode, previous)
        n = width(pool)
        index = bisect.bisect_left(pool, line)
        if index >= 1 << n or pool[index] != line:
            raise ValueError("outside codebook")
        value = (value << n) | index
        count += n
        previous = line
    padding = count - size * 8
    if padding < 0:
        raise ValueError("truncated")
    if value & ((1 << padding) - 1):
        raise ValueError("nonzero padding")
    return (value >> padding).to_bytes(size, "big")
