"""Rebuild public vectors, run negative checks, and prepare blinded reading packets."""
import argparse
import hashlib
import json
import random
import time
from pathlib import Path

from verse import MODES, TABLE, Resources, decode, encode, width


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write(folder, filename, content):
    (folder / filename).write_bytes(content.encode("utf-8"))


def payload(size):
    return hashlib.shake_256(f"ZeroInput/public-verse/v1/{size}".encode()).digest(size)


def rejects(reason, function, *args):
    try:
        function(*args)
    except ValueError as error:
        require(str(error) == reason, f"unexpected failure: {error}")
    else:
        raise RuntimeError(f"expected {reason}")


class FixedPool:
    def __init__(self, words):
        self.words = words

    def pool(self, mode, previous):
        return self.words


def baseline(tx, rx):
    for glyph, expected, lines in (("月", 3942, 118), ("山", 6404, 108)):
        a, b = [FixedPool([w for w in r.free if glyph in w]) for r in (tx, rx)]
        require(len(a.words) == expected, glyph)
        surface = encode(a, "", payload(162))
        require(len(surface) == lines * 6 and decode(b, "", surface, 162) == payload(162), glyph)
    require((len(tx.free), len(tx.seven)) == (213300, 92141), "baseline pools")
    for size, five, seven in ((162, 77, 81), (226, 107, 113), (290, 137, 145)):
        for name, count in (("free", five), ("seven", seven)):
            surface = encode(FixedPool(getattr(tx, name)), "", payload(size))
            require(surface.count("。") == count, "baseline length")
            require(decode(FixedPool(getattr(rx, name)), "", surface, size) == payload(size), name)


def negatives(tx, rx):
    data = payload(162)
    surface = encode(tx, "free", data)
    rejects("truncated", decode, rx, "free", surface[:-6], 162)
    rejects("punctuation", decode, rx, "free", surface[:-1] + "！", 162)
    rejects("outside codebook", decode, rx, "free", "〇" + surface[1:], 162)
    rejects("extra line", decode, rx, "free", surface + surface[:6], 162)
    rejects("outside codebook", decode, rx, "theme", surface, 162)
    rejects("unknown configuration or context", decode, rx, "missing", surface, 162)
    last = rx.free.index(surface[-6:-1])
    rejects("nonzero padding", decode, rx, "free", surface[:-6] + rx.free[last | 1] + "。", 162)
    for size in (0, 1):
        small = FixedPool(rx.free[:size])
        rejects("insufficient candidates", encode, small, "", data)
        rejects("insufficient candidates", decode, small, "", surface, 162)
    # Change just one Han character while keeping an assigned index and valid padding.
    idx = next(i for i in range((1 << width(tx.free)) - 1)
               if tx.free[i][:4] == tx.free[i + 1][:4])
    fixture = (idx << 7).to_bytes(3, "big")
    valid = encode(tx, "free", fixture)
    altered = tx.free[idx + 1] + valid[5:]
    require(decode(rx, "free", altered, 3) != fixture, "valid substitution")
    # Both configurations start with exactly the same pool; a wrong ID can be invisible.
    short = encode(tx, "theme", b"A")
    require(decode(rx, "linked", short, 1) == b"A", "overlapping configuration")


def ordinary(resources, mode, count, rng):
    lines = []
    while len(lines) < count:
        pool = resources.pool(mode, lines[-1] if lines else "")
        lines.append(pool[rng.randrange(1 << width(pool))])
    return "".join(w + "。" for w in lines)


def packet(folder, name, items, rng):
    rng.shuffle(items)
    samples, sheet, key = [], [], []
    for i, (label, surface) in enumerate(items, 1):
        code = f"{name}-{i:02d}"
        samples.append(f"## {code}\n\n{surface}")
        sheet.append(f"| {code} | | | | | |")
        key.append(f"{code}\t{label}")
    write(folder, name + ".md", "\n\n".join(samples) + "\n")
    write(folder, name + "-scores.md",
          "匿名读者编号 ______　请先独立通读并锁定评分，再交回主持人。\n\n"
          "三项各按 1–5 分，1 表示难懂、跳跃或松散，3 表示部分成立，5 表示通顺、相承或集中。"
          "可注明最突兀的句对。猜测来源填携密、普通、人作或不确定。\n\n"
          "| 编号 | 单句通顺 | 上下句衔接 | 全篇主题一致 | 猜测来源 | 依据 |\n"
          "|---|---|---|---|---|---|\n" + "\n".join(sheet) + "\n")
    write(folder, name + "-organizer.txt", "\n".join(key) + "\n")


def run(folder):
    folder.mkdir(exist_ok=True, parents=True)
    start = time.perf_counter()
    tx = Resources()
    records = [(m, s, encode(tx, m, payload(s))) for m in MODES for s in (162, 226, 290)]
    before = time.perf_counter()
    rx = Resources()
    print("second dictionary load seconds", round(time.perf_counter() - before, 3))
    baseline(tx, rx)
    negatives(tx, rx)
    print("theme, sky/water/hills candidates", len(tx.theme), [len(g) for g in tx.groups])
    full, short, vectors = [], [], []
    for mode, size, surface in records:
        original = payload(size)
        recovered = decode(rx, mode, surface, size)
        require(recovered == original, "round trip")
        lines = surface[:-1].split("。")
        units, octets = len(surface.encode("utf-16-le")) // 2, len(surface.encode())
        print(mode, size, len(lines), units, octets, len(lines) - len(set(lines)),
              units <= 512 and octets <= 1536, units <= 8192 and octets <= 16384)
        vectors.append(dict(mode=mode, size=size, payload=original.hex(), surface=surface))
        control = ordinary(tx, mode, len(lines), random.Random(f"ordinary-v1/{mode}/{size}"))
        for label, body in (("payload", surface), ("ordinary", control)):
            item = f"{mode} {size} {label}"
            full.append((item, body))
            short.append((item + " first four", body[:24]))
    short.extend([
        ("Wen Tianxiang 164", "吴楚东南坼。风云地一隅。蹉跎暮容色。不敢恨危途。"),
        ("Wen Tianxiang 167", "阴风千里来。惊浪满吴楚。世事两茫茫。飘泊欲谁诉。"),
        ("Wen Tianxiang 180", "天寒霜雪繁。萧萧北风劲。高鸟黄云暮。斗上捩孤影。"),
    ])
    packet(folder, "short", short, random.Random(20261120))
    packet(folder, "full", full, random.Random(20261121))
    write(folder, "vectors.json", json.dumps(vectors, ensure_ascii=False, indent=2) + "\n")
    print("PASS baseline, negative checks, 9 round trips; dictionary bytes", TABLE.stat().st_size,
          "elapsed seconds", round(time.perf_counter() - start, 3))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    run(parser.parse_args().output)
