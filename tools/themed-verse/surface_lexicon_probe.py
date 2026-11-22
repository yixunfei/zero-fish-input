"""Measure public lexicon coverage and synthetic carrier cost for the Daily trial."""

from collections import Counter
from hashlib import sha256
import json
from random import Random
from statistics import median
from struct import unpack_from

from wording_daily import ROOT, daily_asset


def locked_faces():
    lock = json.loads((ROOT / "tools/dictionaries/sources.lock.json").read_bytes())
    prepared = ROOT / "engine-rime/build/generated/publicDictionaryAssets/rime"
    tables = {}
    for table in lock["tables"]:
        name = f"dicts/{table}.dict.yaml"
        raw = (prepared / name).read_bytes()
        if sha256(raw).hexdigest() != lock["preparedFiles"][name]:
            raise ValueError(f"prepared table does not match the lockfile: {table}")
        _, marker, body = raw.partition(b"\n...\n")
        if not marker:
            raise ValueError(f"missing YAML data separator: {table}")
        tables[table] = {line.split(b"\t", 1)[0].decode("utf-8") for line in body.splitlines()
                         if line and not line.startswith(b"#") and b"\t" in line}
    return lock["version"], tables


class DailyProbe:
    def __init__(self, asset):
        offset = 0

        def read_words(width):
            nonlocal offset
            count, = unpack_from(">H", asset, offset)
            offset += 2
            end = offset + 2 * width * count
            text = asset[offset:end].decode("utf-16-be")
            offset = end
            return tuple(text[i:i + width] for i in range(0, len(text), width))

        self.times = read_words(2)
        topic_count = asset[offset]
        offset += 1
        self.topics = tuple(read_words(4) for _ in range(topic_count))
        if offset != len(asset):
            raise ValueError("unexpected Daily asset length")
        self.phrases = sorted((phrase, topic) for topic, group in enumerate(self.topics) for phrase in group)
        self.phrase_indices = {phrase: (index, topic) for index, (phrase, topic) in enumerate(self.phrases)}
        self.opening_radix = len(self.times) * len(self.phrases)

    def render(self, envelope):
        number = int.from_bytes(b"\x01" + envelope, "big")
        clauses = []
        topic = 0
        while number:
            if len(clauses) % 2 == 0:
                number, digit = divmod(number, self.opening_radix)
                time, index = divmod(digit, len(self.phrases))
                phrase, topic = self.phrases[index]
                clauses.append(self.times[time] + phrase + ("\uff0c" if number else "\u3002"))
            else:
                number, digit = divmod(number, len(self.topics[topic]))
                clauses.append(self.topics[topic][digit] + "\u3002")
        return "\n".join("".join(clauses[i:i + 6]) for i in range(0, len(clauses), 6))

    def recover(self, text, size):
        number, multiplier, topic = 0, 1, 0
        clauses = text.replace("\n", "").replace("\uff0c", "\u3002").split("\u3002")[:-1]
        for index, clause in enumerate(clauses):
            if index % 2 == 0:
                phrase, topic = self.phrase_indices[clause[2:]]
                digit = self.times.index(clause[:2]) * len(self.phrases) + phrase
                radix = self.opening_radix
            else:
                digit = self.topics[topic].index(clause)
                radix = len(self.topics[topic])
            number += digit * multiplier
            multiplier *= radix
        raw = number.to_bytes(size + 1, "big")
        if raw[0] != 1 or self.render(raw[1:]) != text:
            raise ValueError("noncanonical synthetic carrier")
        return raw[1:]


def report_lexicon(version, tables, daily, asset):
    faces = set.union(*tables.values())
    lengths = Counter(len(face) for face in faces if all("\u4e00" <= c <= "\u9fff" for c in face))
    phrases = set(daily.phrase_indices)
    used = set(daily.times) | {phrase[i:i + 2] for phrase in phrases for i in (0, 2)}
    associations = ROOT / "engine-dictionary/src/main/resources/word-associations.tsv"
    joined = {"".join(line.split("\t")[1:]) for line in associations.read_text(encoding="utf-8").splitlines()
              if line.startswith("zh\t")}
    print(f"Wanxiang {version}: {len(tables)} verified tables, {len(faces)} distinct surfaces")
    print(f"Basic Han surface counts by length 1-8: {[lengths[n] for n in range(1, 9)]}")
    print(f"Daily asset: {len(asset)} B, SHA-256 {sha256(asset).hexdigest()}")
    print(f"Daily: {len(daily.times)} times, {[len(group) for group in daily.topics]} phrases by topic")
    print(f"Daily two-Han components: {len(used)} of {lengths[2]} ({len(used) / lengths[2]:.3%})")
    print(f"Complete Daily phrases in any Wanxiang table: {len(phrases & faces)}; "
          f"in project associations: {len(phrases & joined)}")


def report_carrier_cost(daily):
    random = Random(241121)
    print("Synthetic envelope B | Round trips | Carrier UTF-8 B (min / median / max) | Median bits/B")
    for size in (178, 268, 524, 780, 1164):
        lengths = []
        for _ in range(64):
            raw = random.randbytes(size)
            text = daily.render(raw)
            if daily.recover(text, size) != raw:
                raise ValueError("synthetic carrier did not round-trip")
            lengths.append(len(text.encode("utf-8")))
        mid = median(lengths)
        print(f"{size} | {len(lengths)} | {min(lengths)} / {mid:.0f} / {max(lengths)} | {8 * size / mid:.3f}")


def main():
    version, tables = locked_faces()
    asset = daily_asset()
    daily = DailyProbe(asset)
    report_lexicon(version, tables, daily, asset)
    report_carrier_cost(daily)


if __name__ == "__main__":
    main()
