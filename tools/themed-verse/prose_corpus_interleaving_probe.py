"""Compare exact variable sentence order with fixed per-length quotas on the same topic banks."""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from dataclasses import dataclass
from math import comb
from pathlib import Path
from random import Random

from prose_corpus_coverage_probe import (
    ScheduledSentenceBank, benchmark, benchmark_topics, compile_bank, evidence, planned_topics,
    print_batch, read_source, topic_banks,
)
from prose_ngram import CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, STOP, CapacityError
from prose_window_progression_probe import progression_routes
from surface_lexicon_probe import locked_faces


def subset_at(pool, count, rank):
    chosen = []
    start = 0
    for remaining in range(count, 0, -1):
        for index in range(start, len(pool) - remaining + 1):
            block = comb(len(pool) - index - 1, remaining - 1)
            if rank < block:
                chosen.append(pool[index])
                start = index + 1
                break
            rank -= block
        else:
            raise ValueError("combination rank is outside the sentence bucket")
    return chosen


def subset_rank(pool, chosen):
    start, rank = 0, 0
    size = len(chosen)
    for offset, item in enumerate(sorted(chosen)):
        index = pool.index(item, start)
        rank += sum(comb(len(pool) - skipped - 1, size - offset - 1) for skipped in range(start, index))
        start = index + 1
    return rank


@dataclass(frozen=True)
class Shape:
    counts: tuple
    units: int
    sentences: int
    span: int


class InterleavedSentenceBank:
    """Store each pass as a subset per sentence length followed by one permutation."""

    def __init__(self, bank, passes):
        grouped = defaultdict(list)
        for sentence in sorted(bank):
            grouped[len(sentence) + 1].append(sentence)
        self.groups = dict(sorted(grouped.items()))
        self.passes = passes
        self.budget = min(CARRIER_BYTES // 3, CARRIER_UNITS)
        self.factorials = [1]
        self.shapes = self.plan()
        self.spans = [shape.span ** passes for shape in self.shapes]
        self.by_units = {shape.units * passes: shape for shape in self.shapes}

    def plan(self):
        counts = dict.fromkeys(self.groups, 0)
        shapes = [Shape(tuple(counts.values()), 0, 0, 1)]
        while width := self.next_width(counts, shapes[-1]):
            prior = shapes[-1]
            sentences = prior.sentences + 1
            span = prior.span * sentences * (len(self.groups[width]) - counts[width]) // (counts[width] + 1)
            counts[width] += 1
            self.factorials.append(self.factorials[-1] * sentences)
            shapes.append(Shape(tuple(counts.values()), prior.units + width, sentences, span))
        return shapes

    def next_width(self, counts, shape):
        selected, numerator, denominator = 0, 1, 1
        for width, pool in self.groups.items():
            if (shape.units + width) * self.passes > self.budget or counts[width] == len(pool):
                continue
            gain = (shape.sentences + 1) * (len(pool) - counts[width])
            divisor = counts[width] + 1
            if not selected or gain ** selected * denominator ** width > numerator ** width * divisor ** selected:
                selected, numerator, denominator = width, gain, divisor
        return selected

    def guaranteed(self):
        return (self.spans[-1].bit_length() - 2) // 8

    def widest(self, size):
        index = bisect_right(self.spans, (1 << (8 * size + 1)) - 1)
        return self.shapes[index].units * self.passes * 3 if index < len(self.shapes) else None

    def unrank_pass(self, shape, rank):
        chosen = []
        for count, pool in zip(shape.counts, self.groups.values(), strict=True):
            rank, digit = divmod(rank, comb(len(pool), count))
            chosen.extend(subset_at(pool, count, digit))
        ordered = sorted(chosen)
        output = []
        for remaining in range(len(ordered) - 1, -1, -1):
            digit, rank = divmod(rank, self.factorials[remaining])
            output.append(ordered.pop(digit))
        return output

    def rank_pass(self, shape, sentences):
        if len(sentences) != shape.sentences or len(set(sentences)) != len(sentences):
            raise ValueError("a pass must be a permutation of distinct source sentences")
        grouped = defaultdict(list)
        for sentence in sentences:
            grouped[len(sentence) + 1].append(sentence)
        if tuple(len(grouped[width]) for width in self.groups) != shape.counts:
            raise ValueError("invalid sentence length counts")
        if set(grouped) - self.groups.keys():
            raise ValueError("unknown sentence length")
        ordered = sorted(sentences)
        rank = 0
        for sentence in sentences:
            digit = ordered.index(sentence)
            rank = rank * len(ordered) + digit
            ordered.pop(digit)
        for width, pool in reversed(self.groups.items()):
            subset = grouped[width]
            rank = rank * comb(len(pool), len(subset)) + subset_rank(pool, subset)
        return rank

    def render(self, payload):
        if not 1 <= len(payload) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid public envelope length")
        number = int.from_bytes(b"\x01" + payload, "big")
        index = bisect_right(self.spans, number)
        if index == len(self.shapes):
            raise CapacityError("the interleaved sentence bank exceeds its visible budget")
        shape = self.shapes[index]
        output = []
        for _ in range(self.passes):
            number, rank = divmod(number, shape.span)
            output.extend(self.unrank_pass(shape, rank))
        return STOP.join(output) + STOP

    def recover(self, carrier):
        if not carrier.endswith(STOP) or len(carrier) > self.budget:
            raise ValueError("invalid interleaved carrier")
        shape = self.by_units.get(len(carrier))
        sentences = carrier[:-1].split(STOP)
        if shape is None or len(sentences) != shape.sentences * self.passes:
            raise ValueError("unknown interleaved sentence shape")
        number = 0
        for offset in range(len(sentences) - shape.sentences, -1, -shape.sentences):
            number = number * shape.span + self.rank_pass(shape, sentences[offset:offset + shape.sentences])
        frame = number.to_bytes((number.bit_length() + 7) // 8, "big")
        if not 2 <= len(frame) <= MAX_ENVELOPE_BYTES + 1 or frame[0] != 1:
            raise ValueError("invalid interleaved envelope frame")
        payload = frame[1:]
        if self.render(payload) != carrier:
            raise ValueError("noncanonical interleaved sentence carrier")
        return payload


def check_toy():
    route = InterleavedSentenceBank(("甲乙", "乙甲", "丙丁戊", "丁丙戊", "戊丙丁"), 1)
    for shape in route.shapes[1:]:
        outputs = [tuple(route.unrank_pass(shape, rank)) for rank in range(shape.span)]
        if len(set(outputs)) != shape.span:
            raise ValueError("toy interleaving contains duplicate ranks")
        if any(route.rank_pass(shape, output) != rank for rank, output in enumerate(outputs)):
            raise ValueError("toy permutation did not recover at least one rank")
    capped = CappedSentenceBank(("甲乙", "乙甲", "丙丁戊", "丁丙戊", "戊丙丁"), 200, short_bytes=0)
    for shape in capped.shapes[1:]:
        outputs = [tuple(capped.unrank_pass(shape, rank)) for rank in range(shape.span)]
        if len(set(outputs)) != shape.span:
            raise ValueError("toy capped multiset contains duplicate ranks")
        if any(capped.rank_pass(shape, output) != rank for rank, output in enumerate(outputs)):
            raise ValueError("toy capped multiset did not recover at least one rank")


def check_boundary(route, receiver, max_occurrences):
    for fill in (0, 255):
        payload = bytes([fill]) * route.guaranteed()
        text = route.render(payload)
        if receiver.recover(text) != payload or max(Counter(text[:-1].split(STOP)).values()) > max_occurrences:
            raise ValueError("interleaving did not recover or respect the pass limit")
    try:
        route.render(bytes([255]) * (route.guaranteed() + 1))
    except CapacityError:
        return
    raise ValueError("interleaving accepted a worst-case oversize payload")


@dataclass(frozen=True)
class CappedShape(Shape):
    doubled: tuple
    copies: int
    order_span: int


class CappedSentenceBank(InterleavedSentenceBank):
    """Recover the number of singletons and twins from total visible length."""

    def __init__(self, bank, repeat_permille, short_bytes=178):
        self.repeat_permille = repeat_permille
        self.short_limit = (1 << (8 * short_bytes + 1)) - 1
        super().__init__(bank, 1)

    def plan(self):
        counts = dict.fromkeys(self.groups, 0)
        doubled = counts.copy()
        shapes = [CappedShape(tuple(counts.values()), 0, 0, 1, tuple(doubled.values()), 0, 1)]
        while selection := self.next_move(counts, doubled, shapes[-1]):
            width, twin, numerator, denominator = selection
            previous = shapes[-1]
            counts[width] += -1 if twin else 1
            doubled[width] += twin
            sentences = previous.sentences + 1
            span = previous.span * numerator // denominator
            order_span = previous.order_span * sentences // (2 if twin else 1)
            shapes.append(CappedShape(tuple(counts.values()), previous.units + width, sentences, span,
                                      tuple(doubled.values()), previous.copies + twin, order_span))
        return shapes

    def next_move(self, counts, doubled, shape):
        selected = None
        for width, pool in self.groups.items():
            if shape.units + width > self.budget:
                continue
            unused = len(pool) - counts[width] - doubled[width]
            moves = [(False, (shape.sentences + 1) * unused, counts[width] + 1)]
            if shape.span > self.short_limit and 1000 * (shape.copies + 1) <= self.repeat_permille * (shape.sentences + 1):
                moves.append((True, (shape.sentences + 1) * counts[width], 2 * (doubled[width] + 1)))
            for twin, numerator, denominator in moves:
                if numerator == 0:
                    continue
                if selected:
                    other_width, _, other_num, other_den = selected
                    if numerator ** other_width * other_den ** width <= other_num ** width * denominator ** other_width:
                        continue
                selected = width, twin, numerator, denominator
        return selected

    def unrank_pass(self, shape, rank):
        chosen = []
        counts = zip(shape.counts, shape.doubled, self.groups.values(), strict=True)
        for single, double, pool in counts:
            rank, twin_digit = divmod(rank, comb(len(pool), double))
            twins = subset_at(pool, double, twin_digit)
            unused = [word for word in pool if word not in twins]
            rank, digit = divmod(rank, comb(len(unused), single))
            chosen.extend(subset_at(unused, single, digit) + twins + twins)
        ordered = sorted(chosen)
        counts = Counter(ordered)
        span = shape.order_span
        output = []
        while ordered:
            length = len(ordered)
            ticket = rank * length // span
            word = ordered[ticket]
            rank -= span * bisect_left(ordered, word) // length
            span = span * counts[word] // length
            counts[word] -= 1
            ordered.pop(ticket)
            output.append(word)
        return output

    def rank_pass(self, shape, sentences):
        if len(sentences) != shape.sentences:
            raise ValueError("invalid capped sequence length")
        counts = Counter(sentences)
        single, double = defaultdict(list), defaultdict(list)
        for word, copies in counts.items():
            if copies not in (1, 2) or len(word) + 1 not in self.groups:
                raise ValueError("invalid capped multiplicity or sentence length")
            (single if copies == 1 else double)[len(word) + 1].append(word)
        if (tuple(len(single[width]) for width in self.groups) != shape.counts or
                tuple(len(double[width]) for width in self.groups) != shape.doubled):
            raise ValueError("capped length counts differ from the canonical shape")
        rank = 0
        span = shape.order_span
        ordered = sorted(sentences)
        for word in sentences:
            length = len(ordered)
            index = bisect_left(ordered, word)
            rank += span * index // length
            span = span * counts[word] // length
            counts[word] -= 1
            ordered.pop(index)
        for width, pool in reversed(self.groups.items()):
            twins = double[width]
            unused = [word for word in pool if word not in twins]
            rank = rank * comb(len(unused), len(single[width])) + subset_rank(unused, single[width])
            rank = rank * comb(len(pool), len(twins)) + subset_rank(pool, twins)
        return rank


def choose_cap(bank, size):
    for permille in (75, 100, 125, 150, 165, 175, 200):
        route = CappedSentenceBank(bank, permille)
        if route.guaranteed() >= size:
            return route
    return route


def run(path):
    check_toy()
    source = list(read_source(path))
    _, tables = locked_faces()
    lexicon = set().union(*tables.values())
    dictionary = {word for word, count in evidence(source, lexicon).items() if count >= 3}
    _, banks, _ = topic_banks(source, lexicon, dictionary)
    _, window, _, _, _ = progression_routes()
    original = {name: compile_bank(bank) for name, bank in banks.items()}
    capped = planned_topics(banks, 524)
    single = {name: InterleavedSentenceBank(bank, 1) for name, bank in banks.items()}
    double = {name: InterleavedSentenceBank(bank, 2) for name, bank in banks.items()}
    quotas = {name: choose_cap(bank, 524) for name, bank in banks.items()}
    for name, bank in banks.items():
        for route in (single[name], double[name]):
            check_boundary(route, InterleavedSentenceBank(bank, route.passes), route.passes)
        quota = quotas[name]
        check_boundary(quota, CappedSentenceBank(bank, quota.repeat_permille), 2)
        print(f"{name} | guarantee scheduled {ScheduledSentenceBank(bank).guaranteed()} B"
              f" → single {single[name].guaranteed()} B → double {double[name].guaranteed()} B"
              f" | 524 B double worst {double[name].widest(524)}"
              f" | cap {quota.repeat_permille / 10:g}% guarantees {quota.guaranteed()} B, worst {quota.widest(524)}")
    random = Random(562739)
    for size, control, candidate in ((178, {name: ScheduledSentenceBank(bank) for name, bank in banks.items()}, single),
                                     (524, capped, double)):
        batch = [random.randbytes(size) for _ in range(128)]
        before = benchmark(window, batch, dictionary)
        print_batch(size, "window", before, len(batch))
        benchmark_topics(size, "corpus direct", original, banks, batch, dictionary, before)
        benchmark_topics(size, "scheduled", control, banks, batch, dictionary, before)
        benchmark_topics(size, "interleaved", candidate, banks, batch, dictionary, before)
        benchmark_topics(size, "capped interleaving", quotas, banks, batch, dictionary, before)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    run(parser.parse_args().corpus)
