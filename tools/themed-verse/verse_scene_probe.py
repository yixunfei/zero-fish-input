"""Measure exact stanza ranking with a retained scene or a shared literal image."""

import argparse
from bisect import bisect_left, bisect_right
from collections import defaultdict
from math import perm
from pathlib import Path
from random import Random
import re
from statistics import median

from prepare_android_verse import OPENING_SHA, SEVEN_SHA, verified
from prose_capacity_probe import calibration_envelopes, report_surface_calibration
from prose_corpus_coverage_probe import evidence, matched_terms, read_source
from prose_grammar_probe import expect_rejection
from prose_ngram import CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, CapacityError
from prose_window_slot_probe import HOLDOUT_SEED, SEEDS
from p2_theme_pools import build_pools, last_image
from surface_lexicon_probe import locked_faces
from verse import Resources


SCENES = {
    "moonlit": "明月 月明 月色 月影 月光 秋月 夜月 月夜 月华 新月 残月 星河 银河 北斗",
    "river": "江上 江中 江边 江岸 江畔 江水 江流 江南 江潮 长江 岸边 两岸 流水",
    "boat": "扁舟 孤舟 渔舟 渔船 客船 帆影 帆船 孤帆 归帆 归舟 渡口",
    "mountain": "青山 山色 山头 山间 山中 山前 山路 远山 高山 山谷 峰顶 空山",
    "woods": "松林 松风 竹林 竹影 林间 林中 幽林 松间 树影 高树 林下",
    "garden": "花开 梅花 桃花 梨花 菊花 残花 落花 花枝 芳草 柳枝 柳色 柳絮 垂柳",
    "rain": "夜雨 春雨 秋雨 雨声 雨后 微雨 细雨 雨来 风雨 雨打 雨歇",
    "snow": "白雪 残雪 雪中 积雪 飞雪 风雪 落雪 冰雪 大雪",
    "dwelling": "柴门 茅屋 田家 村舍 草堂 村落 窗前 窗外 灯前 灯下 灯火",
    "road": "故乡 家乡 归路 归途 归家 行路 征途 行人 客舍 客路 驿亭",
}


def visible_bytes(width, count):
    return count * 3 * (width + 1) + max(0, count - 1) // 4


def layout(lines):
    return "".join(line + ("，" if index % 2 == 1 and index < len(lines) else "。") +
                   ("\n" if index % 4 == 0 and index < len(lines) else "")
                   for index, line in enumerate(lines, 1))


def parse(surface, width):
    if not 0 < len(surface) <= CARRIER_UNITS or not surface.endswith("。"):
        raise ValueError("invalid poem surface length or terminal punctuation")
    lines = surface[:-1].replace("\n", "").replace("，", "。").split("。")
    if (any(len(line) != width for line in lines) or visible_bytes(width, len(lines)) > CARRIER_BYTES
            or layout(lines) != surface):
        raise ValueError("noncanonical poem layout")
    return lines


def unpack(value):
    size = (value.bit_length() - 1) // 8
    if not 1 <= size <= MAX_ENVELOPE_BYTES or value >> (size * 8) != 1:
        raise ValueError("invalid poem frame")
    return value.to_bytes(size + 1, "big")[1:]


def fixed_bound(total):
    return ((total + 1).bit_length() - 2) // 8


class FrozenVerse:
    """Mirror Android's wrapped least-significant-first surface, excluding AEAD."""

    def __init__(self, width, resources):
        self.width = width
        _, _, pools = build_pools(resources)
        self.opening = pools.theme
        verified("".join(self.opening).encode("utf-16-be"), {5: OPENING_SHA, 7: SEVEN_SHA}[width])
        self.pools = pools
        self.max_lines = (CARRIER_BYTES * 4 + 4) // (12 * (width + 1) + 1)
        self.min_radix = min(map(len, pools.groups))
        self.guaranteed = fixed_bound(len(self.opening) * self.min_radix ** (self.max_lines - 1) - 1)

    def worst(self, size):
        span = len(self.opening)
        count = 1
        while count <= self.max_lines and span <= (1 << (8 * size + 1)) - 1:
            count += 1
            span *= self.min_radix
        return visible_bytes(self.width, count) if count <= self.max_lines else None

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid poem frame length")
        value = int.from_bytes(b"\x01" + packet, "big")
        lines = []
        while value:
            if len(lines) >= self.max_lines:
                raise CapacityError("poem exceeds the carrier limit")
            pool = self.pools.pool("linked", lines[-1] if lines else None)
            value, index = divmod(value, len(pool))
            lines.append(pool[index])
        return layout(lines)

    def recover(self, surface):
        lines = parse(surface, self.width)
        value, span, previous = 0, 1, None
        for line in lines:
            pool = self.pools.pool("linked", previous)
            digit = bisect_left(pool, line)
            if digit == len(pool) or pool[digit] != line:
                raise ValueError("poem line is outside the active pool")
            before = span
            value += digit * span
            span *= len(pool)
            previous = line
        if value < before:
            raise ValueError("poem has an extra terminal line")
        return unpack(value)


class SceneVerse:
    """Rank nonrepeating lines from one bank per four-line stanza by visible length."""

    def __init__(self, width, rows):
        self.width = width
        self.rows = tuple(tuple(lines) for _, lines in sorted(rows.items()) if len(lines) >= 4)
        self.where = {line: (group, index) for group, row in enumerate(self.rows) for index, line in enumerate(row)}
        if not self.rows or len(self.where) != sum(map(len, self.rows)):
            raise ValueError("poem banks are empty or overlap")
        self.offsets = [None]
        for count in range(1, 5):
            offsets = [0]
            for row in self.rows:
                offsets.append(offsets[-1] + perm(len(row), count))
            self.offsets.append(offsets)
        self.cumulative = [0]
        span = 1
        for count in range(1, (CARRIER_BYTES * 4 + 4) // (12 * (width + 1) + 1) + 1):
            block_size = (count - 1) % 4 + 1
            space = self.offsets[block_size][-1]
            self.cumulative.append(self.cumulative[-1] + span * space)
            if block_size == 4:
                span *= space
        self.guaranteed = fixed_bound(self.cumulative[-1])

    def worst(self, size):
        count = bisect_right(self.cumulative, (1 << (8 * size + 1)) - 2)
        return visible_bytes(self.width, count) if count < len(self.cumulative) else None

    def render(self, packet):
        rank = int.from_bytes(b"\x01" + packet, "big") - 1
        count = bisect_right(self.cumulative, rank)
        if not packet or len(packet) > MAX_ENVELOPE_BYTES or count >= len(self.cumulative):
            raise CapacityError("poem rank exceeds the carrier limit")
        rank -= self.cumulative[count - 1]
        lines = []
        for start in range(0, count, 4):
            size = min(4, count - start)
            offsets = self.offsets[size]
            rank, digit = divmod(rank, offsets[-1])
            lines.extend(self.stanza_lines(size, digit))
        return layout(lines)

    def stanza_lines(self, size, digit):
        offsets = self.offsets[size]
        group = bisect_right(offsets, digit) - 1
        value = digit - offsets[group]
        row = self.rows[group]
        taken, lines = [], []
        for position in range(size):
            value, index = divmod(value, len(row) - position)
            for previous in taken:
                if previous <= index:
                    index += 1
            taken.insert(bisect_left(taken, index), index)
            lines.append(row[index])
        return lines

    def stanza_rank(self, stanza):
        tags = [self.where.get(line) for line in stanza]
        if None in tags or len(set(tags)) != len(tags) or len({group for group, _ in tags}) != 1:
            raise ValueError("poem stanza mixes banks, repeats or uses an unknown line")
        group = tags[0][0]
        seen, value, base = [], 0, 1
        for _, index in tags:
            value += (index - sum(previous < index for previous in seen)) * base
            base *= len(self.rows[group]) - len(seen)
            seen.append(index)
        return self.offsets[len(stanza)][group] + value

    def recover(self, surface):
        lines = parse(surface, self.width)
        rank, span = self.cumulative[len(lines) - 1], 1
        for start in range(0, len(lines), 4):
            stanza = lines[start:start + 4]
            rank += span * self.stanza_rank(stanza)
            span *= self.offsets[len(stanza)][-1]
        return unpack(rank + 1)


def banks(lines, lexicon):
    phrases = {term: scene for scene, words in SCENES.items() for term in words.split() if term in lexicon}
    pattern = re.compile("|".join(sorted(phrases, key=lambda term: (-len(term), term))))
    scenes, anchors = defaultdict(list), defaultdict(list)
    for line in lines:
        found = tuple(pattern.findall(line))
        if not found or len({phrases[term] for term in found}) != 1:
            continue
        scenes[phrases[found[0]]].append(line)
        anchors[found[0]].append(line)
    return scenes, anchors, pattern


def verse_parts(lines):
    return tuple("，".join(lines[index:index + 2]) for index in range(0, len(lines), 2))


def controls(route):
    expect_rejection(lambda: route.render(b""))
    for byte in (0, 255):
        packet = bytes([byte]) * route.guaranteed
        surface = route.render(packet)
        if route.recover(surface) != packet:
            raise ValueError("verse boundary failed")
        for invalid in (surface[:-1], "\n" + surface, "甲" + surface, surface + "。"):
            expect_rejection(lambda: route.recover(invalid))
    if isinstance(route, SceneVerse):
        expect_rejection(lambda: route.render(b"\xff" * (route.guaranteed + 1)))
        lines = parse(surface, route.width)
        group, _ = route.where[lines[0]]
        other = route.rows[(group + 1) % len(route.rows)][0]
        for invalid in (other, lines[0]):
            modified = layout([lines[0], invalid, *lines[2:]])
            expect_rejection(lambda: route.recover(modified))


def report_batch(name, route, packets, terms, pattern, new_lines, seed):
    stats = []
    union, emitted, new_terms = set(), set(), set()
    for index, packet in enumerate(packets):
        surface = route.render(packet)
        if route.recover(surface) != packet:
            raise ValueError("paired verse packet did not recover")
        lines = parse(surface, route.width)
        words = matched_terms(lines, terms)
        new = new_lines.intersection(lines)
        used = {term for line in new for term in pattern.findall(line)}
        emitted.update(new)
        union.update(words)
        new_terms.update(used)
        sentences = verse_parts(lines)
        pairs = [(previous, current) for position, (previous, current) in enumerate(zip(lines, lines[1:]), 1)
                 if position % 4]
        exact_anchor = sum(bool(set(pattern.findall(a)) & set(pattern.findall(b))) for a, b in pairs)
        old_marker = sum(last_image(a) in b for a, b in pairs
                         if a not in new_lines and b not in new_lines)
        stats.append((len(words), len(used), len(surface.encode()), 1 - len(set(lines)) / len(lines),
                      1 - len(set(sentences)) / len(sentences),
                      1 - len({pattern.sub("○", line) for line in sentences}) / len(sentences),
                      exact_anchor / max(1, len(pairs)), old_marker / max(1, len(pairs))))
        if seed == HOLDOUT_SEED and index in (0, 37):
            print(f"  {name}/{len(packet)}/{index} {layout(lines[16:24]).replace(chr(10), ' / ')}")
    words, used, length, line_repeat, repeat, masked, anchor, marker = map(median, zip(*stats))
    print(f"{seed}/{len(packets[0])} {name} | {len(packets)} exact | words {words:g}/{len(union)}"
          f" | new slot terms {used:g}/{len(new_terms)} | new lines {len(emitted)}"
          f" | repeats (line/sentence/masked) {line_repeat:.1%}/{repeat:.1%}/{masked:.1%}"
          f" | bytes {length:g}/{max(row[2] for row in stats)} | within-stanza anchor {anchor:.1%}"
          f" | legacy marker {marker:.1%}")


def report_form(width, lines, lexicon, terms, calibration):
    frozen = FrozenVerse(width, lines)
    scenes, anchors, pattern = banks(lines, lexicon)
    routes = {"frozen": frozen, "scene": SceneVerse(width, scenes), "anchor": SceneVerse(width, anchors)}
    baseline = set(frozen.opening)
    for name, route in routes.items():
        stock = set(route.opening) if name == "frozen" else set(route.where)
        new = stock - baseline
        print(f"{width}/{name} | candidate lines {len(stock)} | added lines {len(new)}"
              f" | shared terms {len(matched_terms(stock, terms))} | guarantee {route.guaranteed} B"
              f" | worst 178/524 {route.worst(178)}/{route.worst(524)}")
        controls(route)
        for seed in (*SEEDS, HOLDOUT_SEED):
            rng = Random(seed)
            for size in (178, 524):
                report_batch(f"{width}/{name}", route, [rng.randbytes(size) for _ in range(64)],
                             terms, pattern, new, seed)
        if calibration:
            report_surface_calibration(f"{width}/{name}", route.render, route.recover, route.worst, calibration)


def main(path, calibration):
    _, tables = locked_faces()
    lexicon = set().union(*tables.values())
    terms = {word for word, count in evidence(read_source(path), lexicon).items() if count >= 3}
    entries = calibration_envelopes(calibration) if calibration else []
    resources = Resources()
    for width, lines in ((5, resources.free), (7, resources.seven)):
        report_form(width, lines, lexicon, terms, entries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--calibration", type=Path)
    args = parser.parse_args()
    main(args.corpus, args.calibration)
