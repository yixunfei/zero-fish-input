"""Measure exact finite prose capacity and separate coding gains from shorter rules."""

import argparse
from bisect import bisect_right
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
from random import Random
from statistics import median

from association_probe import ASSOCIATIONS, CORPUS_LOCK, envelope_bytes
from export_word_context import model_asset
from p2_corpus import read_lock, samples
from prose_grammar import CandidateWeights, GrammarCarrier, compile_scene, freeze
from prose_grammar_probe import CORPUS, expect_rejection
from prose_model_probe import CORPUS as TRAINING, MaskedScorer, measure as measure_surface
from prose_ngram import CapacityError, CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, STOP, is_han
from surface_lexicon_probe import DailyProbe, locked_faces
from word_context_probe import build_codecs, measure as measure_body
from wording_daily import daily_asset


SHORTEN = {
    "outing": (
        ("出门前先把", "先把"), ("临走前把", "临走把"), ("出门前把", "临走把"),
        ("出门前再", "再"), ("临走前再", "临走再"),
    ),
    "observation": (
        ("更远处的", "远处的"), ("朝远处望去", "望向远处"), ("接着往外看", "再往外看"),
        ("隔一会儿再看", "隔会儿再看"), ("过一会儿", "过会儿"), ("再看一会儿", "再看会儿"),
        ("显得有些模糊", "有些模糊"), ("看起来有些模糊", "有些模糊"),
    ),
    "organizing": (
        ("整理桌面时先把", "先把"), ("收拾时先把", "先把"),
        ("整理桌面时先", "先"), ("收拾时先", "先"), ("整理时把", "再把"), ("接下来把", "再把"),
        ("然后把", "再把"), ("收拾完再", "最后再"), ("整理完再", "最后再"),
        ("收拾完把", "最后把"), ("整理完把", "最后把"),
    ),
}


class RankedCarrier:
    """Rank valid sentences by total visible length, then by a deterministic order within that length."""

    def __init__(self, phases, limit=min(CARRIER_BYTES // 3, CARRIER_UNITS), destinations=None, endings=None):
        self.groups = []
        self.words = []
        self.endings = tuple(endings) if endings is not None else (True,) * len(phases)
        if len(self.endings) != len(phases) or destinations is not None and len(destinations) != len(phases):
            raise ValueError("ranked grammar states do not agree")
        for state, phase in enumerate(phases):
            sizes = defaultdict(list)
            for sentence, (_, weight) in sorted(phase.items(), key=lambda item: (-item[1][1], item[0])):
                if not is_han(sentence):
                    raise ValueError("expected BMP Han sentence")
                target = (state + 1) % len(phases) if destinations is None else destinations[state][sentence]
                if not 0 <= target < len(phases):
                    raise ValueError("ranked grammar transition is outside the state table")
                sizes[len(sentence) + 1, target].append(sentence)
            groups = [(size, target, words) for (size, target), words in sorted(sizes.items())]
            self.groups.append(groups)
            self.words.append({sentence: (group_index, word_index) for group_index, (_, _, group) in enumerate(groups)
                               for word_index, sentence in enumerate(group)})
        self.build_counts(limit)

    def build_counts(self, limit):
        steps = [tuple((size, target, len(words)) for size, target, words in groups) for groups in self.groups]
        # A pending continuation cannot end. When it returns directly to an accepting state,
        # fold its costs into the preceding edge for counting, keeping its visible transitions for ranking.
        self.folded = [row if not end and all(self.endings[target] for _, target, _ in row) else None
                       for row, end in zip(steps, self.endings, strict=True)]
        shared_counts = {}
        self.exact = []
        for state, (row, ending) in enumerate(zip(steps, self.endings, strict=True)):
            if self.folded[state] is not None:
                self.exact.append(None)
                continue
            combinations = defaultdict(int)
            for size, target, cardinality in row:
                tail = self.folded[target]
                if tail is None:
                    combinations[size, target] += cardinality
                else:
                    for width, next_state, choices in tail:
                        combinations[size + width, next_state] += cardinality * choices
            transitions = tuple((size, target, choices) for (size, target), choices in sorted(combinations.items()))
            signature = ending, transitions
            if signature not in shared_counts:
                shared_counts[signature] = [int(ending)] + [0] * limit
            self.exact.append(shared_counts[signature])
        self.cumulative = [0] * (limit + 1)
        for length in range(1, limit + 1):
            for (_, transitions), counts in shared_counts.items():
                counts[length] = sum(cardinality * self.exact[target][length - size]
                                     for size, target, cardinality in transitions if size <= length)
            self.cumulative[length] = self.cumulative[length - 1] + self.suffix_count(0, length)

    def suffix_count(self, state, length):
        counts = self.exact[state]
        if counts is not None:
            return counts[length]
        return sum(cardinality * self.exact[target][length - size]
                   for size, target, cardinality in self.folded[state] if size <= length)

    def render_rank(self, rank):
        remaining = bisect_right(self.cumulative, rank)
        if rank < 0 or remaining == len(self.cumulative):
            raise CapacityError("rank is outside the public grammar budget")
        rank -= self.cumulative[remaining - 1]
        text, state = [], 0
        while remaining:
            for size, target, words in self.groups[state]:
                if size > remaining:
                    continue
                suffix_count = self.suffix_count(target, remaining - size)
                block = len(words) * suffix_count
                if rank < block:
                    index, rank = divmod(rank, suffix_count)
                    text.append(words[index] + STOP)
                    remaining -= size
                    state = target
                    break
                rank -= block
            else:
                raise ValueError("rank failed to resolve a sentence")
        return "".join(text)

    def recover_rank(self, text):
        length = len(text)
        if not 1 <= length < len(self.cumulative) or not text.endswith(STOP):
            raise ValueError("invalid ranked carrier length or punctuation")
        rank, remaining, state = self.cumulative[length - 1], length, 0
        for sentence in text[:-1].split(STOP):
            key = self.words[state].get(sentence)
            if key is None:
                raise ValueError("unknown or out-of-order sentence")
            group_index, word_index = key
            groups = self.groups[state]
            size, target, _ = groups[group_index]
            rank += sum(len(words) * self.suffix_count(next_state, remaining - width)
                        for width, next_state, words in groups[:group_index] if width <= remaining)
            rank += word_index * self.suffix_count(target, remaining - size)
            remaining -= size
            state = target
        if not self.endings[state]:
            raise ValueError("ranked carrier has a pending continuation")
        return rank

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid synthetic packet length")
        return self.render_rank(int.from_bytes(b"\x01" + packet, "big") - 1)

    def recover(self, text):
        number = self.recover_rank(text) + 1
        packet = number.to_bytes((number.bit_length() + 7) // 8, "big")
        if not 2 <= len(packet) <= MAX_ENVELOPE_BYTES + 1 or packet[0] != 1:
            raise ValueError("invalid ranked packet framing")
        if self.render(packet[1:]) != text:
            raise ValueError("noncanonical ranked packet")
        return packet[1:]


def shorten(scene_id, phases):
    rules = SHORTEN[scene_id]
    results = []
    for phase in phases:
        result = {}
        for sentence, weights in phase.items():
            for before, after in rules:
                # Scoped public rules remove repeated sentence leads or compress a repeated hedge.
                if sentence.startswith(before):
                    sentence = after + sentence[len(before):]
                    break
            for before, after in rules:
                if sentence.endswith(before):
                    sentence = sentence[:-len(before)] + after
                    break
            old = result.get(sentence, (0, 0))
            result[sentence] = tuple(left + right for left, right in zip(old, weights, strict=True))
        results.append(result)
    return results


def bias_shorter(phases):
    results = []
    for phase in phases:
        longest = max(map(len, phase))
        results.append({sentence: (count, weight << ((longest - len(sentence)) // 4))
                        for sentence, (count, weight) in phase.items()})
    return results


def check_ranks():
    words = (("\u7532", "\u4e59\u4e59"), ("\u4e19", "\u4e01\u4e19", "\u620a"), ("\u5df1", "\u5e9a\u5df1"))
    toy = RankedCarrier([{word: (1, 1) for word in row} for row in words], limit=9)
    emitted = [toy.render_rank(rank) for rank in range(toy.cumulative[-1])]
    brute_force = {""}
    frontier = {""}
    for index in range(9 // 2):
        frontier = {prefix + word + STOP for prefix in frontier for word in words[index % len(words)]
                    if len(prefix) + len(word) + 1 <= 9}
        brute_force.update(frontier)
    if set(emitted) != brute_force - {""} or any(toy.recover_rank(text) != rank for rank, text in enumerate(emitted)):
        raise ValueError("rank enumeration was not bijective")
    if list(map(len, emitted)) != sorted(map(len, emitted)):
        raise ValueError("rank enumeration was not length ordered")
    expect_rejection(lambda: toy.render_rank(len(emitted)))
    expect_rejection(lambda: toy.recover_rank(emitted[0][:-1]))


def check_packets(route):
    for packet in (b"\x00", b"\xff", b"\x00\x00", b"\x00\x01\x02"):
        if route.recover(route.render(packet)) != packet:
            raise ValueError("ranked packet did not round-trip")
    expect_rejection(lambda: route.recover("A" + route.render(b"\x00")))
    expect_rejection(lambda: route.recover(route.render(b"\x00")[:-1]))


def bound(route, byte_budget):
    count = route.cumulative[byte_budget // 3]
    return count.bit_length() - 1, ((count + 1).bit_length() - 2) // 8


def report_caps(scene, label, phased, route):
    print(f"{scene} | {label} leaves {[len(row) for row in phased]}"
          f" | exact bits / guaranteed framed B {[bound(route, limit) for limit in (5120, 10240, 16384)]}")
    targets = [bisect_right(route.cumulative, (1 << (8 * size + 1)) - 2) for size in (178, 524, 780)]
    print(f"{scene} | {label} worst-rank shortest budgets "
          f"{[length * 3 if length < len(route.cumulative) else None for length in targets]}")


def report_calibration_bound(scene, label, route, calibration):
    fits_hard = sum(size <= bound(route, CARRIER_BYTES)[1] for original, size in calibration)
    fits_target = sum(size <= bound(route, min(CARRIER_BYTES, 5 * original))[1] for original, size in calibration)
    print(f"{scene} | {label} public calibration guaranteed envelopes"
          f" | hard limit {fits_hard}/{len(calibration)} | 20% target {fits_target}/{len(calibration)}")


def report_codecs(scene, phases, batches, calibration):
    short = shorten(scene, phases)
    base_rank, short_rank = RankedCarrier(phases), RankedCarrier(short)
    report_caps(scene, "current", phases, base_rank)
    report_caps(scene, "short", short, short_rank)
    if calibration:
        report_calibration_bound(scene, "current", base_rank, calibration)
        report_calibration_bound(scene, "short", short_rank, calibration)
    check_packets(base_rank)
    check_packets(short_rank)
    options = {
        "huffman": GrammarCarrier(freeze(scene, phases, True)),
        "length-biased": GrammarCarrier(freeze(scene, bias_shorter(phases), True)),
        "rank": base_rank,
        "short+huffman": GrammarCarrier(freeze(scene, short, True)),
        "short+rank": short_rank,
    }
    for name, route in options.items():
        summary = " | ".join(f"{size} B: {measure_surface(route, packets)}" for size, packets in batches.items())
        print(f"{scene} | {name} | {summary}")


def calibration_envelopes(folder):
    lock, digest = read_lock(folder / "lock.json")
    if digest != sha256(CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")).hexdigest():
        raise ValueError("corpus differs from the repository lock")
    if sha256(model_asset(folder)).hexdigest() != "be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f":
        raise ValueError("calibration does not reproduce the current Debug model")
    training = [raw.decode("utf-8") for _, raw in samples(lock, folder, "training")]
    huffman, codecs, _ = build_codecs(training, ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n"))
    selected = {name: codecs[name] for name in ("recent_only", "bigram_recent")}
    results = []
    for _, raw in samples(lock, folder, "calibration"):
        if 1024 <= len(raw) <= 2048:
            lengths, _ = measure_body(raw, huffman, selected)
            results.append((len(raw), envelope_bytes(lengths["targeted_selection"])))
    if not results:
        raise ValueError("calibration has no requested long-body samples")
    return results


def report_surface_calibration(name, render, recover, worst, entries):
    """Replay the same synthetic envelopes at the locked public bodies' measured sizes."""
    random = Random(241121)
    guaranteed_fit = guaranteed_target = 0
    utilization = []
    for original, size in entries:
        packet = random.randbytes(size)
        bound = worst(size)
        guaranteed_fit += bound is not None
        guaranteed_target += bound is not None and bound <= 5 * original
        try:
            surface = render(packet)
        except CapacityError as failure:
            if bound is not None:
                raise ValueError("a guaranteed calibration packet was rejected") from failure
            continue
        if recover(surface) != packet:
            raise ValueError("locked calibration packet did not recover")
        length = len(surface.encode("utf-8"))
        if bound is not None and length > bound:
            raise ValueError("calibration carrier exceeded its guaranteed length")
        utilization.append(original / length)
    ratios = (f"{min(utilization):.1%}/{median(utilization):.1%}/{max(utilization):.1%}"
              if utilization else "n/a")
    target = sum(ratio >= 0.2 for ratio in utilization)
    print(f"{name} calibration {len(entries)} | guaranteed fit/20% {guaranteed_fit}/{guaranteed_target}"
          f" | sampled fit/20% {len(utilization)}/{target} | utilization min/median/max {ratios}")


def report_daily_calibration(calibration):
    daily = DailyProbe(daily_asset())
    random = Random(241121)
    fits_hard, fits_target = 0, 0
    for original, size in calibration:
        packet = random.randbytes(size)
        surface = daily.render(packet)
        if daily.recover(surface, size) != packet:
            raise ValueError("Daily calibration fixture did not round-trip")
        length = len(surface.encode())
        fits_hard += length <= CARRIER_BYTES
        fits_target += length <= min(CARRIER_BYTES, 5 * original)
    print(f"Daily | public calibration equal-size packets | hard limit {fits_hard}/{len(calibration)}"
          f" | 20% target {fits_target}/{len(calibration)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calibration", type=Path, help="optional locked public training and calibration folder")
    args = parser.parse_args()
    check_ranks()
    calibration = calibration_envelopes(args.calibration) if args.calibration else []
    if calibration:
        sizes = [size for _, size in calibration]
        print(f"public calibration | {len(sizes)} long bodies | wrapped size min/median/max"
              f" {min(sizes)}/{median(sizes):.0f}/{max(sizes)} B | total {sum(sizes)} B")
        report_daily_calibration(calibration)
    scenes = json.loads(CORPUS.read_bytes())["scenes"]
    training = {scene["id"]: scene["training"] for scene in json.loads(TRAINING.read_bytes())["scenes"]}
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    mini = MaskedScorer("mini-int8")
    random = Random(241121)
    batches = {size: tuple(random.randbytes(size) for _ in range(64)) for size in (178, 524, 780)}
    for scene in scenes:
        name = scene["id"]
        phases = compile_scene(scene, lexicon, CandidateWeights(training[name], mini))
        print(f"{name} | source SHA-256 {sha256(freeze(name, phases, True)).hexdigest()}")
        report_codecs(name, phases, batches, calibration)


if __name__ == "__main__":
    main()
