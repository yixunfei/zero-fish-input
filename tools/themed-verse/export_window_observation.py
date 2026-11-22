"""Freeze the refined host grammar and print independent Android test vectors."""

import argparse
from hashlib import sha256
from pathlib import Path
from struct import pack

from prose_window_actions_probe import (
    PERIOD, ObservationRules, action_families, check_action_controls, check_obstruction, observation_fixture,
)
from prose_window_scene_probe import guaranteed, scene_routes
from prose_window_progression_probe import progression_routes
from prose_window_slot_probe import HOLDOUT_SEED, SEEDS, report_paired, slot_routes


def freeze(route):
    sentences = sorted({sentence for state in route.groups for _, _, words in state for sentence in words})
    indices = {word: index for index, word in enumerate(sentences)}
    asset = bytearray(b"ZWO1" + pack(">HH", len(sentences), len(route.groups)))
    for sentence in sentences:
        asset.extend(pack(">B", len(sentence)))
        asset.extend(sentence.encode("utf-16-be"))
    for ending, groups in zip(route.endings, route.groups, strict=True):
        asset.extend(pack(">BH", ending, len(groups)))
        for width, target, words in groups:
            asset.extend(pack(">BHH", width, target, len(words)))
            asset.extend(pack(f">{len(words)}H", *(indices[word] for word in words)))
    return asset


def main(output, scene_corpus, progression, attested_slots):
    if attested_slots:
        tested = slot_routes(attested_slots)
        report_paired(*tested, (*SEEDS, HOLDOUT_SEED))
        route = tested[1]
    elif progression:
        _, route, _, _, _ = progression_routes()
    elif scene_corpus:
        _, _, route, _, _, _ = scene_routes()
    else:
        spec, lexicon, model, previous = observation_fixture()
        rules = ObservationRules(model, previous, action_families(spec, refined=True), lexicon)
        route = rules.bound(model, every=PERIOD)
        check_action_controls(route, rules, model)
        check_obstruction(route, rules, model)
    limit = guaranteed(route)
    asset = freeze(route)
    if output.exists():
        if output.read_bytes() != asset:
            raise ValueError("generated window grammar differs from the existing file")
    else:
        with output.open("xb") as stream:
            stream.write(asset)
    print(f"ZWO1 | {len(asset)} B | {sha256(asset).hexdigest()}")
    print(f"Guaranteed frame | {limit} B | {len(route.groups)} states")
    for size in (1, 178, 524, limit):
        for fill in ("leading-zero", "max"):
            packet = bytes(0 if index < 5 else index * 67 % 256 for index in range(size))
            if fill == "max":
                packet = bytes([255]) * size
            text = route.render(packet)
            if route.recover(text) != packet:
                raise ValueError("host did not recover the vector")
            encoded = text.encode()
            print(f"{size:3} {fill:12} | {len(encoded):5} B | {sha256(encoded).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    editions = parser.add_mutually_exclusive_group()
    editions.add_argument("--scene-corpus", action="store_true")
    editions.add_argument("--progression", action="store_true")
    editions.add_argument("--attested-slots", type=Path, metavar="CORPUS")
    args = parser.parse_args()
    main(args.output, args.scene_corpus, args.progression, args.attested_slots)
