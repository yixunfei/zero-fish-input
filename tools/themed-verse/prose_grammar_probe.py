"""Compare typed grammar, optional Mini weights, n-grams and Daily on the same public packets."""

import argparse
import json
from pathlib import Path
from random import Random

from prose_carrier_probe import CORPUS as SEED, RewriteRules, check_daily, check_rewrites
from prose_grammar import CandidateWeights, GrammarCarrier, compile_phase, compile_scene, freeze, rewrite_groups
from prose_model_probe import CORPUS as TRAINING, MaskedScorer, measure
from prose_ngram import CapacityError, MAX_ENVELOPE_BYTES, ProseNgram, STOP, freeze as freeze_ngram, shortlist, train
from surface_lexicon_probe import DailyProbe, locked_faces
from wording_daily import daily_asset


CORPUS = Path(__file__).with_name("prose_grammar_corpus.json")
CONTROLS = {
    "outing": (0, "出门前先把手机放进口袋。", "出门前先把手机上的天气。"),
    "observation": (1, "远处的屋顶显得有些模糊。", "远处的屋顶放到手边。"),
    "organizing": (1, "接着把剪刀放到手边。", "整理桌面时先把剪刀可以放在一起。"),
}


def expect_rejection(action):
    try:
        action()
    except ValueError:
        return
    raise ValueError("invalid grammar control was accepted")


def check_carrier(sender, receiver, scene):
    for packet in (b"\x00", b"\xff", b"\x00\x00", b"\x00\x01\x02"):
        if receiver.recover(sender.render(packet)) != packet:
            raise ValueError("grammar short-frame mismatch")

    first = sender.render(b"\x00")
    next_phase = first.count(STOP) % len(sender.codes)
    tail = next(text for text, (code, _) in sender.codes[next_phase].items() if code == 0) + STOP
    for malformed in (first[:-1], "A" + first, first + tail):
        expect_rejection(lambda: receiver.recover(malformed))
    phase, good, bad = CONTROLS[scene]
    receiver.require_sentence(phase, good)
    expect_rejection(lambda: receiver.require_sentence(phase, bad))
    expect_rejection(lambda: receiver.require_sentence((phase + 1) % len(sender.codes), good))
    try:
        sender.render(bytes(MAX_ENVELOPE_BYTES))
    except CapacityError:
        return
    raise ValueError("oversized grammar carrier was accepted")


def check_compiler(scorer):
    weights = CandidateWeights(["先 看", "再 看", "后来 看"], scorer)
    ambiguous = [[{"forms": ["先 看", "先看", "再 看", "后来 看"]}]]
    missing = [[{"role": "place", "words": ["地点"]}]]
    expect_rejection(lambda: compile_phase(ambiguous, set(), weights))
    expect_rejection(lambda: compile_phase(missing, set(), weights))
    print("Compiler | rejected ambiguous surfaces and empty required slots")


def compare_rewrite(scene, seed, lexicon):
    old = seed["rewrites"]
    added = [group for group in rewrite_groups(scene, lexicon) if group not in old]
    if len(added) < 3:
        raise ValueError("expected at least three new fact-bound rewrite groups")
    old_rules, new_rules = RewriteRules(old), RewriteRules(old + added)
    extended = STOP.join(group[0] for group in added[:3]) + STOP
    first_skeleton = new_rules.parse(added[0][0] + STOP)[2]
    if new_rules.parse(added[1][0] + STOP)[2] == first_skeleton:
        raise ValueError("rewrite merged different fact values")
    old_bits, _ = check_rewrites(old_rules, [*seed["drafts"], extended], seed["frozen_drafts"])
    new_bits, cases = check_rewrites(new_rules, [*seed["drafts"], extended], seed["frozen_drafts"])
    print(f"{scene['id']} | rewrite added {len(added)} fact-bound rules"
          f" | bits {old_bits} -> {new_bits} | {cases} exact round trips and one quoted control")


def verify_scoring_resources(lexicon, base, scene):
    terms = {word for phase in scene["stages"] for frame in phase for part in frame for word in part.get("words", ())}
    known = terms & lexicon
    print(f"{scene['id']} | grammar terms in full lexicon {len(known)}/{len(terms)}"
          f" ({len(known - base)} outside the main table)")


def compare_scene(scene, training, seed, tables, lexicon, scorer, batches, examples):
    scene_id = scene["id"]
    verify_scoring_resources(lexicon, tables["jichu"], scene)
    weights = CandidateWeights(training, scorer)
    phases = compile_scene(scene, lexicon, weights)
    print(f"{scene_id} | phase choices {[len(phase) for phase in phases]} | Mini scoring groups {len(weights.scores)}")
    for order in (2, 3):
        rows = {prefix: shortlist(row) for prefix, row in train(training, order).items()}
        route = ProseNgram(freeze_ngram(rows, order))
        report(scene_id, f"{order}-gram", route, batches)
    for use_mini in (False, True):
        resource = freeze(scene_id, phases, use_mini)
        sender, receiver = GrammarCarrier(resource), GrammarCarrier(resource)
        check_carrier(sender, receiver, scene_id)
        kind = "grammar+ngram+Mini" if use_mini else "grammar+ngram"
        report(scene_id, kind, sender, batches, f" | {len(resource)} B | {sender.digest}")
        if examples:
            print("".join(sender.examples))
    compare_rewrite(scene, seed, lexicon)


def report(scene, method, route, batches, details=""):
    checks = " | ".join(f"{size} B: {measure(route, packets)}" for size, packets in batches.items())
    print(f"{scene} | {method}{details} | {checks}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true", help="show the highest-weight public sentence per phase")
    args = parser.parse_args()
    scenes = json.loads(CORPUS.read_bytes())
    if scenes["format"] != 1:
        raise ValueError("unsupported public grammar format")
    training = {scene["id"]: scene["training"] for scene in json.loads(TRAINING.read_bytes())["scenes"]}
    seeds = {scene["id"]: scene for scene in json.loads(SEED.read_bytes())["scenarios"]}
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    mini = MaskedScorer("mini-int8")
    check_compiler(mini)
    random = Random(241121)
    batches = {size: tuple(random.randbytes(size) for _ in range(64)) for size in (178, 524)}
    daily = DailyProbe(daily_asset())
    for size, packets in batches.items():
        print(f"Daily | {size} B | {len(packets)}/{len(packets)} fits | median {check_daily(daily, packets):.0f} B")
    for scene in scenes["scenes"]:
        compare_scene(scene, training[scene["id"]], seeds[scene["id"]], tables, lexicon, mini, batches, args.examples)


if __name__ == "__main__":
    main()
