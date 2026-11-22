"""Guardrail A/B on compact window clauses with the previous continuity schedule."""

import argparse
from bisect import bisect_right
from contextlib import redirect_stdout
from dataclasses import dataclass
from io import StringIO
import json
from math import ceil
from pathlib import Path
from random import Random
from statistics import median

from prose_capacity_probe import bound, check_packets
from prose_continuity_probe import LinkRules, RULES, repair
from prose_grammar import CandidateWeights, compile_scene
from prose_grammar_probe import CORPUS, expect_rejection
from prose_model_probe import CORPUS as TRAINING, MaskedScorer
from prose_ngram import CARRIER_BYTES, CapacityError, STOP
from prose_short_branch_probe import ADDITIONS, expand
from surface_lexicon_probe import locked_faces


SPEC = Path(__file__).with_name("prose_window_compact.json")
SCENE = "observation"
PERIOD = 5
SEEDS = (241121, 20261121)
REVIEWED_SEEDS = (621572, 340829)
BATCH_SIZE = 64
MIN_SAVINGS = 256
SAMPLES = ((0, 178, 0, 0), (0, 524, 13, 5), (0, 524, 37, 30),
           (1, 178, 13, 5), (1, 524, 0, 10), (1, 524, 37, 40))


@dataclass(frozen=True)
class Row:
    length: int
    repeats: float
    linked_repeats: float
    linked: int
    slots: int


def scene(file, group):
    return next(entry for entry in json.loads(file.read_bytes())[group] if entry["id"] == SCENE)


def constraints(spec, additions, model, lexicon, include_leads):
    keys = ("follow_forms", "shared", "lead_forms") if include_leads else ("follow_forms", "shared")
    augmented = spec | {key: spec[key] + additions[key] for key in keys}
    return LinkRules(model, augmented, lexicon)


def check_controls(rules, route, model):
    check_packets(route)
    first, other = rules.anchors[:2]
    leading = next(iter(model[0])) + STOP + next(iter(rules.intros[first])) + STOP
    named = next(iter(rules.named[first])) + STOP
    reference = next(iter(rules.shared)) + STOP
    for suffix in (named, reference):
        text = leading + suffix
        if route.render_rank(route.recover_rank(text)) != text:
            raise ValueError("observation control did not keep its object")
    expect_rejection(lambda: route.recover_rank(leading))
    expect_rejection(lambda: route.recover_rank(leading + next(iter(rules.named[other])) + STOP))


def check_boundary(sender, receiver):
    capacity = bound(sender, CARRIER_BYTES)[1]
    for byte in (0, 255):
        packet = bytes([byte]) * capacity
        text = sender.render(packet)
        if receiver.recover(text) != packet:
            raise ValueError("guaranteed capacity boundary did not round-trip")
    try:
        sender.render(b"\xff" * (capacity + 1))
    except CapacityError:
        return
    raise ValueError("the first unsupported worst-case packet unexpectedly fits")


def repeated(values):
    return 1 - len(set(values)) / len(values)


def new_row(text, rules):
    sentences = text[:-1].split(STOP)
    slots, introduced, linked, foreign, incomplete = rules.inspect(sentences)
    if foreign or incomplete or introduced != linked:
        raise ValueError("an active observation lost its object")
    compact = []
    for index in range(rules.lead, len(sentences), 3 * PERIOD):
        if index + 1 >= len(sentences) or not rules.accepts_pair(sentences[index], sentences[index + 1]):
            raise ValueError("the fixed continuity schedule was broken")
        compact.append(sentences[index + 1])
    return Row(len(text.encode()), repeated(sentences), repeated(compact), linked, slots)


def replay(model, rules, batches, examples, on_decoded=None):
    sender, receiver = (rules.bound(model, every=PERIOD) for _ in range(2))
    check_controls(rules, receiver, model)
    check_boundary(sender, receiver)
    by_batch, excerpts = {}, []
    for (suite, size), packets in batches.items():
        rows = {}
        for index, packet in enumerate(packets):
            try:
                text = sender.render(packet)
            except CapacityError:
                continue
            if receiver.recover(text) != packet:
                raise ValueError("observation carrier did not recover its exact packet")
            rows[index] = new_row(text, rules)
            if on_decoded is not None:
                on_decoded(suite, size, index, text)
            for chosen_suite, chosen_size, chosen_index, cycle in SAMPLES if examples else ():
                if (suite, size, index) == (chosen_suite, chosen_size, chosen_index):
                    sentences = text[:-1].split(STOP)[3 * cycle:3 * cycle + 3]
                    if len(sentences) != 3:
                        raise ValueError("selected comparison excerpt is missing")
                    excerpts.append((suite, size, index, cycle, STOP.join(sentences) + STOP))
        by_batch[suite, size] = rows
    return sender, by_batch, excerpts


def statistics(rows):
    lengths = sorted(row.length for row in rows.values())
    return (median(lengths), lengths[ceil(0.95 * len(lengths)) - 1], lengths[-1],
            median(row.repeats for row in rows.values()),
            median(row.linked_repeats for row in rows.values()),
            sum(row.linked for row in rows.values()) / sum(row.slots for row in rows.values()))


def maximum_required(route):
    size = bisect_right(route.cumulative, (1 << (524 * 8 + 1)) - 2)
    return size * 3 if size < len(route.cumulative) else None


def gates(base_route, route, base, observation):
    reasons = []
    if any(b < a for a, b in zip(base_route.cumulative, route.cumulative, strict=True)):
        reasons.append("exact-grammar-space")
    target, old = maximum_required(route), maximum_required(base_route)
    if target is None or old - target < MIN_SAVINGS:
        reasons.append("guaranteed-budget")
    for (suite, size), rows in observation.items():
        original = base[suite, size]
        label = f"seed{suite}/{size}"
        if len(rows) != BATCH_SIZE or rows.keys() != original.keys():
            reasons.append(f"{label}/round-trips")
            continue
        base_metrics, metrics = statistics(original), statistics(rows)
        delta = median(rows[i].length - original[i].length for i in rows)
        if (size == 524 and delta > -MIN_SAVINGS) or any(b > a for a, b in zip(base_metrics[:3], metrics[:3])):
            reasons.append(f"{label}/length")
        if metrics[3] > base_metrics[3]:
            reasons.append(f"{label}/full-sentence-repeat")
        if metrics[4] > base_metrics[4]:
            reasons.append(f"{label}/linked-sentence-repeat")
        if metrics[5] < base_metrics[5]:
            reasons.append(f"{label}/continuity-coverage")
    return reasons


def report(label, route, results, excerpts, baseline=None):
    print(f"{label} | worst 524 B {maximum_required(route)} B | hard-limit capacity {bound(route, CARRIER_BYTES)[1]} B")
    for (suite, size), values in results.items():
        if len(values) != BATCH_SIZE:
            print(f"{label} | seed{suite} | {size} B | {len(values)}/{BATCH_SIZE} passed")
            continue
        middle, tail, maximum, repeat, linked_repeat, coverage = statistics(values)
        delta = median(values[i].length - baseline[suite, size][i].length for i in values) if baseline else 0
        print(f"{label} | seed{suite} | {size} B | {BATCH_SIZE}/{BATCH_SIZE}"
              f" | median/p95/max {middle:.0f}/{tail}/{maximum} B | paired delta {delta:+.0f} B"
              f" | repeat {repeat:.4%} | linked repeat {linked_repeat:.4%} | coverage {coverage:.4%}")
    for suite, size, index, cycle, text in excerpts:
        print(f"sample {label}/{suite}/{size}/{index}/{cycle} {text}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true")
    parser.add_argument("--reviewed", action="store_true", help="evaluate the language revisions on fresh seeds")
    args = parser.parse_args()
    additions = json.loads(SPEC.read_bytes())
    if (additions["format"], additions["scene"], additions["period"]) != (1, SCENE, PERIOD):
        raise ValueError("unexpected compact clause fixture")
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    training = scene(TRAINING, "scenes")["training"]
    mini = CandidateWeights(training, MaskedScorer("mini-int8"))
    original = compile_scene(scene(CORPUS, "scenes"), lexicon, mini)
    with redirect_stdout(StringIO()):
        expanded = expand(SCENE, original, scene(ADDITIONS, "scenes")["frames"], lexicon, mini, max_units=10)
    source = scene(RULES, "scenes")
    model, _ = repair(expanded, source["edits"])
    batches = {}
    for suite, seed in enumerate(REVIEWED_SEEDS if args.reviewed else SEEDS):
        random = Random(seed)
        for size in (178, 524):
            batches[suite, size] = [random.randbytes(size) for _ in range(BATCH_SIZE)]
    baseline_route, baseline, baseline_examples = replay(model, LinkRules(model, source, lexicon), batches, args.examples)
    if any(len(values) != BATCH_SIZE for values in baseline.values()):
        raise ValueError("baseline no longer meets the fixed A/B reference")
    report("baseline", baseline_route, baseline, baseline_examples)
    variants = (("reviewed", additions["polished"], True),) if args.reviewed else (
        ("compact-follow", additions, False), ("compact-pair", additions, True))
    for label, phrases, include_leads in variants:
        rules = constraints(source, phrases, model, lexicon, include_leads)
        route, results, excerpts = replay(model, rules, batches, args.examples)
        report(label, route, results, excerpts, baseline)
        reasons = gates(baseline_route, route, baseline, results)
        print(f"{label} | quantitative gate | {', '.join(reasons) if reasons else 'pass, language review pending'}")


if __name__ == "__main__":
    main()
