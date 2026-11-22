"""Measure the existing P1 position path on the locked public corpus."""
import argparse
from statistics import median
from time import perf_counter_ns

from binary_prototype import deflate
from p1_corpus import samples
from p1_profile import Profile
from rime_location_codec import decode, encode, tokens


def inspect(profile, raw):
    start = perf_counter_ns()
    terms = profile.positions.terms(raw)
    matched = perf_counter_ns()
    stream = encode(raw, terms)
    segmented = perf_counter_ns()
    compressed = deflate(stream, profile.position_dict)
    packed = perf_counter_ns()
    recovered = decode(stream, profile.positions, {}, len(raw))
    end = perf_counter_ns()
    if recovered != raw:
        raise ValueError("position round trip")
    short = full = long_words = 0
    literal_bytes = 0
    for kind, token in tokens(stream):
        if kind == "row":
            if len(token) == 2:
                short += 1
            else:
                full += 1
            long_words += len(profile.positions.get(token)) >= 3
        elif kind == "literal":
            literal_bytes += len(token)
        else:
            raise ValueError("unexpected P1 token")
    sizes = [len(raw), len(stream), len(compressed), short, full, long_words, literal_bytes]
    # An oracle supplies every row type for free. Only unused fixed-width bits
    # are removed. The segmentation and literal token representation are fixed.
    sizes.append((3 * short + 2 * full) // 8)
    times = [matched-start, segmented-matched, packed-segmented, end-packed]
    return sizes, times


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile")
    args = parser.parse_args()
    profile = Profile(args.profile, sending=True)
    print("sample | input B | row stream B | row+dict B | short | full | 3+ chars | "
          "literal payload B | gross whole B saved | lookup / DP / deflate / reverse ms")
    for name, raw in samples():
        runs = [inspect(profile, raw) for _ in range(3)]
        sizes = runs[0][0]
        if any(repeat_sizes != sizes for repeat_sizes, _ in runs):
            raise ValueError("unstable counts")
        times = [median(column) / 1e6 for column in zip(*(t for _, t in runs))]
        print(name, *sizes, " / ".join(f"{t:.2f}" for t in times), sep=" | ")


if __name__ == "__main__":
    main()
