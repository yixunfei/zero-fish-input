"""Compare short-message capacity on the frozen window grammar and concise clause variants."""
import argparse
from collections import defaultdict
from hashlib import sha256
from pathlib import Path
from random import Random
from statistics import median

from export_window_observation import freeze
from prepare_android_verse import WINDOW_SHA
from prose_capacity_probe import RankedCarrier
from prose_grammar_probe import expect_rejection
from prose_ngram import STOP
from prose_window_slot_probe import normalizer, read_source, slot_routes


# Each alternative inherits the original clause's required continuation, including its object.
# Only exact slot frames from the frozen graph with a short, nonempty noun are expanded.
FORMS = (
    ("窗外可见{}", ("窗外有{}", "窗外是{}")),
    ("{}就在窗下", ("窗下是{}", "窗下有{}")),
    ("低头望见{}", ("低头见{}", "往下看是{}")),
    ("{}就在远处", ("远处有{}", "远处是{}")),
    ("再外边是{}", ("再远是{}", "再往外是{}")),
    ("{}忽而晃了晃", ("{}轻摇", "{}微动", "{}轻轻一晃")),
    ("{}飞向远处", ("{}飞远了", "{}渐渐飞远")),
    ("{}飞离枝头", ("{}离枝飞去", "{}飞走了")),
    ("{}缓缓移动", ("{}缓缓飘过", "{}慢慢飘过")),
    ("{}在远处散开", ("{}渐散", "{}散开了")),
    ("{}泛起水光", ("{}泛光", "{}泛着光")),
    ("云影掠过{}", ("云影扫过{}", "云影移过{}")),
    ("{}上的亮色淡了", ("{}暗了些", "{}渐暗")),
    ("风拂过{}", ("风过{}", "风吹过{}")),
    ("{}翻了个面", ("{}翻面了", "{}翻过来")),
    ("{}落在窗台", ("{}落上窗台", "{}落到窗台")),
    ("渐渐看清{}", ("才看清{}", "终于看清{}")),
)


def alternatives(sentence):
    for original, replacements in FORMS:
        start, end = original.split("{}")
        if sentence.startswith(start) and sentence.endswith(end):
            item = sentence[len(start):len(sentence) - len(end)]
            if 2 <= len(item) <= 4:
                yield from (template.format(item) for template in replacements)


def add_clauses(base, source):
    phases, destinations = [], []
    for groups in base.groups:
        weights, targets = {}, {}
        candidates = defaultdict(set)
        for _, target, words in groups:
            for index, word in enumerate(words):
                weights[word] = (1, len(words) - index)
                targets[word] = target
                for shorter in alternatives(word):
                    candidates[shorter].add(target)
        for clause, options in candidates.items():
            if len(options) == 1 and clause not in targets and clause not in source:
                targets[clause] = next(iter(options))
                weights[clause] = (1, 1)
        phases.append(weights)
        destinations.append(targets)
    return RankedCarrier(phases, destinations=destinations, endings=base.endings)


def measure(label, route, receiver):
    counts = route.cumulative[40]  # All characters, including stops, cost three UTF-8 bytes.
    guaranteed = ((counts + 1).bit_length() - 2) // 8
    print(f"{label}: <=120 UTF-8 B: {counts} complete nonempty texts; {guaranteed} framed B guaranteed")
    # 48/53 B were observed by the separate shared-key study. 64/69 B only price an
    # extra 16 B at the transport layer. Neither constitutes a passphrase protocol test.
    for size in (48, 53, 64, 69, 194, 268):
        rng = Random(81209 + size)
        packets = [bytes(size), b"\xff" * size] + [rng.randbytes(size) for _ in range(32)]
        lengths = []
        for packet in packets:
            text = route.render(packet)
            if receiver.recover(text) != packet:
                raise ValueError("short prose byte recovery failed")
            lengths.append(len(text.encode("utf-8")))
        expect_rejection(lambda: receiver.recover(text.removesuffix(STOP)))
        if lengths[0] != min(lengths) or lengths[1] != max(lengths):
            raise ValueError("length-first ranking disagrees with its endpoint bounds")
        print(f"  {size} envelope B: {min(lengths)}..{max(lengths)} carrier B, median {median(lengths):g},"
              f" {len(packets)} byte-exact, utilization {24 / median(lengths):.3%}")


def main(path):
    _, base, *_ = slot_routes(path)
    if sha256(freeze(base)).hexdigest() != WINDOW_SHA:
        raise ValueError("window graph differs from the Android asset")
    normalize = normalizer()
    source = {normalize(row.text).removesuffix(STOP) for row in read_source(path)}
    trial = add_clauses(base, source)
    if any(new < old for new, old in zip(trial.cumulative, base.cumulative, strict=True)):
        raise ValueError("added clauses reduced available paths")
    measure("frozen window", base, base)
    measure("concise window", trial, add_clauses(base, source))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    main(parser.parse_args().corpus)
