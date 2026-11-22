"""Measure shared lexicon coverage and reversible topic banks from public Mandarin text."""

import argparse
from bisect import bisect_right
import bz2
from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from random import Random
import re
from statistics import median

from prose_capacity_probe import RankedCarrier
from prose_ngram import CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, CapacityError, STOP, is_han
from prose_window_compact_probe import check_boundary
from prose_window_progression_probe import progression_routes
from prose_window_scene_probe import guaranteed
from surface_lexicon_probe import locked_faces


CORPUS_URL = "https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2"
CORPUS_SIZE = 1_728_820
CORPUS_SHA256 = "48b42fd1531c8a75c1f586a9b287c07910f3dbc719219e62970bef70cfcd42eb"
HAN_RUN = re.compile(r"[一-鿿]+")
TOPICS = {
    "street": "车站 地铁 公交 巴士 电车 交通 出租车 机场 火车 车票 站台 行李 候车 旅行 自行车 骑车 步行 路口 人行道 校车 上车 下车 开车 马路 乘车",
    "home": "厨房 做饭 煮饭 洗碗 餐具 冰箱 台所 打扫 收拾 洗衣服 早餐 午饭 晚饭 晚餐 吃饭 面包 米饭 水果 蔬菜 果汁 洗衣机 水杯 饭碗 筷子 煮汤",
    "learning": "学校 教室 课本 图书馆 读书 阅读 写作 笔记 学习 作业 功课 考试 上课 下课 复习 课堂 字典 辞典 词典 查字 单词 语法 背诵 黑板",
    "outdoors": "天空 天气 树木 树林 河面 河边 草地 下雨 雨天 雪天 下雪 地面 阳光 月光 风景 公园 彩虹 落叶 叶子 鸟叫 花园 湖面 天色 气温 云层 山坡",
}
NEGATIVE_PREFIXES = ("他", "她", "它", "你", "您", "喂", "嗨")


@dataclass(frozen=True)
class SourceSentence:
    identifier: str
    text: str
    owner: str


def read_source(path):
    if path.stat().st_size != CORPUS_SIZE:
        raise ValueError("Mandarin source size differs from the pinned snapshot")
    data = path.read_bytes()
    if sha256(data).hexdigest() != CORPUS_SHA256:
        raise ValueError("Mandarin source hash differs from the pinned snapshot")
    for row in bz2.decompress(data).decode("utf-8").splitlines():
        identifier, language, text, owner, _, _ = row.split("\t")
        if language != "cmn" or not identifier.isascii() or not identifier.isdecimal():
            raise ValueError("unexpected Mandarin source record")
        yield SourceSentence(identifier, text, owner)


def evidence(source, lexicon):
    """A word surface contributes at most once per distinct source sentence."""
    observed = Counter()
    for text in {record.text for record in source}:
        seen = set()
        for part in HAN_RUN.findall(text):
            for width in (2, 3, 4):
                seen.update(part[index:index + width] for index in range(len(part) - width + 1)
                            if part[index:index + width] in lexicon)
        observed.update(seen)
    return observed


def topic_banks(source, lexicon, dictionary):
    markers = {topic: tuple(word for word in words.split() if word in lexicon)
               for topic, words in TOPICS.items()}
    banks = {topic: {} for topic in markers}
    eligible = []
    counts = Counter()
    topic_counts = {topic: Counter() for topic in markers}
    seen_texts = set()
    for record in source:
        text = record.text
        if text in seen_texts:
            continue
        seen_texts.add(text)
        matches = [topic for topic, words in markers.items() if any(word in text for word in words)]
        terms = matched_terms(HAN_RUN.findall(text), dictionary)
        counts.update(terms)
        if len(matches) == 1:
            topic_counts[matches[0]].update(terms)
        if not text.endswith(STOP):
            continue
        sentence = text[:-1]
        if not 4 <= len(sentence) <= 20 or not is_han(sentence) or sentence.startswith(NEGATIVE_PREFIXES):
            continue
        if len(matches) == 1:
            banks[matches[0]].setdefault(sentence, record)
        elif not matches:
            eligible.append((sentence, record, terms))
    anchors = tuple(word for names in markers.values() for word in names)
    recruits = {name: {word for word, count in present.items()
                       if count >= 8 and 5 * count >= counts[word] and not any(anchor in word for anchor in anchors)}
                for name, present in topic_counts.items()}
    strong = {name: {word for word in words if 5 * topic_counts[name][word] >= 2 * counts[word]}
              for name, words in recruits.items()}
    expanded = {name: dict(bank) for name, bank in banks.items()}
    for sentence, record, terms in eligible:
        matches = [name for name, words in recruits.items() if terms & strong[name] or len(terms & words) >= 2]
        if len(matches) == 1:
            expanded[matches[0]].setdefault(sentence, record)
    return banks, expanded, recruits


def tokens(sentence, dictionary):
    """Use the same non-overlapping longest match in all arms, ignoring single characters."""
    index = 0
    while index + 1 < len(sentence):
        for width in (4, 3, 2):
            word = sentence[index:index + width]
            if len(word) == width and word in dictionary:
                yield word
                index += width
                break
        else:
            index += 1


def matched_terms(sentences, dictionary):
    return {word for sentence in sentences for word in tokens(sentence, dictionary)}


def compile_bank(bank):
    if len(bank) < 16:
        raise ValueError("too few topical source sentences to compare")
    graph = [{sentence: (1, 1) for sentence in bank}]
    route = RankedCarrier(graph)
    check_boundary(route, RankedCarrier(graph))
    return route


class ScheduledSentenceBank:
    """Mix decreasing radices within a fixed number of fresh passes over the source sentences."""

    def __init__(self, bank, passes=1, short_through=0):
        groups = defaultdict(list)
        for sentence in sorted(bank):
            groups[max(len(sentence) + 1, short_through)].append(sentence)
        self.groups = dict(groups)
        self.passes = passes
        self.short_through = short_through
        budget, extra = divmod(min(CARRIER_BYTES // 3, CARRIER_UNITS), passes)
        self.steps = []
        self.widths = [0]
        self.spans = [1]
        for index in range(passes):
            quotas = {width: len(words) for width, words in groups.items()}
            left = budget + (index < extra)
            reset = True
            while width := self.best_bucket(quotas, left):
                self.steps.append((width, reset))
                self.widths.append(self.widths[-1] + width)
                self.spans.append(self.spans[-1] * quotas[width])
                quotas[width] -= 1
                left -= width
                reset = False

    @staticmethod
    def best_bucket(quotas, remaining):
        selected = 0
        for width, count in sorted(quotas.items()):
            if width <= remaining and count > 1 and (not selected or count ** selected > quotas[selected] ** width):
                selected = width
        return selected

    def guaranteed(self):
        return (self.spans[-1].bit_length() - 2) // 8

    def widest(self, size):
        # The framed integer runs from 2^(8n) to 2^(8n+1)-1 inclusive.
        step = bisect_right(self.spans, (1 << (8 * size + 1)) - 1)
        return self.widths[step] * 3 if step < len(self.widths) else None

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid public envelope")
        number = int.from_bytes(b"\x01" + packet, "big")
        if number >= self.spans[-1]:
            raise CapacityError("distinct sentence bank exceeds budget")
        parts = []
        for width, reset in self.steps:
            if reset:
                available = {width: list(words) for width, words in self.groups.items()}
            options = available[width]
            number, digit = divmod(number, len(options))
            parts.append(options.pop(digit) + STOP)
            if not number:
                return "".join(parts)
        raise ValueError("planned sentence bank did not finish ranking")

    def recover(self, carrier):
        if not carrier.endswith(STOP) or len(carrier) > self.widths[-1]:
            raise ValueError("invalid distinct sentence carrier")
        pieces = carrier[:-1].split(STOP)
        if len(pieces) > len(self.steps):
            raise ValueError("too many sentences for the distinct bank")
        number, factor, digit = 0, 1, 0
        for (width, reset), sentence in zip(self.steps, pieces):
            if reset:
                available = {width: list(words) for width, words in self.groups.items()}
            options = available[width]
            digit = options.index(sentence)
            number += factor * digit
            factor *= len(options)
            options.pop(digit)
        packed = number.to_bytes((number.bit_length() + 7) // 8, "big")
        if not digit or not 2 <= len(packed) <= MAX_ENVELOPE_BYTES + 1 or packed[0] != 1:
            raise ValueError("invalid distinct sentence frame")
        packet = packed[1:]
        if self.render(packet) != carrier:
            raise ValueError("noncanonical distinct sentence frame")
        return packet


def check_schedule(bank, route):
    receiver = ScheduledSentenceBank(bank, route.passes, route.short_through)
    for byte in (0, 255):
        packet = bytes([byte]) * route.guaranteed()
        text = route.render(packet)
        if receiver.recover(text) != packet:
            raise ValueError("distinct bank did not recover its guaranteed limit")
        if max(Counter(text[:-1].split(STOP)).values()) > route.passes:
            raise ValueError("the pass limit was not enforced")
    try:
        route.render(bytes([255]) * (route.guaranteed() + 1))
    except CapacityError:
        pass
    else:
        raise ValueError("distinct bank accepted a worst-case oversize packet")
    sentence = route.render(bytes(16)).split(STOP)[0] + STOP
    try:
        receiver.recover(sentence + sentence)
    except ValueError:
        return
    raise ValueError("distinct bank accepted the same source sentence twice")


def planned_topics(banks, target):
    best = None
    best_limit = 0
    for passes in range(2, 13):
        routes = {name: max((ScheduledSentenceBank(bank, passes, width) for width in range(6, 13)),
                            key=ScheduledSentenceBank.guaranteed)
                  for name, bank in banks.items()}
        limit = min(route.guaranteed() for route in routes.values())
        if limit > best_limit:
            best, best_limit = routes, limit
        if limit >= target:
            break
    if best is None:
        raise ValueError("no bounded sentence schedule can carry a framed packet")
    return best


@dataclass(frozen=True)
class Batch:
    terms: frozenset
    median_terms: float
    median_long_terms: float
    median_length: float
    median_repeat: float
    median_term_density: float
    rows: int


def benchmark(route, packets, dictionary, bank=None):
    terms, distinct, longer, lengths, repetition, density = set(), [], [], [], [], []
    for packet in packets:
        try:
            rendered = route.render(packet)
        except CapacityError:
            continue
        if route.recover(rendered) != packet:
            raise ValueError("public sample failed exact recovery")
        sentences = rendered[:-1].split(STOP)
        if bank is not None and any(sentence not in bank for sentence in sentences):
            raise ValueError("a thematic route emitted a sentence outside its source bank")
        matched = matched_terms(sentences, dictionary)
        terms.update(matched)
        distinct.append(len(matched))
        density.append(len(matched) * 1000 / sum(map(len, sentences)))
        longer.append(sum(len(word) >= 3 for word in matched))
        lengths.append(len(rendered.encode()))
        repetition.append(1 - len(set(sentences)) / len(sentences))
    if not lengths:
        return Batch(frozenset(), 0, 0, 0, 0, 0, 0)
    return Batch(frozenset(terms), median(distinct), median(longer), median(lengths), median(repetition),
                 median(density), len(lengths))


def widest(route, bytes_count):
    units = bisect_right(route.cumulative, (1 << (8 * bytes_count + 1)) - 2)
    return units * 3 if units < len(route.cumulative) else None


def report_sources(source, table_count, lexicon, occurrence, accepted, banks, baseline):
    ordinary = sum(2 <= len(word) <= 4 and is_han(word) for word in lexicon)
    print(f"source | {len(source)} records | {len({row.text for row in source})} distinct source sentences | {CORPUS_URL}")
    print(f"lexicon | {table_count} verified tables | {len(lexicon)} distinct surfaces"
          f" | {ordinary} Han surfaces of 2–4 characters")
    print(f"source ∩ lexicon | {len(occurrence)} span matches | {len(accepted)} attested in ≥3 distinct source sentences")
    window_sentences = {sentence for row in baseline.words for sentence in row}
    print(f"window frozen candidates | {len(window_sentences)} sentences"
          f" | {len(matched_terms(window_sentences, accepted))} matched terms on the shared inventory")
    for topic, bank in banks.items():
        print(f"bank {topic} | {len(bank)} sentences | {len({row.owner for row in bank.values() if row.owner})} credited owners"
              f" | {len(matched_terms(bank, accepted))} matched terms")


def run(path):
    source = list(read_source(path))
    _, tables = locked_faces()
    lexicon = set().union(*tables.values())
    occurrence = evidence(source, lexicon)
    accepted = {word for word, count in occurrence.items() if count >= 3}
    anchor_banks, banks, recruits = topic_banks(source, lexicon, accepted)
    _, baseline, _, _, _ = progression_routes()
    routes = {name: compile_bank(bank) for name, bank in banks.items()}
    distinct = {name: ScheduledSentenceBank(bank) for name, bank in banks.items()}
    capped = planned_topics(banks, 524)
    passes = next(iter(capped.values())).passes
    for routes_to_check in (distinct, capped):
        for name, route in routes_to_check.items():
            check_schedule(banks[name], route)
    report_sources(source, len(tables), lexicon, occurrence, accepted, banks, baseline)
    for topic, original in anchor_banks.items():
        print(f"recruit {topic} | {len(original)} single-topic seeds → {len(banks[topic])} eligible source sentences"
              f" | {len(recruits[topic])} accepted collocational signals")
    for name, route in (("window", baseline), *routes.items()):
        print(f"capacity {name} | guarantee {guaranteed(route)} B | 524 B worst {widest(route, 524)}")
    for name, route in distinct.items():
        print(f"capacity distinct/{name} | guarantee {route.guaranteed()} B | 524 B worst {route.widest(524)}")
    for name, route in capped.items():
        print(f"capacity {passes}-pass/{name} | guarantee {route.guaranteed()} B"
              f" | 524 B reserved {route.widest(524)} | merged widths through {route.short_through}")
    random = Random(562739)
    for size in (178, 524):
        packets = [random.randbytes(size) for _ in range(128)]
        before = benchmark(baseline, packets, accepted)
        print_batch(size, "window", before, len(packets))
        benchmark_topics(size, "source", routes, banks, packets, accepted, before)
        if size == 178:
            benchmark_topics(size, "distinct", distinct, banks, packets, accepted, before)
        else:
            benchmark_topics(size, f"{passes}-pass", capped, banks, packets, accepted, before)


def benchmark_topics(size, style, routes, banks, packets, accepted, before):
    per_topic = []
    for index, (name, route) in enumerate(routes.items()):
        selected = packets[index::len(routes)]
        after = benchmark(route, selected, accepted, banks[name])
        per_topic.append(after)
        print_batch(size, f"{style}/{name}", after, len(selected))
    union = frozenset().union(*(batch.terms for batch in per_topic))
    decoded = sum(batch.rows for batch in per_topic)
    if decoded == len(packets):
        print(f"{size} B {style} aggregate | {decoded}/{len(packets)} exact"
              f" | {len(before.terms)} → {len(union)} union terms | {len(union - before.terms)} new")
    else:
        print(f"{size} B {style} aggregate | {decoded}/{len(packets)} exact | coverage comparisons require a complete cohort")


def print_batch(size, name, batch, samples):
    print(f"{size} B {name} | {batch.rows}/{samples} exact | medians term {batch.median_terms:g}"
          f" | three/four-character term {batch.median_long_terms:g} | bytes {batch.median_length:g}"
          f" | terms/1000 Han {batch.median_term_density:.1f} | repeated sentence {batch.median_repeat:.1%}"
          f" | {len(batch.terms)} union terms")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path, help="locally pinned Mandarin detailed export")
    run(parser.parse_args().corpus)
