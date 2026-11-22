"""Check conservative, context-scoped literal rewrites on authored evaluation drafts."""

from math import prod
from random import Random

from prose_carrier_probe import QUOTE_MARKS, STOP, RewriteRules, choose, recover
from prose_window_slot_probe import HOLDOUT_SEED, SEEDS


# Each rule has an unchanged antecedent and two equivalent statements. The
# reader sees that same antecedent, so rule eligibility is independent of rank.
RULES = (
    ("我站在临街的窗口", ("我看见一辆公交车在路口右转", "我看到一辆公交车在路口右转")),
    ("刚才下了一场雨", ("路边还有些积水", "路旁还有些积水")),
    ("窗前有间空屋", ("那间屋里的灯已经灭了", "那间屋里的灯已经熄了")),
    ("窗边垂着几根柳枝", ("柳枝在微风里轻轻摇动", "柳枝在微风中轻轻摇动")),
)

EVALUATION = (
    ("vehicle", "我站在临街的窗口。我看到一辆公交车在路口右转。转过去就望不见了。时间是早上七点。", 1),
    ("rain", "刚才下了一场雨。路旁还有些积水。水里映着窗上的光。", 1),
    ("lamp", "窗前有间空屋。那间屋里的灯已经灭了。再往里我就看不清了。", 1),
    ("willow", "窗边垂着几根柳枝。柳枝在微风里轻轻摇动。我在这里看了好一会儿。", 1),
    ("multiple", "刚才下了一场雨。路边还有些积水。窗边垂着几根柳枝。柳枝在微风中轻轻摇动。", 2),
    ("negation", "我站在临街的窗口。我没看见一辆公交车在路口右转。", 0),
    ("direction", "我站在临街的窗口。我看到一辆公交车在路口左转。", 0),
    ("reported", "他说他站在临街的窗口。我看到一辆公交车在路口右转。", 0),
    ("referent", "窗前有两间空屋。那间屋里的灯已经灭了。", 0),
    ("tense", "窗前有间空屋。那间屋里的灯可能已经灭了。", 0),
    ("tone", "窗边垂着几根柳枝。柳枝在微风中轻轻摇动吗？", 0),
    ("quotation", "“刚才下了一场雨。路边还有些积水。”", 0),
    ("incomplete", "刚才下了一场雨。路边还有些积水", 0),
)


class ScopedRules:
    def __init__(self, rows):
        self.antecedents = {lead for lead, _ in rows}
        self.rules = {(lead, choice): (index, tuple(group))
                      for index, (lead, group) in enumerate(rows) for choice in group}
        variants = {text for _, text in self.rules}
        if self.antecedents & variants or len(self.rules) != sum(len(group) for _, group in rows):
            raise ValueError("a rewrite could change context or parse ambiguously")

    def parse(self, text):
        segments = text.split(STOP)
        groups, skeleton = [], []
        frozen = any(mark in text for mark in QUOTE_MARKS)
        for index, segment in enumerate(segments):
            entry = self.rules.get((segments[index - 1], segment)) if 0 < index < len(segments) - 1 and not frozen else None
            groups.append(entry[1] if entry else (segment,))
            skeleton.append((1, entry[0]) if entry else (0, segment))
        return segments, groups, skeleton


def main():
    baseline = RewriteRules([list(group) for _, group in RULES])
    scoped = ScopedRules(RULES)
    variants = 0
    spans = []
    for name, source, expected in EVALUATION:
        _, old_groups, _ = baseline.parse(source)
        _, groups, signature = scoped.parse(source)
        span = prod(map(len, groups))
        if span != 1 << expected:
            raise ValueError(f"incorrect context gate: {name}")
        print(f"{name} | choices {prod(map(len, old_groups))} → {span}")
        for rank in range(span):
            output = STOP.join(choose(rank, groups))
            parts, received, received_signature = scoped.parse(output)
            if received_signature != signature or recover(parts, received) != rank:
                raise ValueError(f"contextual rewrite parity failed: {name}")
            variants += 1
        spans.append(span)
    print(f"{len(EVALUATION)} evaluation drafts | {variants} variants recovered | maximum {max(spans).bit_length() - 1} bits")
    for seed in (*SEEDS, HOLDOUT_SEED):
        random = Random(seed)
        for size in (178, 524):
            ranks = [int.from_bytes(b"\x01" + random.randbytes(size), "big") for _ in range(64)]
            fits = sum(rank < span for rank in ranks for span in spans)
            print(f"{seed}/{size} | {fits}/{len(ranks) * len(spans)} draft/packet combinations fit")


if __name__ == "__main__":
    main()
