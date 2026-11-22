"""Run public Mini/Tiny candidate screening and a frozen prose n-gram comparison."""

import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
from random import Random
from statistics import median
from string import Formatter

import numpy as np
import onnxruntime as ort

from prose_carrier_probe import CORPUS as SEED_CORPUS, check_daily
from prose_ngram import CapacityError, MAX_ENVELOPE_BYTES, ProseNgram, freeze, is_han, shortlist, train
from surface_lexicon_probe import DailyProbe, locked_faces
from wording_daily import ROOT, daily_asset


CORPUS = Path(__file__).with_name("prose_ngram_corpus.json")
ASSETS = ROOT / "build/model-evaluation"
VOCAB_HASH = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
GRAPH_HASHES = {
    "mini-int8": "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446",
    "tiny": "8ed158781c253fba6409cd7a5c352ea77d20a98ae6e8fc925d7df3aff1f343ff",
}
SCREEN_MARGIN = 3.0
INPUT_NAMES = ("input_ids", "positions", "target_ids")
PREFIXES = {
    "outing": "出门 前 先",
    "observation": "从 窗边 往 外 看",
    "organizing": "整理 桌面 时 先",
}


class MaskedScorer:
    def __init__(self, name):
        directory = ASSETS / "android-assets" / name
        graph = directory / "model.onnx"
        vocab = ASSETS / ("mini" if name == "mini-int8" else name) / "vocab.txt"
        raw = vocab.read_bytes()
        if sha256(graph.read_bytes()).hexdigest() != GRAPH_HASHES[name] or sha256(raw).hexdigest() != VOCAB_HASH:
            raise ValueError(f"model or vocabulary hash mismatch: {name}")
        self.asset_bytes = graph.stat().st_size + len(raw)
        self.ids = {word: index for index, word in enumerate(raw.decode("utf-8").splitlines())}
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(str(graph), options, providers=["CPUExecutionProvider"])
        self.verify_fixtures(directory / "fixtures.json")

    def verify_fixtures(self, path):
        fixtures = json.loads(path.read_bytes())
        if len(fixtures) != 26:
            raise ValueError("expected 26 existing model benchmark vectors")
        for row in fixtures:
            scores = self.predict({name: row[name] for name in INPUT_NAMES})
            np.testing.assert_allclose(scores, row["expected_scores"], rtol=0, atol=0.025)

    def accepts(self, word):
        return is_han(word) and all(char in self.ids for char in word)

    def predict(self, inputs):
        values, = self.session.run(None, {name: np.asarray(value, dtype=np.int64) for name, value in inputs.items()})
        if not np.isfinite(values).all():
            raise ValueError("nonfinite short-word inference result")
        return values

    def scores(self, context, words):
        if not 1 <= len(context) <= 16 or not 2 <= len(words) <= 8 or not self.accepts(context):
            raise ValueError("context or group is outside the short-word scorer")
        left = [self.ids[char] for char in context]
        rows, positions, targets = [], [], []
        for word in words:
            if len(word) != 2 or not self.accepts(word):
                raise ValueError("candidate is outside the short-word scorer")
            tokens = [101, *left, *(self.ids[char] for char in word), 102]
            for position in (len(tokens) - 3, len(tokens) - 2):
                row = tokens.copy()
                targets.append(row[position])
                row[position] = 103
                rows.append(row)
                positions.append(position)
        values = self.predict(dict(zip(INPUT_NAMES, (rows, positions, targets), strict=True)))
        if values.shape != (2 * len(words),):
            raise ValueError("malformed short-word inference result")
        return dict(zip(words, values.reshape(-1, 2).mean(axis=1).tolist(), strict=True))


def keep_candidates(scores):
    best = max(scores.values())
    return {word for word, score in scores.items() if score >= best - SCREEN_MARGIN}


def compiled_rows(counts, scorers):
    variants = {name: {} for name in ("counts", *scorers)}
    stats = {name: Counter() for name in scorers}
    vocab = scorers["mini-int8"]
    for history, counter in sorted(counts.items()):
        options = shortlist(counter)
        variants["counts"][history] = options
        context = "".join(word for word in history if is_han(word))[-16:]
        words = [word for word, _ in options if len(word) == 2 and vocab.accepts(word)]
        for name, scorer in scorers.items():
            filtered = options
            if context and scorer.accepts(context) and len(words) >= 2:
                keep = keep_candidates(scorer.scores(context, words))
                filtered = [(word, count) for word, count in options if word not in words or word in keep]
                stats[name].update(rows=1, pruned=len(options) - len(filtered))
            variants[name][history] = filtered
    return variants, stats


def template_cases(scenarios, scorer):
    for scene in scenarios:
        for clause in scene["clauses"]:
            for pattern in clause["patterns"]:
                context = ""
                for literal, field, _, _ in Formatter().parse(pattern):
                    for char in literal:
                        context = (context + char)[-16:] if scorer.accepts(char) else ""
                    if field is not None:
                        terms = clause["terms"][field]
                        words = [word for word in terms if len(word) == 2 and scorer.accepts(word)][:8]
                        if context and len(words) >= 2:
                            yield context, words
                        context = (context + terms[0])[-16:]


def rewrite_cases(corpus, seed):
    original = {scene["id"]: scene["rewrites"] for scene in seed["scenarios"]}
    for scene in corpus["scenes"]:
        for left, words, right in scene["rewrite_slots"]:
            if [left + word + right for word in words] not in original[scene["id"]]:
                raise ValueError("the scored rewrite differs from the existing whole-sentence forms")
            yield left, words


def compare_shortlists(corpus, scorers):
    vocab = scorers["mini-int8"]
    seed = json.loads(SEED_CORPUS.read_bytes())
    cases = tuple(template_cases(seed["scenarios"], vocab))
    rewrites = tuple(rewrite_cases(corpus, seed))
    paired = [(context, good, bad) for scene in corpus["scenes"] for context, good, bad in scene["ranking"]]
    for name, model in scorers.items():
        top = []
        retained = Counter()
        for context, words in cases:
            keep = keep_candidates(model.scores(context, words))
            retained.update(candidates=len(words), kept=len(keep))
        for context, words in rewrites:
            keep = keep_candidates(model.scores(context, words))
            retained.update(rewrite_pairs_with_both=len(keep) == len(words))
        for context, good, bad in paired:
            scores = model.scores(context, good + bad)
            keep = keep_candidates(scores)
            best = max(scores, key=scores.get)
            top.append(best in good)
            retained.update(good=len(good), good_kept=len(keep.intersection(good)),
                            bad=len(bad), bad_kept=len(keep.intersection(bad)))
        print(f"{name} | {model.asset_bytes} model+vocab B | 26/26 reference score vectors | shared seed slots {len(cases)}"
              f" | kept {retained['kept']}/{retained['candidates']}"
              f" | rewrite pairs with both kept {retained['rewrite_pairs_with_both']}/{len(rewrites)}"
              f" | authored ranking top-good {sum(top)}/{len(top)}"
              f" | kept good {retained['good_kept']}/{retained['good']}"
              f" | kept decoys {retained['bad_kept']}/{retained['bad']}")


def verify_frames(route):
    for sample in (b"\x00", b"\xff", b"\x00\x00", b"\x00\x01\x02"):
        if route.recover(route.render(sample)) != sample:
            raise ValueError("synthetic packet did not round-trip")
    first = route.render(b"\x00")
    for bad in (first[:-1], "A" + first, first + route.continue_text(())):
        try:
            route.recover(bad)
        except ValueError:
            continue
        raise ValueError("malformed synthetic prose was accepted")
    try:
        route.render(bytes(MAX_ENVELOPE_BYTES))
    except CapacityError:
        return
    raise ValueError("oversized synthetic prose did not fail")


def measure(route, packets):
    lengths, repeats = [], []
    for packet in packets:
        try:
            text = route.render(packet)
        except CapacityError:
            continue
        if route.recover(text) != packet:
            raise ValueError("synthetic envelope did not round-trip")
        lengths.append(len(text.encode()))
        lines = text[:-1].split("\u3002")
        repeats.append(1 - len(set(lines)) / len(lines))
    if not lengths:
        return f"0/{len(packets)} fits"
    return (f"{len(lengths)}/{len(packets)} fits, median {median(lengths):.0f} B,"
            f" median duplicate sentences {median(repeats):.1%}")


def compare_prose(corpus, scorers, examples):
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    random = Random(241121)
    batches = {size: tuple(random.randbytes(size) for _ in range(64)) for size in (178, 524)}
    daily = DailyProbe(daily_asset())
    for size, packets in batches.items():
        size_median = check_daily(daily, packets)
        print(f"Daily | {size} B | {len(packets)}/{len(packets)} fits | median {size_median:.0f} B")
    for scene in corpus["scenes"]:
        tokens = {token for line in scene["training"] for token in line.split(" ")}
        known = tokens & lexicon
        print(f"{scene['id']} | training {len(scene['training'])} sentences"
              f" | lexicon vocabulary {len(known)}/{len(tokens)}"
              f" ({len(known - tables['jichu'])} outside the main table)")
        for order in (2, 3):
            compare_scene(scene, order, scorers, batches, examples)


def compare_scene(scene, order, scorers, batches, examples):
    variants, stats = compiled_rows(train(scene["training"], order), scorers)
    for name, rows in variants.items():
        resource = freeze(rows, order)
        route = ProseNgram(resource)
        verify_frames(route)
        checks = " | ".join(f"{size} B: {measure(route, packets)}" for size, packets in batches.items())
        counters = stats.get(name, {})
        print(f"{scene['id']} | order {order} | {name} | {len(rows)} rows, {len(resource)} B"
              f" | scored {counters.get('rows', 0)} rows, removed {counters.get('pruned', 0)} options"
              f" | SHA-256 {sha256(resource).hexdigest()} | {checks}")
        if examples:
            print(route.continue_text(PREFIXES[scene["id"]].split(" ")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--examples", action="store_true", help="show one public continuation per run")
    args = parser.parse_args()
    corpus = json.loads(CORPUS.read_bytes())
    if corpus["format"] != 1 or ort.__version__ != "1.26.0":
        raise ValueError("expected corpus format 1 and the project's ONNX Runtime 1.26.0")
    scorers = {name: MaskedScorer(name) for name in GRAPH_HASHES}
    compare_shortlists(corpus, scorers)
    compare_prose(corpus, scorers, args.examples)


if __name__ == "__main__":
    main()
