"""Recruit window noun slots from attested classifier phrases without using corpus sentences as output."""

import argparse
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path
from random import Random
import re
from statistics import median

from prose_capacity_probe import RankedCarrier
from prose_corpus_coverage_probe import HAN_RUN, evidence, matched_terms, read_source, widest
from prose_ngram import STOP, is_han
from prose_window_compact_probe import PERIOD, check_boundary, new_row
from prose_window_progression_probe import progression_routes
from prose_window_scene_probe import guaranteed, report_calibration
from surface_lexicon_probe import locked_faces


SPEC = Path(__file__).with_name("prose_window_slot_corpus.json")
ROOT = Path(__file__).resolve().parents[2]
OPENCC = ROOT / "engine-rime/build/generated/openccAssets/rime/opencc"
NORMALIZATION = {
    "TSCharacters.txt": "6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a",
    "TSPhrases.txt": "b2ef895dd4953b4bb77fc8ef8d26a2a9ca6d43a760ed9a1d767672cfafa6324f",
}
NUMBER = r"(?<![一二三四五六七八九十百千万])(?:[两兩一二三四五六七八九十几幾那这這]|[1-9])"
BOUNDARY = frozenset("的了在有正将被是上里中内外旁邻边下前后向也又都和与把，让。！？；、")
SEEDS = (319353, 682879)
HOLDOUT_SEED = 847277


def normalizer():
    mapping = {}
    for name, expected in NORMALIZATION.items():
        data = (OPENCC / name).read_bytes().replace(b"\r\n", b"\n")
        if sha256(data).hexdigest() != expected:
            raise ValueError("traditional normalization tables differ from the pinned source")
        for line in data.decode("utf-8").splitlines():
            old, options = line.split("\t")
            mapping[old] = options.split()[0]
    lengths = sorted({len(key) for key in mapping}, reverse=True)

    def normalize(text):
        parts = []
        index = 0
        while index < len(text):
            for width in lengths:
                candidate = text[index:index + width]
                if candidate in mapping:
                    parts.append(mapping[candidate])
                    index += len(candidate)
                    break
            else:
                parts.append(text[index])
                index += 1
        return "".join(parts)

    return normalize


def broad_inventory(roles, ordinary, named):
    heads = defaultdict(list)
    for role in roles:
        for head in role["heads"]:
            heads[head].append(role["id"])
    return {word: heads[word[-1]] for word in ordinary
            if 2 <= len(word) <= 4 and word[-1] in heads and word not in named and is_han(word)}


def observed_objects(records, roles, lexicon, ordinary, named, normalize):
    markers = {role["id"]: re.compile(NUMBER + "[" + role["classifiers"] + "]") for role in roles}
    evidence_by_role = {role["id"]: defaultdict(set) for role in roles}
    broad = defaultdict(set)
    wanted = broad_inventory(roles, ordinary, named)
    source_sentences = set()
    for record in records:
        text = normalize(record.text)
        source_sentences.add(text.removesuffix(STOP))
        if record.owner not in ("", "\\N"):
            for part in HAN_RUN.findall(text):
                for width in (2, 3, 4):
                    for index in range(len(part) - width + 1):
                        token = part[index:index + width]
                        if token in wanted:
                            broad[token].add(record.owner)
        for role in roles:
            support = evidence_by_role[role["id"]]
            for match in markers[role["id"]].finditer(text):
                start = match.end()
                for end in range(min(start + 4, len(text)), start + 1, -1):
                    word = text[start:end]
                    if (word[-1] in role["heads"] and word in lexicon and is_han(word) and
                            (end == len(text) or text[end] in BOUNDARY)):
                        if word not in named:
                            support[word].add(text)
                        break
    reviewers = {role["id"]: {} for role in roles}
    for word, owners in broad.items():
        if len(owners) >= 3:
            for identifier in wanted[word]:
                reviewers[identifier][word] = len(owners)
    return evidence_by_role, reviewers, source_sentences


def freeze_candidates(roles, evidence_by_role, reviewers, source_sentences, discover):
    candidates = defaultdict(dict)
    origins = defaultdict(set)
    for role in roles:
        evidence_row = evidence_by_role[role["id"]]
        supervised = reviewers[role["id"]]
        strict = {word: len(rows) for word, rows in evidence_row.items() if len(rows) >= 2}
        evidence_counts = supervised | strict
        if discover:
            print(f"review {role['id']} | {' '.join(sorted(evidence_counts))}")
        words = set(role.get("reviewed", ())) & evidence_counts.keys()
        print(f"slots {role['id']} | {len(evidence_row)} classifier witnesses | {len(supervised)} contributor witnesses"
              f" | {len(words)} compiled | {' '.join(sorted(words))}")
        for word in sorted(words):
            for form in role["forms"]:
                text = form.format(item=word)
                if len(text) >= 6 and text not in source_sentences:
                    candidates[role["stage"]][text] = (1, min(evidence_counts[word], 16))
                    origins[text].add(word)
    return candidates, origins


def augment(baseline, candidates):
    phases, routes = [], []
    original_leaves = set().union(*(row.keys() for row in baseline.words))
    for state, row in enumerate(baseline.groups):
        weights, targets = {}, {}
        for _, target, words in row:
            for index, word in enumerate(words):
                weights[word] = (1, len(words) - index)
                targets[word] = target
        if state < 3 * PERIOD and state not in (1, 2):
            for text, score in candidates[state % 3].items():
                if text not in original_leaves:
                    weights[text] = score
                    targets[text] = (state + 1) % (3 * PERIOD)
        phases.append(weights)
        routes.append(targets)
    return RankedCarrier(phases, destinations=routes, endings=baseline.endings)


def sample(route, rules, pairs, payload, terms, origins, frame_mask):
    text = route.render(payload)
    if route.recover(text) != payload:
        raise ValueError("corpus slot probe did not recover exactly")
    measure = new_row(text, rules)
    sentences = text[:-1].split(STOP)
    for index, sentence in enumerate(sentences):
        pair = pairs.get(sentence)
        if pair is not None and (index + 1 == len(sentences) or sentences[index + 1] not in pair.outcomes):
            raise ValueError("an existing observation lost its continuation")
    used = set().union(*(origins.get(sentence, set()) for sentence in sentences))
    masked = 1 - len({frame_mask.sub("○", line) for line in sentences}) / len(sentences)
    return len(matched_terms(sentences, terms)), used, measure.repeats, len(text.encode()), masked, sentences


def report_paired(baseline, route, rules, pairs, terms, origins, mask, seeds, examples=False):
    for name, carrier in (("window", baseline), ("corpus slots", route)):
        leaves = set().union(*(row.keys() for row in carrier.words))
        live_rows = len({id(row) for row in carrier.exact if row is not None})
        print(f"{name} | {len(leaves)} sentences | {len(matched_terms(leaves, terms))} shared inventory terms"
              f" | {live_rows} count rows"
              f" | guarantee {guaranteed(carrier)} B | 524 B worst {widest(carrier, 524)}")
    if any(new < old for old, new in zip(baseline.cumulative, route.cumulative, strict=True)):
        raise ValueError("adding typed candidates reduced the exact language space")
    for seed in seeds:
        random = Random(seed)
        for size in (178, 524):
            messages = [random.randbytes(size) for _ in range(64)]
            base = [sample(baseline, rules, pairs, packet, terms, {}, mask) for packet in messages]
            test = [sample(route, rules, pairs, packet, terms, origins, mask) for packet in messages]
            distinct = set().union(*(row[1] for row in test))
            values = {index: (median(row[index] for row in base), median(row[index] for row in test))
                      for index in (0, 2, 3, 4)}
            print(f"{seed}/{size} B | terms {median(row[0] for row in base):g} → {median(row[0] for row in test):g}"
                  f" | actual slot terms median {median(len(row[1]) for row in test):g}, union {len(distinct)}"
                  f" | sentence repeat {median(row[2] for row in base):.1%} → {median(row[2] for row in test):.1%}"
                  f" | masked shape repeat {median(row[4] for row in base):.1%} → {median(row[4] for row in test):.1%}"
                  f" | carrier bytes {median(row[3] for row in base):g} → {median(row[3] for row in test):g}")
            if values[0][1] <= values[0][0] or any(values[index][1] > values[index][0] for index in (2, 3, 4)):
                raise ValueError("a held corpus slot cohort failed the coverage or repetition gate")
            if examples:
                for index in (0, 37):
                    start = 16 + index
                    print(f"sample {seed}/{size}/{index} A {STOP.join(base[index][5][start:start + 6])}")
                    print(f"sample {seed}/{size}/{index} B {STOP.join(test[index][5][start:start + 6])}")


def prepare(path):
    records = tuple(read_source(path))
    _, tables = locked_faces()
    lexicon = set().union(*tables.values())
    names = set().union(*(tables[name] for name in ("diming", "renming", "mingren"))) - tables["jichu"]
    spec = json.loads(SPEC.read_bytes())
    if spec["format"] != 1:
        raise ValueError("unknown corpus slot format")
    roles = spec["roles"]
    observed, reviewers, source = observed_objects(records, roles, lexicon, tables["jichu"], names, normalizer())
    return records, lexicon, roles, observed, reviewers, source


def slot_routes(path, discover=False):
    records, lexicon, roles, observed, reviewers, source = prepare(path)
    candidates, origins = freeze_candidates(roles, observed, reviewers, source, discover)
    if discover:
        return None
    _, baseline, rules, pairs, known = progression_routes()
    route = augment(baseline, candidates)
    check_boundary(route, augment(baseline, candidates))
    previous = set().union(*(row.keys() for row in baseline.words))
    genuine = set().union(*(row.keys() for row in route.words)) - previous
    if genuine & source:
        raise ValueError("an added sentence duplicates a normalized source sentence")
    adopted = set().union(*(origins[text] for text in genuine))
    new_terms = {word for word in adopted if word not in known and not any(word in text for text in previous)}
    mask = re.compile("|".join(sorted(known | adopted, key=lambda word: (-len(word), word))))
    print(f"emittable slots | {len(adopted)} terms | {len(new_terms)} absent from previous sentence surfaces")
    shared = {word for word, count in evidence(records, lexicon).items() if count >= 3}
    return baseline, route, rules, pairs, shared, {text: origins[text] & new_terms for text in genuine}, mask


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--holdout", action="store_true")
    parser.add_argument("--examples", action="store_true")
    parser.add_argument("--calibration", type=Path)
    arguments = parser.parse_args()
    routes = slot_routes(arguments.corpus, arguments.discover)
    if routes is not None:
        seeds = (*SEEDS, HOLDOUT_SEED) if arguments.holdout else SEEDS
        report_paired(*routes, seeds, arguments.examples)
        if arguments.calibration:
            report_calibration(*routes[:2], arguments.calibration)
