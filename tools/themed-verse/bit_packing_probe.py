"""Measure non-zlib second passes over reversible word-level Huffman bytes."""
import argparse
from hashlib import sha256
import lzma
from pathlib import Path

from association_probe import ASSOCIATIONS, CORPUS_LOCK, envelope_bytes, phrases, synthetic_messages
from huffman_rans import HuffmanRans, MAX_STREAM
from p1_corpus import samples as regression_samples
from p2_corpus import canonical_json, read_lock, samples
from word_codec import WordCodec, word_counts


LZMA_FILTERS = [{"id": lzma.FILTER_LZMA2, "dict_size": 4096, "preset": 6}]


def build_models(training, associations):
    ranked = sorted(associations, key=lambda phrase: -sum(text.count(phrase) for text in training))
    ids = WordCodec({token.encode("utf-8"): 1 for token in ranked[:127]} | {b"": 1})
    if len(ids.codes) != 128 or any(len(code) != 7 for code in ids.codes.values()):
        raise ValueError("invalid seven-bit table")
    huffman = WordCodec(word_counts(training, associations))
    history = [huffman.encode(text.encode("utf-8")) for text in training]
    return ids, huffman, HuffmanRans(history, contextual=False), HuffmanRans(history, contextual=True)


def encode_lzma(stream):
    return len(stream).to_bytes(2, "big") + lzma.compress(stream, format=lzma.FORMAT_RAW, filters=LZMA_FILTERS)


def decode_lzma(stream):
    size = int.from_bytes(stream[:2], "big")
    if not 1 <= size <= MAX_STREAM or not 3 <= len(stream) <= 2 * MAX_STREAM:
        raise ValueError("LZMA frame length")
    reader = lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=LZMA_FILTERS)
    try:
        packed = reader.decompress(stream[2:], max_length=size + 1)
    except lzma.LZMAError as error:
        raise ValueError("LZMA stream") from error
    if len(packed) != size or not reader.eof or reader.unused_data:
        raise ValueError("LZMA output length or trailing bytes")
    return packed


def evaluate(raw, ids, huffman, global_rans, context_rans):
    fixed = ids.encode(raw)
    bits = huffman.encode(raw)
    if ids.decode(fixed, len(raw)) != raw or huffman.decode(bits, len(raw)) != raw:
        raise ValueError("phrase stream changed the input")
    global_stream = global_rans.encode(bits)
    context_stream = context_rans.encode(bits)
    lzma_stream = encode_lzma(bits)
    for stream, decode in ((global_stream, global_rans.decode), (context_stream, context_rans.decode),
                           (lzma_stream, decode_lzma)):
        unpacked = decode(stream)
        if unpacked != bits or huffman.decode(unpacked, len(raw)) != raw:
            raise ValueError("second pass changed the input")
    return {
        "seven_bit_phrase_ids": len(fixed),
        "huffman_phrases": len(bits),
        "huffman_then_global_rans": len(global_stream),
        "huffman_then_context_rans": len(context_stream),
        "huffman_then_raw_lzma2": len(lzma_stream),
        "shortest_huffman_stream": min(len(bits), len(global_stream), len(context_stream), len(lzma_stream)),
    }


def summarize(rows):
    return {"samples": len(rows), "variants": {
        name: {
            "stream_bytes": sum(row[name] for row in rows),
            "stream_delta": sum(row[name] - row["huffman_phrases"] for row in rows),
            "smaller_streams": sum(row[name] < row["huffman_phrases"] for row in rows),
            "smaller_envelopes": sum(envelope_bytes(row[name]) < envelope_bytes(row["huffman_phrases"]) for row in rows),
            "larger_envelopes": sum(envelope_bytes(row[name]) > envelope_bytes(row["huffman_phrases"]) for row in rows),
            "envelope_delta": sum(envelope_bytes(row[name]) - envelope_bytes(row["huffman_phrases"]) for row in rows),
        } for name in rows[0]}}


def check_literal_fallback(ids, huffman):
    raw = "ASCII e\u0301，𠀋、🙂、\r\n、\r、\n".encode("utf-8")
    for codec in (ids, huffman):
        stream = codec.encode(raw)
        if codec.decode(stream, len(raw)) != raw:
            raise ValueError("literal fallback was not exact")
        for invalid in (stream[:-1], stream + b"\0"):
            try:
                codec.decode(invalid, len(raw))
            except ValueError:
                continue
            raise ValueError("truncated or overlong phrase stream accepted")


def check_secondary_frames(huffman, global_rans, context_rans):
    stream = huffman.encode("公开夹具，𠀋🙂\r\ne\u0301".encode("utf-8"))
    for encode, decode in ((global_rans.encode, global_rans.decode), (context_rans.encode, context_rans.decode),
                           (encode_lzma, decode_lzma)):
        frame = encode(stream)
        if decode(frame) != stream:
            raise ValueError("binary fixture mismatch")
        for invalid in (frame[:-1], frame + b"\0", b"\xff\xff" + frame[2:]):
            try:
                decode(invalid)
            except ValueError:
                continue
            raise ValueError("truncated or overlong binary frame accepted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path, help="Directory with the frozen P2 lock, training and calibration archives")
    args = parser.parse_args()
    lock, digest = read_lock(args.corpus / "lock.json")
    if digest != sha256(CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")).hexdigest():
        raise ValueError("corpus differs from the repository lock")
    training = [raw.decode("utf-8") for _, raw in samples(lock, args.corpus, "training")]
    association_data = ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n")
    associations = phrases(association_data)
    ids, huffman, global_rans, context_rans = build_models(training, associations)
    check_literal_fallback(ids, huffman)
    check_secondary_frames(huffman, global_rans, context_rans)
    report = {"training_samples": len(training), "holdout_attempts": 0, "huffman_tokens": len(huffman.codes),
              "association_sha256_lf": sha256(association_data).hexdigest(),
              "phrase_codes_at_most_eight_bits": sum(len(token.decode("utf-8")) >= 2 and len(code) <= 8
                                                     for token, code in huffman.codes.items())}
    corpora = {
        "p2_calibration": (raw for _, raw in samples(lock, args.corpus, "calibration")),
        "p1_regression": (raw for name, raw in regression_samples() if name.endswith(".md")),
        "short_curation_controls": synthetic_messages(),
    }
    for name, corpus in corpora.items():
        report[name] = summarize([evaluate(raw, ids, huffman, global_rans, context_rans) for raw in corpus])
    print(canonical_json(report).decode().rstrip())


if __name__ == "__main__":
    main()
