"""Measure the corpus-backed window carrier against the frozen first edition."""

import argparse
from bisect import bisect_right
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
from random import Random
import re
from statistics import median

from prose_capacity_probe import calibration_envelopes
from prose_grammar import CandidateWeights
from prose_ngram import STOP, is_han
from prose_window_actions_probe import (
    PERIOD, ObservationRules, action_families, check_action_controls, check_obstruction, observation_fixture,
)
from prose_window_compact_probe import BATCH_SIZE, check_boundary, maximum_required, new_row, scene
from prose_model_probe import CORPUS as TRAINING, MaskedScorer


CORPUS = Path(__file__).with_name("prose_window_scene_corpus.json")
SEEDS = (941127, 372691)


def dictionary_roles(corpus, lexicon):
    roles = {}
    for group in corpus["objects"]:
        allowed = set(group["heads"])
        words = Counter()
        for sentence in group["samples"]:
            # Annotate multi-character spans to avoid borrowing the tail of a longer noun
            # or treating a preposition plus a noun as a new dictionary object.
            fragments = re.split(r"〔([^〕]+)〕", sentence)
            for text in fragments[::2]:
                words.update(text[index:index + 2] for index in range(len(text) - 1)
                             if text[index + 1] in allowed and text[index:index + 2] in lexicon)
            for word in fragments[1::2]:
                if word not in lexicon or word[-1] not in allowed or not is_han(word):
                    raise ValueError("annotated object requires locked dictionary evidence")
                words[word] += 1
        words = Counter({word: count for word, count in words.items() if is_han(word)})
        if len(words) < 4 or group["id"] in roles:
            raise ValueError("the scene corpus has insufficient or ambiguous object evidence")
        roles[group["id"]] = words
    return roles


def add_attested_frames(model, corpus, lexicon, weights):
    terms = set()
    exclusions = tuple(corpus["exclude_prefixes"])
    output = [{phrase: score for phrase, score in row.items() if not phrase.startswith(exclusions)} for row in model]
    for group in corpus["phases"]:
        rows = [tuple(tokens) for tokens in group["samples"]]
        slot = ("subject", "object").index(group["position"])
        allowed = set(group["heads"])
        words = sorted({row[slot] for row in rows if row[slot][-1] in allowed and row[slot] in lexicon})
        forms = sorted({row[1 - slot] for row in rows})
        fixed = set(group.get("fixed_forms", ()))
        if not fixed <= set(forms):
            raise ValueError("an attested-only phrase form has no source sentence")
        if len(words) < 2 or not all(map(is_han, (*words, *forms))):
            raise ValueError("the scene corpus has an empty or invalid phrase class")
        terms.update(words)
        stage = output[group["phase"]]
        for form in forms:
            candidates = [word for word in words if form not in fixed or (form, word) in rows]
            bonuses = {}
            if slot == 1:
                for offset in range(0, len(candidates), 8):
                    bonuses.update(weights.bonuses((form,), tuple((word,) for word in candidates[offset:offset + 8])))
            for word in candidates:
                tokens = (form, word) if slot == 1 else (word, form)
                phrase = "".join(tokens)
                observed = weights.occurrence((tokens[0],), tokens[1])
                stage.setdefault(phrase, (1 + observed, 1 + observed + bonuses.get(word, 0)))
    return output, terms


def scene_definition():
    corpus = json.loads(CORPUS.read_bytes())
    if corpus["format"] != 1:
        raise ValueError("unknown window scene corpus")
    scorer = MaskedScorer("mini-int8")
    mini = CandidateWeights(scene(TRAINING, "scenes")["training"], scorer)
    spec, lexicon, model, previous = observation_fixture(mini)
    original_families = action_families(spec, refined=True)
    original_rules = ObservationRules(model, previous, original_families, lexicon)
    weights = CandidateWeights([" ".join(row) for group in corpus["phases"] for row in group["samples"]], scorer)
    expanded, phase_terms = add_attested_frames(model, corpus, lexicon, weights)
    roles = dictionary_roles(corpus, lexicon)
    attested = set().union(*(set(words) for words in roles.values()))
    # A frame can hide a discrete object. Open water and terrain remain observations
    # without promising that a shift will reveal their whole extent.
    frame_objects = {word for role in ("buildings", "water-spans") for word in roles[role]
                     if word[-1] in "楼塔亭檐顶台桥洞墩坝"}
    anchors = previous["anchors"] + sorted(attested - set(previous["anchors"]))
    previous = previous | {"anchors": anchors}
    families = []
    for family in original_families:
        identifier = family["id"]
        eligible = attested if identifier != "window-frame" else frame_objects
        families.append(family | {
            "anchors": family["anchors"] + sorted(eligible - set(family["anchors"])),
            "leads": family["leads"] + corpus["action_leads"][identifier],
            "shared": family["shared"] + corpus["followups"][identifier],
        })
    rules = ObservationRules(expanded, previous, families, lexicon)
    return model, original_rules, expanded, rules, attested | phase_terms, roles, lexicon


def scene_routes():
    model, original_rules, expanded, rules, terms, roles, _ = scene_definition()
    base = original_rules.bound(model, every=PERIOD)
    route = rules.bound(expanded, every=PERIOD)
    check_action_controls(route, rules, expanded)
    check_obstruction(route, rules, expanded)
    check_boundary(route, rules.bound(expanded, every=PERIOD))
    return base, original_rules, route, rules, terms, roles


@dataclass(frozen=True)
class Measures:
    repeat: float
    common: int
    adjacent: float
    frames: float
    terms: int
    bytes: int


def measure(route, rules, packet, dictionary_terms, frame_terms):
    text = route.render(packet)
    if route.recover(text) != packet:
        raise ValueError("exact packet recovery failed")
    return measure_text(text, rules, dictionary_terms, frame_terms)


def measure_text(text, rules, dictionary_terms, frame_terms):
    row = new_row(text, rules)
    pieces = text[:-1].split(STOP)
    adjacent = [{sentence[i:i+2] for i in range(len(sentence) - 1)} for sentence in pieces]
    overlaps = sum(bool(left & right) for left, right in zip(adjacent, adjacent[1:]))
    frames = 1 - len({frame_terms.sub("○", sentence) for sentence in pieces}) / len(pieces)
    return Measures(row.repeats, max(Counter(pieces).values()), overlaps / max(1, len(pieces) - 1),
                    frames, sum(word in text for word in dictionary_terms), len(text.encode()))


def guaranteed(route):
    return ((route.cumulative[-1] + 1).bit_length() - 2) // 8


def evaluate(base, original_rules, route, rules, terms, roles):
    frame_terms = re.compile("|".join(sorted(terms | set(original_rules.anchors), key=lambda word: (-len(word), word))))
    for role, words in roles.items():
        print(f"{role} | {len(words)} terms | {' '.join(sorted(words))}")
    for label, carrier in (("baseline", base), ("scene corpus", route)):
        choices = {sentence for row in carrier.words for sentence in row}
        print(f"{label} | {len(choices)} unique sentences | {len(carrier.groups)} states"
              f" | guarantee {guaranteed(carrier)} B | 524 B max {maximum_required(carrier)} B")
    if (any(old > new for old, new in zip(base.cumulative, route.cumulative, strict=True)) or
            guaranteed(route) < guaranteed(base) or maximum_required(route) > maximum_required(base)):
        raise ValueError("the expanded observation lost capacity")
    for seed in SEEDS:
        rng = Random(seed)
        for size in (178, 524):
            packets = [rng.randbytes(size) for _ in range(BATCH_SIZE)]
            old = [measure(base, original_rules, packet, terms, frame_terms) for packet in packets]
            new = [measure(route, rules, packet, terms, frame_terms) for packet in packets]
            scores = {name: (median(getattr(row, name) for row in old), median(getattr(row, name) for row in new))
                      for name in Measures.__dataclass_fields__}
            print(f"{seed} | {size} B | {scores}")
            if (scores["repeat"][1] > scores["repeat"][0] * .8 or scores["common"][1] > scores["common"][0] or
                    scores["adjacent"][1] > scores["adjacent"][0] or scores["frames"][1] > scores["frames"][0] or
                    scores["terms"][1] < scores["terms"][0] + 5 or
                    scores["bytes"][1] > scores["bytes"][0]):
                raise ValueError("a paired language, vocabulary or length gate failed")


def report_calibration(base, route, folder):
    entries = calibration_envelopes(folder)
    previous = sum(size <= guaranteed(base) for _, size in entries)
    fits = [(original, size) for original, size in entries if size <= guaranteed(route)]
    target = sum(bisect_right(route.cumulative, (1 << (8 * size + 1)) - 2) * 3 <= original * 5
                 for original, size in fits)
    print(f"locked calibration | guaranteed {previous} → {len(fits)}/{len(entries)}"
          f" | at least 20% utilization {target}/{len(entries)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calibration", type=Path, help="locked P2 training and calibration archives")
    args = parser.parse_args()
    routes = scene_routes()
    evaluate(*routes)
    if args.calibration is not None:
        report_calibration(routes[0], routes[2], args.calibration)
