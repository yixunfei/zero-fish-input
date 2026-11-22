"""Measure corpus-attested, three-sentence window observations against the frozen Debug graph."""

import argparse
import json
from hashlib import sha256
from pathlib import Path
from random import Random
import re
from statistics import median

from export_window_observation import freeze
from prose_capacity_probe import RankedCarrier, calibration_envelopes, report_surface_calibration
from prose_corpus_coverage_probe import matched_terms, widest
from prose_grammar_probe import expect_rejection
from prose_ngram import STOP, CapacityError
from prose_window_compact_probe import PERIOD
from prose_window_progression_probe import state_prefixes
from prose_window_scene_probe import guaranteed
from prose_window_slot_probe import HOLDOUT_SEED, SEEDS, normalizer, read_source, slot_routes
from surface_lexicon_probe import locked_faces


SPEC = Path(__file__).with_name("prose_scene_runs.json")
BASELINE_SHA = "3a8b44dfe515c5d1498fa09cf9b07a0fdc41937598bc718feab9be1e6b01ca1d"
STAGES = ("opens", "moves", "ends")


def compile_scenes(path, existing):
    normalize = normalizer()
    source = {normalize(record.text).removesuffix(STOP) for record in read_source(path)}
    _, tables = locked_faces()
    lexicon = set().union(*tables.values())
    spec = json.loads(SPEC.read_bytes())
    if spec["format"] != 1:
        raise ValueError("unknown scene format")
    scenes, seen = {}, set(existing)
    for scene in spec["scenes"]:
        witnesses = {key: sum(key in text for text in source) for key in scene["evidence"]}
        if not all(witnesses.values()):
            raise ValueError(f"missing collocation evidence in {scene['id']}: {witnesses}")
        slots = {word for word in scene["slots"] if word in lexicon and any(word in text for text in source)}
        stages = tuple(tuple(text for text in scene[stage] if text not in seen and text not in source)
                       for stage in STAGES)
        candidates = set().union(*map(set, stages))
        if (scene["id"] in scenes or min(map(len, stages)) < 2 or
                len(candidates) != sum(map(len, stages))):
            raise ValueError(f"scene has ambiguous or insufficient sentences: {scene['id']}")
        seen.update(candidates)
        emitted = {word for word in slots if any(word in text for text in candidates)}
        print(f"{scene['id']} | {len(candidates)} compiled | {witnesses}"
              f" | slots {','.join(sorted(emitted))}")
        scenes[scene["id"]] = stages, emitted
    return scenes


def route_with_scenes(baseline, scenes, required=False):
    if baseline is None:
        phases, targets, endings = [{}], [{}], [True]
        entrances = (0,)
    else:
        offset = int(required)
        phases = [{text: (1, len(words) - index) for _, _, words in groups
                   for index, text in enumerate(words)} for groups in baseline.groups]
        targets = [{text: target + offset for _, target, words in groups for text in words}
                   for groups in baseline.groups]
        endings = list(baseline.endings)
        if required:
            phases.insert(0, {})
            targets.insert(0, {})
            endings.insert(0, False)
            entrances = (0,)
        else:
            # Preserve the starting and periodic optical observation. A branch consumes one full cycle.
            entrances = range(3, 3 * PERIOD, 3)
    for state in entrances:
        destination = 1 if required else (state + 3) % (3 * PERIOD) if baseline is not None else 0
        for (first, middle, last), _ in scenes.values():
            next_state = len(phases)
            phases[state].update(dict.fromkeys(first, (1, 1)))
            targets[state].update(dict.fromkeys(first, next_state))
            phases.extend(dict.fromkeys(row, (1, 1)) for row in (middle, last))
            targets.extend((dict.fromkeys(middle, next_state + 1), dict.fromkeys(last, destination)))
            endings.extend((False, False))
    return RankedCarrier(phases, destinations=targets, endings=endings)


def check_scenes(route, scenes, entrance):
    prefix = state_prefixes(route)[entrance]
    for name, ((first, middle, last), _) in scenes.items():
        head = prefix + first[0] + STOP
        open_run = head + middle[0] + STOP
        complete = open_run + last[0] + STOP
        state = 0
        for sentence in complete[:-1].split(STOP):
            group, _ = route.words[state][sentence]
            state = route.groups[state][group][1]
        while not route.endings[state]:
            _, state, words = route.groups[state][0]
            complete += words[0] + STOP
        if route.render_rank(route.recover_rank(complete)) != complete:
            raise ValueError(f"scene does not rank exactly: {name}")
        (other_first, other_middle, other_last), _ = next(item for key, item in scenes.items() if key != name)
        for invalid in (head, open_run, complete[:-1], open_run + other_last[0] + STOP,
                        head + other_middle[0] + STOP + last[0] + STOP,
                        head + other_first[0] + STOP + last[0] + STOP):
            expect_rejection(lambda: route.recover_rank(invalid))


def sentence_set(route):
    return set().union(*(row.keys() for row in route.words))


def review(routes, terms, slots, base_mask, scenes, calibration):
    mask = re.compile("|".join(sorted({*slots, *base_mask.pattern.split("|")}, key=lambda w: (-len(w), w))))
    openings = {line for (first, _, _), _ in scenes.values() for line in first}
    for name, route in routes.items():
        cap = guaranteed(route)
        print(f"{name} | {len(sentence_set(route))} sentences | {len(route.groups)} states"
              f" | {len({id(row) for row in route.exact if row is not None})} count rows"
              f" | {len(matched_terms(sentence_set(route), terms))} shared terms | guarantee {cap} B"
              f" | worst 178/524: {widest(route, 178)}/{widest(route, 524)}")
        for byte in (0, 255):
            packet = bytes([byte]) * cap
            if route.recover(route.render(packet)) != packet:
                raise ValueError("boundary packet did not round-trip")
        expect_rejection(lambda: route.render(b"\xff" * (cap + 1)))
    for seed in (*SEEDS, HOLDOUT_SEED):
        random = Random(seed)
        for size in (178, 524):
            packets = [random.randbytes(size) for _ in range(64)]
            for name, route in routes.items():
                report_batch(name, route, seed, packets, terms, slots, mask, openings)
    if calibration:
        entries = calibration_envelopes(calibration)
        for name, route in routes.items():
            report_surface_calibration(name, route.render, route.recover, lambda size: widest(route, size), entries)


def report_batch(name, route, seed, packets, terms, slots, mask, openings):
    measurements = []
    union, slot_union = set(), set()
    samples, total_runs = [], 0
    size = len(packets[0])
    bound = widest(route, size)
    for packet in packets:
        try:
            surface = route.render(packet)
        except CapacityError as failure:
            if bound is not None:
                raise ValueError("a guaranteed scene packet was rejected") from failure
            continue
        if route.recover(surface) != packet:
            raise ValueError("paired scene packet did not recover")
        length = len(surface.encode("utf-8"))
        if bound is not None and length > bound:
            raise ValueError("scene carrier exceeded its guaranteed length")
        lines = surface[:-1].split(STOP)
        matched = matched_terms(lines, terms)
        used = {word for word in slots if any(word in text for text in lines)}
        union.update(matched)
        slot_union.update(used)
        runs = [index for index, line in enumerate(lines) if line in openings]
        total_runs += len(runs)
        if name == "one-scene" and runs != [0]:
            raise ValueError("required opening scene was not emitted exactly once")
        measurements.append((len(matched), len(used), 1 - len(set(lines)) / len(lines),
                             1 - len({mask.sub("○", s) for s in lines}) / len(lines),
                             length, len(runs) * 3 / len(lines)))
        if seed == HOLDOUT_SEED and len(samples) < 3:
            start = max(0, runs[min(len(runs) - 1, len(samples))] - 1) if runs else 16
            samples.append(STOP.join(lines[start:start + 6]))
    if not measurements:
        print(f"{seed}/{size} {name} | 0/{len(packets)} recovery (capacity)")
        return
    words, used, repeated, masked, length, linked = map(median, zip(*measurements))
    print(f"{seed}/{size} {name} | {len(measurements)}/{len(packets)} exact"
          f" | words {words:g}/{len(union)} | slots {used:g}/{len(slot_union)}"
          f" | repeat {repeated:.1%}/{masked:.1%} | bytes {length:g}"
          f" | runs {total_runs}, linked {linked:.1%} | max {max(row[4] for row in measurements)} B"
          f" | slots used {','.join(sorted(slot_union))}")
    for sample in samples:
        print(f"  {sample}")


def main(path, calibration):
    _, baseline, _, _, terms, _, base_mask = slot_routes(path)
    if sha256(freeze(baseline)).hexdigest() != BASELINE_SHA:
        raise ValueError("baseline differs from the frozen Android graph")
    originals = sentence_set(baseline)
    scenes = compile_scenes(path, originals)
    pure = route_with_scenes(None, scenes)
    hybrid = route_with_scenes(baseline, scenes)
    required = route_with_scenes(baseline, scenes, required=True)
    check_scenes(pure, scenes, 0)
    check_scenes(hybrid, scenes, 3)
    check_scenes(required, scenes, 0)
    added = {word for _, words in scenes.values() for word in words
             if not any(word in sentence for sentence in originals)}
    print(f"scenes | {len(scenes)} | {sum(len(words) for _, words in scenes.values())} scene-slot placements"
          f" | {len(added)} surfaces absent from frozen prose")
    routes = {"frozen": baseline, "hybrid": hybrid, "one-scene": required, "scenes-only": pure}
    review(routes, terms, added, base_mask, scenes, calibration)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--calibration", type=Path)
    args = parser.parse_args()
    main(args.corpus, args.calibration)
