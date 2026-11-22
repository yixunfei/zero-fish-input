"""Measure lexical repairs and explicit object continuity in the public prose grammar."""

import argparse
from bisect import bisect_right
from collections import Counter
from contextlib import redirect_stdout
from hashlib import sha256
from io import StringIO
import json
from pathlib import Path
from random import Random

from prose_capacity_probe import RankedCarrier, bound, calibration_envelopes, check_packets, check_ranks, report_calibration_bound
from prose_grammar import CandidateWeights, compile_scene, freeze
from prose_grammar_probe import CORPUS, expect_rejection
from prose_model_probe import CORPUS as TRAINING, MaskedScorer
from prose_ngram import CARRIER_BYTES, CapacityError, STOP, is_han
from prose_short_branch_probe import ADDITIONS, BUDGETS, expand, summarize
from surface_lexicon_probe import locked_faces


RULES = Path(__file__).with_name("prose_continuity_corpus.json")


def check_state_counter():
    patterns = ("\u7532\u3002\u96e8\u3002", "\u4e59\u3002\u96ea\u3002")
    surfaces = [{"\u7532": (1, 1), "\u4e59": (1, 1)}, {"\u96e8": (1, 1)}, {"\u96ea": (1, 1)}]
    jumps = [{"\u7532": 1, "\u4e59": 2}, {"\u96e8": 0}, {"\u96ea": 0}]
    route = RankedCarrier(surfaces, limit=8, destinations=jumps, endings=[True, False, False])
    expected = set(patterns) | {left + right for left in patterns for right in patterns}
    actual = [route.render_rank(rank) for rank in range(route.cumulative[-1])]
    if len(actual) != len(expected) or set(actual) != expected:
        raise ValueError("stateful rank count differs from literal enumeration")
    if any(route.recover_rank(text) != index for index, text in enumerate(actual)):
        raise ValueError("stateful toy rank did not round-trip")


def repair(phases, edits):
    rules = [(old, new, scope == ["suffix"]) for old, new, *scope in edits]
    changes = Counter()
    repaired = []
    for phase in phases:
        updated = {}
        for sentence, weights in phase.items():
            edited = sentence
            for old, new, suffix in rules:
                if suffix and edited.endswith(old):
                    edited = edited[:-len(old)] + new
                    changes[old] += 1
                elif not suffix and old in edited:
                    edited = edited.replace(old, new)
                    changes[old] += 1
            previous = updated.get(edited, (0, 0))
            updated[edited] = tuple(a + b for a, b in zip(previous, weights, strict=True))
        repaired.append(updated)
    expected = {old for old, _, _ in rules}
    if changes.keys() != expected:
        raise ValueError(f"unused collocation repairs: {expected - changes.keys()}")
    if any((text.endswith(old) if suffix else old in text)
           for phase in repaired for text in phase for old, _, suffix in rules):
        raise ValueError("a repaired rule still includes its old form")
    return repaired, changes


def templates(forms, anchors, available):
    output, labels = {}, {}
    for anchor in anchors:
        row = {}
        for form in forms:
            if form.count("{item}") != 1:
                raise ValueError("an object template must name exactly one object")
            sentence = form.format(item=anchor)
            if sentence in labels or not is_han(sentence):
                raise ValueError("ambiguous or non-Han object template")
            labels[sentence] = anchor
            row[sentence] = available.get(sentence, (1, 1))
        output[anchor] = row
    return output, labels


class LinkRules:
    def __init__(self, model, spec, lexicon):
        self.lead = spec["lead"]
        self.follow = self.lead + 1
        if self.lead not in (0, 1):
            raise ValueError("expected two adjacent phases in a three-sentence cycle")
        self.anchors = tuple(spec["anchors"])
        if len(set(self.anchors)) != len(self.anchors) or len(self.anchors) < 2 or not set(self.anchors) <= lexicon:
            raise ValueError("unavailable or ambiguous anchor words")
        self.intros, self.start_words = templates(spec["lead_forms"], self.anchors, model[self.lead])
        self.named, self.follow_words = templates(spec["follow_forms"], self.anchors, model[self.follow])
        self.shared = {sentence: model[self.follow].get(sentence, (1, 1)) for sentence in spec["shared"]}
        if (not self.shared or not all(map(is_han, self.shared)) or
                self.shared.keys() & self.follow_words.keys() or self.shared.keys() & model[self.follow].keys()):
            raise ValueError("context-only follow-up has an ambiguous public interpretation")

    def intro_row(self):
        return {sentence: weight for row in self.intros.values() for sentence, weight in row.items()}

    def follow_row(self):
        return {sentence: weight for row in self.named.values() for sentence, weight in row.items()} | self.shared

    def control(self, model):
        phases = list(model)
        phases[self.lead] = self.intro_row()
        phases[self.follow] = self.follow_row()
        endings = [phase != self.follow for phase in range(len(phases))]
        return RankedCarrier(phases, endings=endings)

    def graph(self, model, every=None):
        cycles = every or 1
        phases = list(model) * cycles
        ordinary_states = len(phases)
        leads = self.intro_row()
        lead_states = list(range(self.lead, ordinary_states, len(model)))
        for index in lead_states:
            phases[index] = leads if every and index == self.lead else phases[index] | leads
        routes = [{word: (index + 1) % ordinary_states for word in row} for index, row in enumerate(phases)]
        endings = [True] * len(phases)
        for index in lead_states:
            self.bind_followups(phases, routes, endings, index, (index + 2) % ordinary_states)
        return phases, routes, endings

    def bound(self, model, every=None):
        phases, routes, endings = self.graph(model, every=every)
        return RankedCarrier(phases, destinations=routes, endings=endings)

    def bind_followups(self, phases, routes, endings, lead, next_phase):
        for anchor in self.anchors:
            state = len(phases)
            for sentence in self.intros[anchor]:
                routes[lead][sentence] = state
            row = self.named[anchor] | self.shared
            phases.append(row)
            routes.append(dict.fromkeys(row, next_phase))
            endings.append(False)

    def matches(self, anchor, following):
        return self.follow_words.get(following) == anchor or following in self.shared

    def accepts_pair(self, introduction, following):
        anchor = self.start_words.get(introduction)
        return anchor is not None and self.matches(anchor, following)

    def inspect(self, pieces):
        slots, intros, linked, foreign, unfinished = 0, 0, 0, 0, 0
        for index in range(self.lead, len(pieces), 3):
            slots += 1
            anchor = self.start_words.get(pieces[index])
            if anchor is None:
                continue
            intros += 1
            if index + 1 == len(pieces):
                unfinished += 1
                continue
            following = pieces[index + 1]
            named = self.follow_words.get(following)
            linked += self.matches(anchor, following)
            foreign += named is not None and named != anchor
        return slots, intros, linked, foreign, unfinished


def check_links(model, rules, free, bound_route, selective):
    first, other = rules.anchors[:2]
    intro = next(iter(rules.intros[first])) + STOP
    same = next(iter(rules.named[first])) + STOP
    different = next(iter(rules.named[other])) + STOP
    reference = next(iter(rules.shared)) + STOP
    prefix = next(iter(model[0])) + STOP if rules.lead else ""
    for route in (bound_route, selective):
        check_packets(route)
        for tail in (same, reference):
            accepted = prefix + intro + tail
            rank = route.recover_rank(accepted)
            if route.render_rank(rank) != accepted:
                raise ValueError("same-object or contextual follow-up did not round-trip")
        expect_rejection(lambda: route.recover_rank(prefix + intro + different))
        expect_rejection(lambda: route.recover_rank(prefix + intro))
    for tail in (same, different):
        text = prefix + intro + tail
        if free.render_rank(free.recover_rank(text)) != text:
            raise ValueError("unbound control discarded a valid comparison path")


def replay(scene, label, route, rules, batch, size, examples, period=None):
    rows, totals, excerpts = {}, [0, 0, 0, 0, 0], []
    for index, packet in enumerate(batch):
        try:
            text = route.render(packet)
        except CapacityError:
            continue
        if route.recover(text) != packet:
            raise ValueError("continuity probe did not recover the packet")
        pieces = text[:-1].split(STOP)
        rows[index] = len(text.encode()), 1 - len(set(pieces)) / len(pieces)
        totals = [a + b for a, b in zip(totals, rules.inspect(pieces), strict=True)]
        if period is not None:
            for slot in range(rules.lead, len(pieces), 3 * period):
                if (slot + 1 == len(pieces) or pieces[slot] not in rules.start_words or
                        not rules.matches(rules.start_words[pieces[slot]], pieces[slot + 1])):
                    raise ValueError("periodic grammar missed a required link")
        if examples and size == 178 and index in (0, 13, 37):
            excerpts.append((index, STOP.join(pieces[:3]) + STOP))
    slots, intros, linked, foreign, unfinished = totals
    if label == "strict" and not (slots == intros == linked and not (foreign or unfinished)):
        raise ValueError("strict grammar emitted an unbound continuation")
    if (label == "selective" or period is not None) and not (intros == linked and not (foreign or unfinished)):
        raise ValueError("selective grammar emitted a continuation without its object")
    print(f"{scene} | {label} | {size} B | {summarize(rows, len(batch))}"
          f" | linked/intro/slots {linked}/{intros}/{slots}, wrong named object {foreign}, unfinished {unfinished}")
    for index, text in excerpts:
        print(f"sample {scene}/{label}/{index} {text}")


def choose_period(name, grammar, model, strict):
    capacities = [(1, bound(strict, CARRIER_BYTES)[1])]
    if capacities[0][1] >= 524:
        return 1, strict
    for period in range(2, 6):
        candidate = grammar.bound(model, every=period)
        capacity = bound(candidate, CARRIER_BYTES)[1]
        capacities.append((period, capacity))
        if capacity >= 524:
            print(f"{name} | period sweep {capacities} | first guaranteed 524 B at {period}")
            return period, candidate
    print(f"{name} | period sweep {capacities} | no guaranteed 524 B")
    return None, None


def run_scene(name, original, repair_spec, lexicon, batches, calibration, examples):
    revised, changes = repair(original, repair_spec["edits"])
    grammar = LinkRules(revised, repair_spec, lexicon)
    free = grammar.control(revised)
    strict = grammar.bound(revised, every=1)
    selective = grammar.bound(revised)
    check_links(revised, grammar, free, strict, selective)
    if any(a > b for a, b in zip(strict.cumulative, free.cumulative, strict=True)):
        raise ValueError("link enforcement exceeded the unconstrained grammatical space")
    print(f"{name} | repaired {sum(changes.values())} occurrences, unique phase counts"
          f" {[len(phase) for phase in original]} -> {[len(phase) for phase in revised]}"
          f" | anchored topics {len(grammar.anchors)}")
    print(f"{name} | source SHA-256 {sha256(freeze(name, original, True)).hexdigest()}")
    arms = {"baseline": RankedCarrier(original), "repaired": RankedCarrier(revised), "unbound": free,
            "strict": strict, "selective": selective}
    period, periodic = choose_period(name, grammar, revised, strict)
    if periodic is not None:
        arms["periodic"] = periodic
    for label, route in arms.items():
        budgets = [bound(route, limit) for limit in BUDGETS]
        needed = bisect_right(route.cumulative, (1 << (8 * 524 + 1)) - 2)
        limit524 = needed * 3 if needed < len(route.cumulative) else None
        print(f"{name} | {label} | exact bits/guaranteed framed B {budgets} | 524 B budget {limit524}")
        if calibration:
            report_calibration_bound(name, label, route, calibration)
        for size, packet_batch in batches.items():
            replay(name, label, route, grammar, packet_batch, size, examples,
                   period=period if label == "periodic" else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true")
    parser.add_argument("--calibration", type=Path)
    args = parser.parse_args()
    check_ranks()
    check_state_counter()
    spec = json.loads(RULES.read_bytes())
    if spec["format"] != 1:
        raise ValueError("unexpected continuity fixture version")
    repairs = {scene["id"]: scene for scene in spec["scenes"]}
    additions = {scene["id"]: scene["frames"] for scene in json.loads(ADDITIONS.read_bytes())["scenes"]}
    scenes = json.loads(CORPUS.read_bytes())["scenes"]
    training = {scene["id"]: scene["training"] for scene in json.loads(TRAINING.read_bytes())["scenes"]}
    calibration = calibration_envelopes(args.calibration) if args.calibration else []
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    mini = MaskedScorer("mini-int8")
    random = Random(241121)
    batches = {size: tuple(random.randbytes(size) for _ in range(64)) for size in (178, 524, 780)}
    for scene in scenes:
        name = scene["id"]
        weights = CandidateWeights(training[name], mini)
        initial = compile_scene(scene, lexicon, weights)
        with redirect_stdout(StringIO()):
            original = expand(name, initial, additions[name], lexicon, weights, max_units=10)
        run_scene(name, original, repairs[name], lexicon, batches, calibration, args.examples)


if __name__ == "__main__":
    main()
