"""Compare probability models on the same reversible word tokens."""
import argparse
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path

from association_probe import ASSOCIATIONS, CORPUS_LOCK, envelope_bytes, phrases, synthetic_messages
from p1_corpus import samples as regression_samples
from p2_corpus import canonical_json, read_lock, samples
from word_codec import WordCodec, word_counts
from word_context_codec import ContextTables, PublicContinuations, WordContextCodec


def build_codecs(training, association_data):
    huffman = WordCodec(word_counts(training, phrases(association_data)))
    tables = ContextTables(huffman.codes, (huffman.tokenize(text.encode("utf-8")) for text in training))
    public = PublicContinuations(huffman, association_data, tables)
    codecs = {
        "unigram": WordContextCodec(tables),
        "bigram": WordContextCodec(tables, tables.bigram),
        "supported_top8": WordContextCodec(tables, tables.sparse),
        "bigram_public": WordContextCodec(tables, tables.bigram, public=public),
        "recent_only": WordContextCodec(tables, cache_tokens=tables.known),
        "bigram_recent": WordContextCodec(tables, tables.bigram, cache_tokens=tables.known),
        "bigram_recent_lexical": WordContextCodec(tables, tables.bigram, cache_tokens=tables.lexical),
    }
    return huffman, codecs, {
        "vocabulary": len(tables.symbols), "lexical_tokens": len(tables.lexical), "bigram_contexts": len(tables.bigram),
        "bigram_edges": sum(len(row.ids) for row in tables.bigram.values()),
        "supported_contexts": len(tables.sparse), "supported_edges": sum(len(row.ids) for row in tables.sparse.values()),
        "public_prefixes": len(public.rows),
    }


def measure(raw, huffman, codecs):
    tokens = huffman.tokenize(raw)
    packed = huffman.encode_tokens(tokens)
    if huffman.decode(packed, len(raw)) != raw:
        raise ValueError("Huffman baseline did not recover the original")
    sizes = {"huffman": len(packed)}
    totals = {}
    for name, codec in codecs.items():
        encoded, stats = codec.encode(tokens)
        if codec.decode(encoded, len(raw)) != raw:
            raise ValueError(f"{name} did not recover the original")
        sizes[name], totals[name] = len(encoded), stats
    # All arms use the same one-byte mode and two-byte original length in the envelope estimate.
    sizes["shortest_context"] = min(sizes[name] for name in codecs if name != "unigram")
    sizes["shortest_with_fallback"] = min(sizes.values())
    sizes["targeted_selection"] = min(sizes["huffman"], sizes["recent_only"], sizes["bigram_recent"])
    return sizes, totals


def deltas(sizes, reference):
    pairs = list(zip(sizes, reference, strict=True))
    padded = [envelope_bytes(size) - envelope_bytes(before) for size, before in pairs]
    return {
        "stream_delta": sum(size - before for size, before in pairs),
        "smaller_streams": sum(size < before for size, before in pairs),
        "envelope_delta": sum(padded),
        "smaller_envelopes": sum(delta < 0 for delta in padded),
        "larger_envelopes": sum(delta > 0 for delta in padded),
    }


def summarize(rows):
    metrics = {name: Counter() for name in rows[0][1]}
    for _, details in rows:
        for name, stats in details.items():
            metrics[name].update(stats)
    modes = {}
    for name in rows[0][0]:
        sizes = [row[name] for row, _ in rows]
        modes[name] = {
            "stream_bytes": sum(sizes),
            "exceeds_body_stream_limit": sum(size > 2045 for size in sizes),
            "vs_huffman": deltas(sizes, [row["huffman"] for row, _ in rows]),
            "vs_unigram": deltas(sizes, [row["unigram"] for row, _ in rows]),
        }
    return {"samples": len(rows), "modes": modes, "predictions": metrics}


def check_frames(huffman, codecs):
    public = "收到请回复。明天见。收到请回复。明天见。\r\n𠀋🙂e\u0301\r\n".encode("utf-8")
    tokens = huffman.tokenize(public)
    limit = "𠀋🙂".encode("utf-8") * 256
    limit_tokens = huffman.tokenize(limit)
    fingerprints = {}
    for name, codec in codecs.items():
        stream, stats = codec.encode(tokens)
        if codec.decode(stream, len(public)) != public:
            raise ValueError("literal fixture")
        if codec.decode(codec.encode(limit_tokens)[0], len(limit)) != limit:
            raise ValueError("maximum-length literal fixture")
        if name == "bigram_recent" and not stats["repeat_hits"]:
            raise ValueError("repeat fixture did not exercise prediction")
        for data, size in ((stream[:-1], len(public)), (stream + b"\0", len(public)),
                           (bytes(4) + stream[4:], len(public)), (stream, len(public) - 1), (stream, 2049)):
            try:
                codec.decode(data, size)
            except ValueError:
                continue
            raise ValueError(f"{name} accepted a malformed frame")
        codec.encode(huffman.tokenize(b"public second message"))
        if codec.encode(tokens)[0] != stream:
            raise ValueError("context leaked between messages")
        fingerprints[name] = sha256(stream).hexdigest()
    return fingerprints


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path, help="Directory with the frozen training and calibration archives")
    args = parser.parse_args()
    lock, digest = read_lock(args.corpus / "lock.json")
    if digest != sha256(CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")).hexdigest():
        raise ValueError("corpus differs from the repository lock")
    training = [raw.decode("utf-8") for _, raw in samples(lock, args.corpus, "training")]
    association_data = ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n")
    huffman, codecs, model = build_codecs(training, association_data)
    fingerprints = check_frames(huffman, codecs)
    grouped = defaultdict(list)
    for row, raw in samples(lock, args.corpus, "calibration"):
        grouped[row["genre"]].append(measure(raw, huffman, codecs))
    calibration = [row for group in grouped.values() for row in group]
    report = {
        "corpus_sha256": digest,
        "association_sha256_lf": sha256(association_data).hexdigest(),
        "training_samples": len(training), "holdout_attempts": 0, "model": model,
        "public_fixture_payload_sha256": fingerprints,
        "p2_calibration": summarize(calibration),
        "p2_by_genre": {genre: summarize(group) for genre, group in grouped.items()},
        "p1_regression": summarize([measure(raw, huffman, codecs) for name, raw in regression_samples()
                                    if name.endswith(".md")]),
        "short_curation_controls": summarize([measure(raw, huffman, codecs) for raw in synthetic_messages()]),
    }
    print(canonical_json(report).decode().rstrip())


if __name__ == "__main__":
    main()
