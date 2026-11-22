"""Compare frozen default word associations as GB18030 compression dictionaries."""
import argparse
from hashlib import sha256
from pathlib import Path
from statistics import median

from binary_prototype import deflate, inflate
from p1_corpus import samples as regression_samples
from p2_corpus import canonical_json, read_lock, samples
from prepare_android_verse import DICTIONARY_SHA, FIXTURES, verified
from verse import ROOT


ASSOCIATIONS = ROOT / "engine-dictionary/src/main/resources/word-associations.tsv"
SCENARIOS = ROOT / "engine-dictionary/src/test/resources/association-quality-scenarios.tsv"
CORPUS_LOCK = ROOT / "docs/themed-verse/p2-corpus-lock.json"
WINDOW = 32768
FRAME_BUCKET = 64
BODY_OVERHEAD = 3 + 36  # Packet header plus encrypted frame fields.
DEFAULT_ENVELOPE_OVERHEAD = 204  # Bootstrap, metadata and tags for the default two stages.


def phrases(data):
    result = {}
    for row in data.decode("utf-8").splitlines():
        if not row or row.startswith("#"):
            continue
        language, prefix, suffix = row.split("\t")
        if language not in ("zh", "en") or not prefix or not suffix:
            raise ValueError("invalid public association")
        phrase = join_words(language, prefix, suffix)
        result[phrase] = None
    return tuple(result)


def join_words(language, prefix, suffix):
    return prefix + ("" if language == "zh" else " ") + suffix


def synthetic_messages():
    for row in SCENARIOS.read_text(encoding="utf-8").splitlines():
        if not row or row.startswith("#"):
            continue
        _, language, _, prefix, replies = row.split("\t")
        if replies != "-":
            yield join_words(language, prefix, replies.split("|", 1)[0]).encode("utf-8")


def dictionaries(base, associations):
    shared = "\0".join(associations).encode("gb18030")
    budget = len(base)
    reserve = budget // 4
    extension = shared[-(WINDOW - budget):]
    return {
        "baseline": base,
        "associations_only": shared[-budget:],
        "same_budget_mix": base[-(budget - reserve):] + shared[-reserve:],
        "expanded_mix_base_last": extension + base,
        "expanded_mix_associations_last": base + extension,
    }


def envelope_bytes(stream_bytes):
    framed = stream_bytes + BODY_OVERHEAD
    return DEFAULT_ENVELOPE_OVERHEAD + FRAME_BUCKET * ((framed + FRAME_BUCKET - 1) // FRAME_BUCKET)


def measure(corpus, variants, associations):
    rows = []
    for raw in corpus:
        text = raw.decode("utf-8")
        coded = text.encode("gb18030")
        sizes = {}
        for name, dictionary in variants.items():
            stream = deflate(coded, dictionary)
            recovered = inflate(stream, 4 * len(raw), dictionary).decode("gb18030").encode("utf-8")
            if recovered != raw:
                raise ValueError("compression did not round-trip")
            sizes[name] = len(stream)
        rows.append((sum(phrase in text for phrase in associations), sizes))
    baseline = [sizes["baseline"] for _, sizes in rows]
    summary = {"samples": len(rows), "samples_with_full_association": sum(count > 0 for count, _ in rows)}
    # The smallest payload reduction that reaches a lower 64-byte bucket.
    framed = [size + BODY_OVERHEAD for size in baseline if size + BODY_OVERHEAD > FRAME_BUCKET]
    savings_needed = [(size - 1) % FRAME_BUCKET + 1 for size in framed]
    if savings_needed:
        summary["next_bucket_savings"] = {
            "minimum": min(savings_needed), "median": median(savings_needed), "maximum": max(savings_needed),
        }
    for name in variants:
        lengths = [sizes[name] for _, sizes in rows]
        delta = [curr - prev for curr, prev in zip(lengths, baseline, strict=True)]
        envelope_delta = [envelope_bytes(curr) - envelope_bytes(prev)
                          for curr, prev in zip(lengths, baseline, strict=True)]
        summary[name] = {
            "stream_bytes": sum(lengths), "delta_bytes": sum(delta),
            "shorter": sum(n < 0 for n in delta), "longer": sum(n > 0 for n in delta),
            "envelope_shrinks": sum(n < 0 for n in envelope_delta),
            "envelope_grows": sum(n > 0 for n in envelope_delta),
            "envelope_delta_bytes": sum(envelope_delta),
        }
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("calibration", type=Path, help="Directory containing the frozen P2 lock and calibration archive")
    args = parser.parse_args()
    lock, digest = read_lock(args.calibration / "lock.json")
    expected_lock = CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")
    if digest != sha256(expected_lock).hexdigest():
        raise ValueError("calibration differs from the repository lock")
    data = ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n")
    associations = phrases(data)
    base = verified((FIXTURES / "gb18030.zdict").read_bytes(), DICTIONARY_SHA)
    variants = dictionaries(base, associations)
    calibration = tuple(samples(lock, args.calibration, "calibration"))
    report = {
        "association_sha256_lf": sha256(data).hexdigest(),
        "association_phrases": len(associations),
        "dictionary_sizes": {name: len(dictionary) for name, dictionary in variants.items()},
        "calibration": {genre: measure((raw for row, raw in calibration if row["genre"] == genre),
                                       variants, associations)
                        for genre in sorted({row["genre"] for row, _ in calibration})},
        "p1_regression": measure((raw for name, raw in regression_samples() if name.endswith(".md")),
                                 variants, associations),
        "curation_controls": measure(synthetic_messages(), variants, associations),
        "holdout_attempts": 0,
    }
    print(canonical_json(report).decode().rstrip())


if __name__ == "__main__":
    main()
