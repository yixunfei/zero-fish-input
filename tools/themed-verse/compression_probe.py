"""Offline size probe only. No production envelope or credential is used."""
import bisect
import hashlib
import json
import zlib

from verse import ROOT, Resources


def pack(resources, mode, data):
    remaining = int.from_bytes(data, "big")
    span = 1
    target = 1 << (8 * len(data))
    lines = []
    while span < target:
        pool = resources.pool(mode, lines[-1] if lines else "")
        remaining, digit = divmod(remaining, len(pool))
        lines.append(pool[digit])
        span *= len(pool)
    return "".join(line + "。" for line in lines)


def unpack(resources, mode, surface, size):
    target = 1 << (8 * size)
    span, value = 1, 0
    previous = ""
    if not surface.endswith("。"):
        raise ValueError("punctuation")
    for line in surface[:-1].split("。"):
        pool = resources.pool(mode, previous)
        digit = bisect.bisect_left(pool, line)
        if span >= target or digit == len(pool) or pool[digit] != line:
            raise ValueError("invalid line")
        value += digit * span
        span *= len(pool)
        previous = line
    if span < target or value >= target:
        raise ValueError("invalid end")
    return value.to_bytes(size, "big")


def envelope_budget(size):
    return 98 + 64 * ((size + 36 + 63) // 64)


def main():
    tx, rx = Resources(), Resources()
    rows = json.loads((ROOT / "docs/themed-verse/vectors.json").read_text(encoding="utf-8"))
    for row in rows:
        mode, size = row["mode"], row["size"]
        data = bytes.fromhex(row["payload"])
        surface = pack(tx, mode, data)
        if unpack(rx, mode, surface, size) != data:
            raise ValueError("mixed radix round trip")
        print(mode, size, "old/new lines", row["surface"].count("。"), surface.count("。"),
              "zlib(payload)", len(zlib.compress(data, 9)))

    design = (ROOT / "docs/natural-wording-steganography.md").read_text(encoding="utf-8")
    # Consecutive prose paragraphs in document order, excluding Markdown tables/headings.
    prose = "\n".join(line for line in design.splitlines() if line and "\u4e00" <= line[0] <= "\u9fff")
    for cap in (512, 1024, 2048):
        raw = prose.encode()[:cap].decode("utf-8", errors="ignore").encode()
        compact = zlib.compress(raw, 9)
        if zlib.decompress(compact) != raw:
            raise ValueError("zlib round trip")
        print("prose raw/zlib", len(raw), len(compact), "single-layer budget",
              envelope_budget(len(raw)), envelope_budget(len(compact)))
        for length in (envelope_budget(len(raw)), envelope_budget(len(compact))):
            dummy = hashlib.shake_256(f"verse-budget/{length}".encode()).digest(length)
            cover = pack(tx, "linked", dummy)
            if unpack(rx, "linked", cover, length) != dummy:
                raise ValueError("long vector round trip")
            fits = len(cover) <= 8192 and len(cover.encode()) <= 16384
            print("  linked budget, lines, UTF-8 bytes, fits", length, cover.count("。"),
                  len(cover.encode()), fits)


if __name__ == "__main__":
    main()
