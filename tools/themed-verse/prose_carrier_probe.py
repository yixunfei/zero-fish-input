"""Compare lexicon-supported prose generation and exact-sentence rewriting on the host."""

import json
from itertools import product
from math import log2, prod
from pathlib import Path
from random import Random
from statistics import median

from surface_lexicon_probe import DailyProbe, locked_faces
from wording_daily import daily_asset


CORPUS = Path(__file__).with_name("prose_probe_corpus.json")
CARRIER_LIMIT = 16_384
STOP = "\u3002"
QUOTE_MARKS = "\"\u2018\u2019\u201c\u201d\u300c\u300d\u300e\u300f"


def choose(value, groups):
    choices = []
    for group in groups:
        value, index = divmod(value, len(group))
        choices.append(group[index])
    if value:
        raise ValueError("value exceeds the available choices")
    return choices


def recover(choices, groups):
    value, multiplier = 0, 1
    for choice, group in zip(choices, groups, strict=True):
        value += group.index(choice) * multiplier
        multiplier *= len(group)
    return value


class GeneratedProse:
    def __init__(self, scene, lexicon):
        self.opening = scene["opening"]
        self.terms_seen, self.terms_used = set(), set()
        self.groups = []
        for clause in scene["clauses"]:
            slots = clause["terms"]
            self.terms_seen.update(term for values in slots.values() for term in values)
            matched = [[term for term in values if term in lexicon] for values in slots.values()]
            self.terms_used.update(term for values in matched for term in values)
            if not all(matched):
                raise ValueError("the lexicon left an empty prose slot")
            sentences = {
                pattern.format(**dict(zip(slots, words, strict=True)))
                for words in product(*matched)
                for pattern in clause["patterns"]
            }
            if any(not text or STOP in text or "\n" in text or "\r" in text for text in sentences):
                raise ValueError("a candidate conflicts with the prose boundary")
            self.groups.append(sorted(sentences))

    @property
    def span(self):
        return prod(map(len, self.groups))

    def render(self, value):
        return self.opening + STOP.join(choose(value, self.groups)) + STOP

    def recover(self, text):
        if not text.startswith(self.opening) or not text.endswith(STOP):
            raise ValueError("invalid prose paragraph")
        sentences = text[len(self.opening):-1].split(STOP)
        return recover(sentences, self.groups)

    def min_bytes(self):
        return len(self.opening.encode()) + sum(
            min(len(sentence.encode()) for sentence in group) + len(STOP.encode())
            for group in self.groups
        )


class RewriteRules:
    def __init__(self, rules):
        self.rules = {}
        for index, group in enumerate(rules):
            if len(group) < 2:
                raise ValueError("rewrite needs alternatives")
            for sentence in group:
                if not sentence or STOP in sentence or sentence in self.rules:
                    raise ValueError("ambiguous rewrite surface")
                self.rules[sentence] = index, group

    def parse(self, text):
        segments = text.split(STOP)
        groups = []
        skeleton = []
        quoted = any(mark in text for mark in QUOTE_MARKS)
        for index, sentence in enumerate(segments):
            # Freeze drafts with quotes instead of interpreting quote boundaries in this probe.
            rule = self.rules.get(sentence) if not quoted and index < len(segments) - 1 else None
            groups.append(rule[1] if rule else [sentence])
            skeleton.append((1, rule[0]) if rule else (0, sentence))
        return segments, groups, skeleton


def check_generated(route, random):
    sizes = []
    values = [0, route.span - 1] + [random.randrange(route.span) for _ in range(64)]
    for value in values:
        text = route.render(value)
        if route.recover(text) != value:
            raise ValueError("generated prose did not round-trip")
        sizes.append(len(text.encode()))
    for byte in (0, 1, 255):
        framed = b"\x01" + bytes([byte])
        text = route.render(int.from_bytes(framed, "big"))
        if route.recover(text).to_bytes(len(framed), "big") != framed:
            raise ValueError("generated prose lost the synthetic byte frame")
    try:
        route.recover(route.render(0)[:-1])
    except ValueError:
        pass
    else:
        raise ValueError("accepted truncated generated prose")
    return median(sizes)


def check_rewrites(rules, drafts, frozen):
    capacities = []
    verified = 0
    for source in drafts:
        _, groups, skeleton = rules.parse(source)
        span = prod(map(len, groups))
        capacities.append(span.bit_length() - 1)
        for value in range(span):
            changed = STOP.join(choose(value, groups))
            segments, received, recovered_skeleton = rules.parse(changed)
            if recovered_skeleton != skeleton or recover(segments, received) != value:
                raise ValueError("rewrite changed protected content or failed to round-trip")
            verified += 1
    for source in frozen:
        _, groups, _ = rules.parse(source)
        if prod(map(len, groups)) != 1 or STOP.join(choose(0, groups)) != source:
            raise ValueError("a frozen draft accepted a rewrite")
    return capacities, verified


def check_daily(daily, packets):
    lengths = []
    for packet in packets:
        text = daily.render(packet)
        if daily.recover(text, len(packet)) != packet:
            raise ValueError("Daily control did not round-trip")
        lengths.append(len(text.encode()))
    return median(lengths)


def main():
    corpus = json.loads(CORPUS.read_bytes())
    if corpus["format"] != 1:
        raise ValueError("unsupported prose fixture")
    _, tables = locked_faces()
    lexicon = set.union(*tables.values())
    random = Random(241121)
    packet_batches = {size: [random.randbytes(size) for _ in range(64)] for size in (178, 524)}
    daily = DailyProbe(daily_asset())
    for size, packets in packet_batches.items():
        mid = check_daily(daily, packets)
        print(f"Daily control | {size} B packet | {len(packets)} complete round trips | {mid:.0f} B median carrier")
    for scene in corpus["scenarios"]:
        route = GeneratedProse(scene, lexicon)
        mid = check_generated(route, random)
        capacities, verified = check_rewrites(
            RewriteRules(scene["rewrites"]), scene["drafts"], scene["frozen_drafts"]
        )
        absolute_max = int((CARRIER_LIMIT // route.min_bytes()) * log2(route.span))
        terms = len(route.terms_used)
        outside_base = len(route.terms_used - tables["jichu"])
        print(f"{scene['id']} | dictionary terms {terms}/{len(route.terms_seen)} ({outside_base} outside jichu)"
              f" | choices by sentence {list(map(len, route.groups))} | {route.span.bit_length() - 1} whole bits"
              f" | {mid:.0f} B median paragraph | {absolute_max} bits at the byte limit if repeated")
        print(f"{scene['id']} | rewrite capacities {capacities} whole bits | {verified} exact rewrite round trips"
              f" | {len(scene['frozen_drafts'])} frozen negative controls")
        for size, packets in packet_batches.items():
            values = [int.from_bytes(b"\x01" + packet, "big") for packet in packets]
            fitting = sum(value < route.span for value in values)
            rewrite_fits = sum(value < (1 << bits) for value in values for bits in capacities)
            print(f"{scene['id']} | {size} B framed packets that fit one paragraph: {fitting}/{len(packets)}")
            print(f"{scene['id']} | {size} B framed packets that fit one draft: "
                  f"{rewrite_fits}/{len(packets) * len(capacities)}")


if __name__ == "__main__":
    main()
