"""Validate concrete observation actions with the same window-carrier guardrails."""

import argparse
from collections import Counter, defaultdict
from contextlib import redirect_stdout
from io import StringIO
import json
from pathlib import Path
from random import Random
from sys import getsizeof

from prose_continuity_probe import LinkRules, RULES, repair, templates
from prose_grammar import CandidateWeights, compile_scene
from prose_grammar_probe import CORPUS, expect_rejection
from prose_model_probe import CORPUS as TRAINING, MaskedScorer
from prose_ngram import STOP, is_han
from prose_short_branch_probe import ADDITIONS, expand
from prose_window_compact_probe import BATCH_SIZE, PERIOD, SCENE, gates, replay, report, scene
from surface_lexicon_probe import locked_faces


SPEC = Path(__file__).with_name("prose_window_actions.json")
SEEDS = (463121, 813527)
REVISED_SEEDS = (634291, 714043)
REFINED_SEEDS = (287431, 519607)
MIN_ACTION_PERCENT = 10


class ObservationRules(LinkRules):
    def __init__(self, model, previous, spec, lexicon):
        super().__init__(model, previous, lexicon)
        self.actions = {}
        self.blocks = []
        self.families = set()
        for family in spec:
            name, anchors = family["id"], family["anchors"]
            if name in self.families or not 2 <= len(set(anchors)) == len(anchors) or not set(anchors) <= set(self.anchors):
                raise ValueError("unexpected observation family or anchor")
            self.families.add(name)
            intros, _ = templates(family["leads"], anchors, model[self.lead])
            named, _ = templates(family["named"], anchors, model[self.follow])
            shared = {sentence: (1, 1) for sentence in family["shared"]}
            if not shared or not all(map(is_han, shared)):
                raise ValueError("observation method needs visible legal outcomes")
            self.add_family(name, anchors, intros, named, shared, model[self.lead])

    def add_family(self, name, anchors, intros, named, shared, previous):
        correlated = defaultdict(list)
        for anchor in anchors:
            related = named[anchor] | shared
            signature = tuple(sorted(related.items()))
            for intro in intros[anchor]:
                if intro in self.actions or intro in self.start_words or intro in previous:
                    raise ValueError("observation intro conflicts with an existing path")
                self.actions[intro] = name, anchor, related
                correlated[signature].append(intro)
        self.blocks.extend((leads, dict(outcomes)) for outcomes, leads in correlated.items())

    def intro_row(self):
        return super().intro_row() | dict.fromkeys(self.actions, (1, 1))

    def bind_followups(self, phases, routes, endings, lead, next_phase):
        super().bind_followups(phases, routes, endings, lead, next_phase)
        for introductions, follow in self.blocks:
            state = len(phases)
            phases.append(follow)
            routes.append(dict.fromkeys(follow, next_phase))
            endings.append(False)
            for introduction in introductions:
                routes[lead][introduction] = state

    def accepts_pair(self, introduction, following):
        action = self.actions.get(introduction)
        if action is not None:
            return following in action[2]
        return super().accepts_pair(introduction, following)

    def inspect(self, pieces):
        slots, intros, linked, wrong, unfinished = super().inspect(pieces)
        for index in range(self.lead, len(pieces), 3):
            if pieces[index] not in self.actions:
                continue
            intros += 1
            if index + 1 == len(pieces):
                unfinished += 1
            elif self.accepts_pair(pieces[index], pieces[index + 1]):
                linked += 1
            else:
                wrong += 1
        return slots, intros, linked, wrong, unfinished


def check_action_controls(route, rules, model):
    representatives = {}
    for lead, (family, anchor, continue_with) in rules.actions.items():
        representatives.setdefault(family, (lead, anchor, continue_with))
    prefix = next(iter(model[0])) + STOP
    for family, (lead, anchor, choices) in representatives.items():
        started = prefix + lead + STOP
        sample = started + next(iter(choices)) + STOP
        if route.render_rank(route.recover_rank(sample)) != sample:
            raise ValueError("the action did not retain its introduction")
        expect_rejection(lambda: route.recover_rank(started))
        other_condition = next(word for reason, (_, _, follow) in representatives.items()
                               if reason != family for word in follow if word not in choices)
        expect_rejection(lambda: route.recover_rank(started + other_condition + STOP))
        others = [other for name, noun, other in rules.actions.values() if name == family and noun != anchor]
        wrong_anchor = next((word for row in others for word in row if word not in choices), None)
        if wrong_anchor is not None:
            expect_rejection(lambda: route.recover_rank(started + wrong_anchor + STOP))


def check_obstruction(route, rules, model):
    for family, _, outcomes in rules.actions.values():
        if family == "window-frame" and any("看全" not in suffix for suffix in outcomes):
            raise ValueError("a window-frame action did not promise full visibility")
    intro = next(iter(model[0])) + STOP + "隔着窗框看不全高楼" + STOP
    closed = intro + "侧过身就能看全了" + STOP
    if route.render_rank(route.recover_rank(closed)) != closed:
        raise ValueError("full visibility after moving did not round-trip")
    expect_rejection(lambda: route.recover_rank(intro + "侧过身就能看见" + STOP))


class ActionSamples:
    def __init__(self, rules, examples, baseline_text):
        self.rules = rules
        self.examples = examples
        self.baseline_text = baseline_text
        self.counts = defaultdict(Counter)
        self.first = {}

    def __call__(self, suite, size, index, text):
        pieces = text[:-1].split(STOP)
        counts = self.counts[suite, size]
        for slot in range(self.rules.lead, len(pieces), 3 * PERIOD):
            counts["slots"] += 1
            action = self.rules.actions.get(pieces[slot])
            if action is None:
                continue
            reason, _, _ = action
            counts[reason] += 1
            if self.examples and size == 524 and reason not in self.first:
                original = self.baseline_text[suite, size, index][:-1].split(STOP)
                before = STOP.join(original[slot - 1:slot + 2]) + STOP
                after = STOP.join(pieces[slot - 1:slot + 2]) + STOP
                self.first[reason] = (suite, index, slot // 3, before, after)

    def report(self):
        reasons = []
        for (suite, size), counts in self.counts.items():
            selected = {family: counts[family] for family in sorted(self.rules.families)}
            total = sum(selected.values())
            print(f"actions | seed{suite} | {size} B | selected {total}/{counts['slots']}"
                  f" ({total / counts['slots']:.1%}) | {selected}")
            if size == 524 and total * 100 < MIN_ACTION_PERCENT * counts["slots"]:
                reasons.append(f"seed{suite}/concrete-action-coverage")
        for name, (suite, index, cycle, before, after) in self.first.items():
            print(f"action-sample {name}/{suite}/{index}/{cycle} A {before}")
            print(f"action-sample {name}/{suite}/{index}/{cycle} B {after}")
        return reasons


def report_count_storage(label, carrier):
    rows = {id(row): row for row in carrier.exact if row is not None}
    values = {id(value): value for row in rows.values() for value in row}
    approximate = sum(map(getsizeof, rows.values())) + sum(map(getsizeof, values.values()))
    print(f"{label} | count states {len(carrier.exact)}, materialized rows {len(rows)}"
          f" | Python count tables {approximate} B")


def observation_fixture(mini=None):
    spec = json.loads(SPEC.read_bytes())
    if (spec["format"], spec["scene"], spec["period"]) != (1, SCENE, PERIOD):
        raise ValueError("unexpected observation action fixture")
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    if mini is None:
        mini = CandidateWeights(scene(TRAINING, "scenes")["training"], MaskedScorer("mini-int8"))
    old = compile_scene(scene(CORPUS, "scenes"), lexicon, mini)
    with redirect_stdout(StringIO()):
        original = expand(SCENE, old, scene(ADDITIONS, "scenes")["frames"], lexicon, mini, max_units=10)
    previous = scene(RULES, "scenes")
    model, _ = repair(original, previous["edits"])
    return spec, lexicon, model, previous


def action_families(spec, revised=False, refined=False):
    revisions = spec["revised"] if revised or refined else {}
    families = [family | revisions.get(family["id"], {}) for family in spec["families"]]
    if refined:
        next(family for family in families if family["id"] == "window-frame")["shared"] = spec["refined_window_frame"]
    return families


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true")
    variants = parser.add_mutually_exclusive_group()
    variants.add_argument("--revised", action="store_true", help="evaluate causally revised forms on fresh seeds")
    variants.add_argument("--refined", action="store_true", help="require full visibility after obstruction")
    args = parser.parse_args()
    spec, lexicon, model, previous = observation_fixture()
    batches = {}
    seeds = REFINED_SEEDS if args.refined else REVISED_SEEDS if args.revised else SEEDS
    for suite, seed in enumerate(seeds):
        random = Random(seed)
        for size in (178, 524):
            batches[suite, size] = [random.randbytes(size) for _ in range(BATCH_SIZE)]
    baseline_text = {}

    def collect(suite, size, index, text):
        if args.examples and size == 524:
            baseline_text[suite, size, index] = text

    base_route, base, excerpts = replay(model, LinkRules(model, previous, lexicon), batches, args.examples, collect)
    if any(len(rows) != BATCH_SIZE for rows in base.values()):
        raise ValueError("window baseline did not fit the reference workload")
    report("baseline", base_route, base, excerpts)
    report_count_storage("baseline", base_route)
    candidate = ObservationRules(model, previous, action_families(spec, args.revised, args.refined), lexicon)
    observed = ActionSamples(candidate, args.examples, baseline_text)
    route, results, excerpts = replay(model, candidate, batches, args.examples, observed)
    check_action_controls(route, candidate, model)
    if args.refined:
        check_obstruction(route, candidate, model)
    report("actions", route, results, excerpts, base)
    report_count_storage("actions", route)
    reasons = gates(base_route, route, base, results) + observed.report()
    print(f"actions | quantitative gate | {', '.join(reasons) if reasons else 'pass, language review pending'}")


if __name__ == "__main__":
    main()
