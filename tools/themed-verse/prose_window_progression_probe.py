"""Evaluate locally linked window observations before freezing the Debug surface."""

import argparse
from collections import defaultdict, deque
from dataclasses import dataclass
import json
from pathlib import Path
from random import Random
import re
from statistics import median

from prose_capacity_probe import RankedCarrier
from prose_continuity_probe import templates
from prose_grammar_probe import expect_rejection
from prose_ngram import STOP, is_han
from prose_window_compact_probe import BATCH_SIZE, PERIOD, check_boundary, maximum_required
from prose_window_scene_probe import SEEDS, guaranteed, measure_text, report_calibration, scene_definition


CORPUS = Path(__file__).with_name("prose_window_progression_corpus.json")
HOLDOUT_SEED = 317941


@dataclass(frozen=True)
class Pair:
    stage: int
    family: str
    object_name: str
    outcomes: frozenset
    named: frozenset


def continuation_pairs(corpus, lexicon):
    pairs = {}
    terms = set()
    for row in corpus["pairs"]:
        stage, family = row["stage"], row["id"]
        anchors = [word for word in row["objects"] if word in lexicon]
        if stage not in (0, 2) or len(set(anchors)) != len(anchors) or len(anchors) < 4:
            raise ValueError("missing dictionary evidence for a progression family")
        terms.update(anchors)
        openings, _ = templates(row["opens"], anchors, {})
        named, _ = templates(row.get("named", ()), anchors, {})
        across = [word for word in row.get("follow_objects", ()) if word in lexicon]
        across_rows, _ = templates(row.get("follow_forms", ()), across, {})
        terms.update(across)
        distant_views = frozenset(sentence for choices in across_rows.values() for sentence in choices)
        shared = frozenset(row.get("continues", ())) | distant_views
        if len(shared) < 4 or not all(map(is_han, shared)):
            raise ValueError("missing attested progression outcomes")
        for anchor, sentences in openings.items():
            explicit = frozenset(named[anchor])
            pair = Pair(stage, family, anchor, shared | explicit, explicit)
            for sentence in sentences:
                if sentence in pairs:
                    raise ValueError("ambiguous progression opening")
                pairs[sentence] = pair
    return pairs, terms


def alternatives(corpus, lexicon, pairs):
    by_stage = defaultdict(dict)
    terms = set()
    for row in corpus["alternatives"]:
        terms_in_row = [word for word in row["objects"] if word in lexicon]
        if len(terms_in_row) < 4:
            raise ValueError("too little dictionary evidence for a reviewed frame")
        terms.update(terms_in_row)
        rows, _ = templates(row["forms"], terms_in_row, {})
        for options in rows.values():
            by_stage[row["stage"]].update({sentence: score for sentence, score in options.items() if sentence not in pairs})
    return by_stage, terms


def revised_graph(graph, edits):
    phases, routes, endings = graph
    replacements = {term + wrong: term + correct for term in edits["cast_by"]
                    for wrong, correct in edits["predicates"].items()}
    for index in range(2, 3 * PERIOD, 3):
        phases[index] = {replacements.get(text, text): score for text, score in phases[index].items()}
        routes[index] = {replacements.get(text, text): target for text, target in routes[index].items()}
    return phases, routes, endings


def extend_graph(graph, pairs, other_forms, edits):
    graph = revised_graph(graph, edits)
    phases, routes, endings = graph
    ordinary = 3 * PERIOD
    reserved = set().union(*(phase.keys() for phase in phases))
    overlaps = reserved.intersection(pairs)
    if overlaps:
        raise ValueError(f"progression openings collide with existing sentences: {sorted(overlaps)}")
    if any(pair.outcomes & pairs.keys() for pair in pairs.values()):
        raise ValueError("an outcome also starts a separate progression")
    for stage, forms in other_forms.items():
        for source in range(0 if stage == 0 else 3 + stage, ordinary, 3):
            phases[source] = forms | phases[source]
            routes[source] = dict.fromkeys(forms, (source + 1) % ordinary) | routes[source]
    by_stage = defaultdict(lambda: defaultdict(dict))
    for sentence, pair in pairs.items():
        by_stage[pair.stage][pair.outcomes][sentence] = (1, 1)
    for stage, by_outcome in by_stage.items():
        # The first cycle still has the existing mandatory optical observation.
        for source in range(3 + stage, ordinary, 3):
            phases[source] = dict(phases[source])
            for outcomes, sentences in by_outcome.items():
                state = len(phases)
                target = (source + 2) % ordinary
                phases[source].update(sentences)
                routes[source].update(dict.fromkeys(sentences, state))
                phases.append(dict.fromkeys(outcomes, (1, 1)))
                routes.append(dict.fromkeys(outcomes, target))
                endings.append(False)
    return RankedCarrier(phases, destinations=routes, endings=endings)


def state_prefixes(route):
    discovered = {0: ""}
    pending = deque([0])
    while pending:
        state = pending.popleft()
        for _, target, words in route.groups[state]:
            if target not in discovered:
                discovered[target] = discovered[state] + words[0] + STOP
                pending.append(target)
    return discovered


def check_progression(base, route, pairs):
    prefixes = state_prefixes(base)
    checked = set()
    for opening, pair in pairs.items():
        if pair.family in checked:
            continue
        checked.add(pair.family)
        source = 3 + pair.stage
        prefix = prefixes[source] + opening + STOP
        full = prefix + min(pair.outcomes) + STOP
        if route.render_rank(route.recover_rank(full)) != full:
            raise ValueError("a witnessed progression did not round-trip")
        expect_rejection(lambda: route.recover_rank(prefix))
        foreign = min(outcome for other in pairs.values() if other.family != pair.family
                      for outcome in other.outcomes - pair.outcomes)
        expect_rejection(lambda: route.recover_rank(prefix + foreign + STOP))
        if pair.named:
            same_family = next(other for other in pairs.values()
                               if other.family == pair.family and other.object_name != pair.object_name)
            wrong = prefix + min(same_family.named - pair.outcomes) + STOP
            expect_rejection(lambda: route.recover_rank(wrong))
        expect_rejection(lambda: route.recover_rank(prefixes[(source + 1) % (3 * PERIOD)] + opening + STOP))


def progression_routes():
    corpus = json.loads(CORPUS.read_bytes())
    if corpus["format"] != 1:
        raise ValueError("unknown window progression corpus")
    _, _, phases, rules, known_terms, _, lexicon = scene_definition()
    base = rules.bound(phases, every=PERIOD)
    pairs, terms = continuation_pairs(corpus, lexicon)
    forms, new_terms = alternatives(corpus, lexicon, pairs)
    route = extend_graph(rules.graph(phases, every=PERIOD), pairs, forms, corpus["light_edits"])
    check_progression(base, route, pairs)
    check_boundary(route, extend_graph(rules.graph(phases, every=PERIOD), pairs, forms, corpus["light_edits"]))
    return base, route, rules, pairs, known_terms | terms | new_terms


def read_sample(route, rules, packet, pairs, terms, frame_terms):
    text = route.render(packet)
    if route.recover(text) != packet:
        raise ValueError("progression carrier failed individual round trip")
    linked = []
    pieces = text[:-1].split(STOP)
    for index, piece in enumerate(pieces):
        pair = pairs.get(piece)
        if pair is None:
            continue
        if (index % 3 != pair.stage or index + 1 == len(pieces) or pieces[index + 1] not in pair.outcomes):
            raise ValueError("progression lost its object or effect")
        linked.append(pair.family)
    return measure_text(text, rules, terms, frame_terms), len(linked) / max(1, len(pieces) - 1), set(linked), text


def compare(base, route, rules, pairs, terms, examples=False, holdout=False):
    formatter = re.compile("|".join(sorted(terms | set(rules.anchors), key=lambda word: (-len(word), word))))
    if any(new < old for old, new in zip(base.cumulative, route.cumulative, strict=True)):
        raise ValueError("the progression reduced the exact grammar space")
    for name, carrier in (("scene corpus", base), ("progression", route)):
        sentences = set().union(*(row.keys() for row in carrier.words))
        live_rows = len({id(row) for row in carrier.exact if row is not None})
        print(f"{name} | {len(sentences)} candidates | {len(carrier.groups)} states | {live_rows} count rows"
              f" | {guaranteed(carrier)} B guaranteed | {maximum_required(carrier)} B worst 524")
    failures = []
    for seed in (*SEEDS, HOLDOUT_SEED) if holdout else SEEDS:
        rng = Random(seed)
        for size in (178, 524):
            packets = [rng.randbytes(size) for _ in range(BATCH_SIZE)]
            before = [read_sample(base, rules, packet, {}, terms, formatter) for packet in packets]
            after = [read_sample(route, rules, packet, pairs, terms, formatter) for packet in packets]
            changes = {name: (median(getattr(row[0], name) for row in before),
                              median(getattr(row[0], name) for row in after))
                       for name in ("repeat", "frames", "common", "adjacent", "terms", "bytes")}
            continuity = median(row[1] for row in after)
            reached = median(len(row[2]) for row in after)
            print(f"{seed} | {size} B | {changes} | explicit new links {continuity:.1%} | families per carrier {reached:g}")
            if (continuity < .06 or reached < 3 or changes["repeat"][1] > changes["repeat"][0] or
                    changes["frames"][1] > changes["frames"][0] - .02 or changes["common"][1] > changes["common"][0] or
                    changes["adjacent"][1] > changes["adjacent"][0] + .01 or
                    changes["terms"][1] < changes["terms"][0] or changes["bytes"][1] > changes["bytes"][0]):
                failures.append(f"{seed}/{size}")
            if examples:
                for index in (0, 13):
                    offset = 18
                    print(f"sample {seed}/{size}/{index} A " + STOP.join(before[index][3].split(STOP)[offset:offset + 9]))
                    print(f"sample {seed}/{size}/{index} B " + STOP.join(after[index][3].split(STOP)[offset:offset + 9]))
    if failures:
        raise ValueError("progression language or uptake gate: " + ", ".join(failures))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true")
    parser.add_argument("--holdout", action="store_true")
    parser.add_argument("--calibration", type=Path)
    args = parser.parse_args()
    routes = progression_routes()
    compare(*routes, examples=args.examples, holdout=args.holdout)
    if args.calibration:
        report_calibration(*routes[:2], args.calibration)
