"""Freeze the public word-context model used by the Android Debug sender."""
import argparse
from hashlib import sha256
from pathlib import Path
from struct import pack

from association_probe import ASSOCIATIONS, CORPUS_LOCK, phrases
from p2_corpus import read_lock, samples
from word_codec import WordCodec, word_counts
from word_context_codec import ContextTables


def model_asset(corpus):
    lock, digest = read_lock(corpus / "lock.json")
    locked = CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")
    if digest != sha256(locked).hexdigest():
        raise ValueError("corpus differs from the repository lock")
    training = [raw.decode("utf-8") for _, raw in samples(lock, corpus, "training")]
    source = ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n")
    if sha256(source).hexdigest() != "778d14920b9a4f505c42cb29f8394778ea459ad1125562c04107349821da8d23":
        raise ValueError("public associations changed")
    words = WordCodec(word_counts(training, phrases(source)))
    tables = ContextTables(words.codes, [words.tokenize(line.encode("utf-8")) for line in training])
    data = bytearray(pack(">4sHH", b"ZWC1", len(tables.symbols), len(tables.bigram)))
    for i, token in enumerate(tables.symbols):
        code = words.codes[token]
        data.extend(pack(">BBIH", len(token), len(code), int(code, 2), tables.prefix[i + 1] - tables.prefix[i]))
        data.extend(token)
    for previous, row in sorted(tables.bigram.items()):
        data.extend(pack(">HH", previous + 1, len(row.ids)))
        for i, successor in enumerate(row.ids):
            data.extend(pack(">HH", successor, row.counts[i + 1] - row.counts[i]))
    return bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    asset = model_asset(args.corpus)
    # Versioned assets are immutable once used by a carrier mode.
    with args.output.open("xb") as stream:
        stream.write(asset)
    print(f"{len(asset)} bytes, SHA-256 {sha256(asset).hexdigest()}")


if __name__ == "__main__":
    main()
