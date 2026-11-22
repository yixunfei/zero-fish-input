"""Measure the marginal capacity of short, independently decodable prose clauses."""

import argparse
from bisect import bisect_right
from hashlib import sha256
import json
from math import ceil
from pathlib import Path
from random import Random
from statistics import median

from prose_capacity_probe import RankedCarrier, bound, calibration_envelopes, check_packets, report_calibration_bound
from prose_grammar import CandidateWeights, GrammarCarrier, MAX_PHASE_CHOICES, compile_phase, compile_scene, freeze
from prose_grammar_probe import CORPUS
from prose_model_probe import CORPUS as TRAINING, MaskedScorer
from prose_ngram import CARRIER_BYTES, CapacityError, STOP
from surface_lexicon_probe import locked_faces


ADDITIONS = Path(__file__).with_name("prose_short_branches.json")
BUDGETS = (5120, 10240, CARRIER_BYTES)
SAMPLE_INDICES = (0, 13, 37)
ROLE_CONTROLS = {
    "outing": (0, "先带上雨伞", "先带上地址"),
    "observation": (0, "窗边可见栏杆", "窗边可见路线"),
    "organizing": (1, "再把剪刀移到手边", "再把天气移到手边"),
}


def capacity_bits(phases):
    return bound(RankedCarrier(phases, limit=BUDGETS[0] // 3), BUDGETS[0])[0]


def expand(scene_id, baseline, spec, lexicon, weights, max_units):
    expanded = [phase.copy() for phase in baseline]
    prior_bits = capacity_bits(expanded)
    seen_rules = set()
    for frame in spec:
        rule_id, phase = frame["id"], frame["stage"]
        if rule_id in seen_rules or not 0 <= phase < len(expanded):
            raise ValueError("duplicate rule ID or invalid phase")
        seen_rules.add(rule_id)
        choices = compile_phase([frame["parts"]], lexicon, weights)
        short = {text: score for text, score in choices.items() if len(text) + 1 <= max_units}
        if not short:
            raise ValueError(f"{rule_id} has no sentence within the length budget")
        overlaps = short.keys() & expanded[phase].keys()
        if overlaps:
            raise ValueError(f"{scene_id}/{rule_id} overlaps an existing clause: {min(overlaps)}")
        expanded[phase].update(short)
        if len(expanded[phase]) > MAX_PHASE_CHOICES:
            raise ValueError("expanded phase exceeds the existing budget")
        bits = capacity_bits(expanded)
        if bits <= prior_bits:
            raise ValueError(f"{rule_id} added no whole bit at the working budget")
        print(f"{scene_id} | {rule_id} | phase {phase + 1} | added {len(short)}"
              f" | length rejected {len(choices) - len(short)} | incremental bits {bits - prior_bits}")
        prior_bits = bits
    for before, after in zip(baseline, expanded, strict=True):
        if any(after.get(word) != frequency for word, frequency in before.items()):
            raise ValueError("a baseline sentence or weight was lost")
    return expanded


def compare_capacity(scene, base, expanded, before, after):
    if any(a > b for a, b in zip(before.cumulative, after.cumulative, strict=True)):
        raise ValueError("add-only grammar lost capacity")
    phase, positive, negative = ROLE_CONTROLS[scene]
    if positive in base[phase] or positive not in expanded[phase] or negative in expanded[phase]:
        raise ValueError("new grammar did not enforce the expected lexical roles")
    for label, columns, route in (("base", base, before), ("expanded", expanded, after)):
        thresholds = [bound(route, length) for length in BUDGETS]
        rank = (1 << (524 * 8 + 1)) - 2
        required = bisect_right(route.cumulative, rank)
        shortest = required * 3 if required < len(route.cumulative) else None
        print(f"{scene} | {label} | choices {[len(phase) for phase in columns]}"
              f" | exact bits and guaranteed framed bytes {thresholds} | 524 B worst-rank budget {shortest}")


def test_packets(sender, receiver, batch, samples):
    rows, excerpts = {}, []
    for index, packet in enumerate(batch):
        try:
            text = sender.render(packet)
        except CapacityError:
            continue
        if receiver.recover(text) != packet:
            raise ValueError("expanded grammar round trip failed")
        pieces = text[:-1].split(STOP)
        rows[index] = len(text.encode()), 1 - len(set(pieces)) / len(pieces)
        if samples and index in SAMPLE_INDICES:
            excerpts.append((index, STOP.join(pieces[:3]) + STOP))
    return rows, excerpts


def summarize(rows, total):
    if not rows:
        return f"0/{total}, no length statistics"
    lengths = sorted(length for length, _ in rows.values())
    repeats = median(repeat for _, repeat in rows.values())
    p95 = lengths[ceil(0.95 * len(lengths)) - 1]
    return f"{len(rows)}/{total}, median {median(lengths):.0f} B, p95 {p95} B, repeat {repeats:.1%}"


def replay(scene, kind, original, expanded, batches, examples):
    for size, packets in batches.items():
        outcomes = [test_packets(sender, receiver, packets, examples and size == 178)
                    for sender, receiver in (original, expanded)]
        left, right = (item[0] for item in outcomes)
        common = sorted(left.keys() & right.keys())
        deltas = [right[index][0] - left[index][0] for index in common]
        paired = f", paired median change {median(deltas):+.0f} B over {len(common)}" if common else ""
        print(f"{scene} | {kind} | {size} B | A {summarize(left, len(packets))}"
              f" | B {summarize(right, len(packets))}{paired}")
        for label, (_, excerpts) in zip(("A", "B"), outcomes, strict=True):
            for index, excerpt in excerpts:
                print(f"sample {scene}/{kind}/{label}/{index} {excerpt}")


def compare(scene, spec, weights, lexicon, max_units, batches, calibration, examples):
    name = scene["id"]
    original = compile_scene(scene, lexicon, weights)
    expanded = expand(name, original, spec, lexicon, weights, max_units)
    before, after = RankedCarrier(original), RankedCarrier(expanded)
    compare_capacity(name, original, expanded, before, after)
    check_packets(after)
    if calibration:
        report_calibration_bound(name, "base", before, calibration)
        report_calibration_bound(name, "expanded", after, calibration)
    replay(name, "rank", (before, RankedCarrier(original)), (after, RankedCarrier(expanded)), batches, examples)
    a, b = freeze(name, original, True), freeze(name, expanded, True)
    print(f"{name} | frozen A/B SHA-256 {sha256(a).hexdigest()} / {sha256(b).hexdigest()}")
    replay(name, "huffman", (GrammarCarrier(a), GrammarCarrier(a)), (GrammarCarrier(b), GrammarCarrier(b)),
           batches, examples)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true", help="show preselected public three-sentence excerpts")
    parser.add_argument("--calibration", type=Path, help="optional locked training/calibration archive folder")
    args = parser.parse_args()
    spec = json.loads(ADDITIONS.read_bytes())
    if spec["format"] != 1 or spec["max_units"] != 10:
        raise ValueError("unexpected short clause fixture version or length")
    added = {scene["id"]: scene["frames"] for scene in spec["scenes"]}
    scenes = json.loads(CORPUS.read_bytes())["scenes"]
    training = {scene["id"]: scene["training"] for scene in json.loads(TRAINING.read_bytes())["scenes"]}
    if set(added) != set(training) or len(spec["scenes"]) != len(added):
        raise ValueError("short clauses must cover the three existing scenes once")
    calibration = calibration_envelopes(args.calibration) if args.calibration else []
    if calibration:
        print(f"public calibration | {len(calibration)} bodies | smallest wrapped packet"
              f" {min(size for _, size in calibration)} B")
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    mini = MaskedScorer("mini-int8")
    random = Random(241121)
    batches = {size: tuple(random.randbytes(size) for _ in range(64)) for size in (178, 524, 780)}
    for scene in scenes:
        name = scene["id"]
        weights = CandidateWeights(training[name], mini)
        compare(scene, added[name], weights, lexicon, spec["max_units"], batches, calibration, args.examples)


if __name__ == "__main__":
    main()
