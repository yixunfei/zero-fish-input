"""Compile the P1 public configuration from the locked offline sources."""
import argparse
from array import array
from hashlib import file_digest, sha256
import json
from pathlib import Path
import re
import sys
import tempfile

from p1_corpus import training
from p1_positions import (FORWARD_FILE, FORWARD_MAGIC, REVERSE_FILE, REVERSE_HEADER,
                          REVERSE_MAGIC, ROW_BASE, ROW_COUNT, SHORT_BASE, SHORT_COUNT, U32,
                          PositionIndex)
from p1_profile import (GB_DICT, MODE_IDS, POOLS, POSITION_DICT, PROFILE_NAME,
                        PROFILE_SHA256, RECEIVE_ASSETS, UTF8_DICT, WORDS)
from rime_location_codec import ShortLocations, dictionary_rows, encode
from rime_location_probe import chunks
from verse import IMAGES, LOCK, Resources
from word_codec import WordCodec, word_counts


LOCK_SHA256 = "0f04754f84bedce500e965c3f895a444de13136a4d4e3bea68b81f12a2372767"
POSITION_DICT_SHA256 = "42af269d0800173c17c67e7a1253472d79aea4e0844a2600c5714862d5fc8d2b"
HAN_TERM = re.compile(r"[\u4e00-\u9fff]{1,64}")


def json_bytes(obj):
    return (json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def write_u32(file, values):
    numbers = array("I", values)
    if numbers.itemsize != U32.size:
        raise ValueError("expected a 32-bit unsigned integer")
    if sys.byteorder == "little":
        numbers.byteswap()
    numbers.tofile(file)


def build_positions(folder, spec):
    offsets = array("I", [0])
    forward = {}
    short = ShortLocations()
    count = 0
    with (folder / REVERSE_FILE).open("xb") as file:
        file.seek(REVERSE_HEADER.size + 4 * (ROW_COUNT + 1 + SHORT_COUNT))
        for count, (name, word, _, weight) in enumerate(dictionary_rows(spec), 1):
            row = count - 1
            if row >= ROW_COUNT:
                raise ValueError("extra public dictionary rows")
            data = word.encode()
            file.write(data)
            offsets.append(offsets[-1] + len(data))
            if HAN_TERM.fullmatch(word):
                forward.setdefault(data, ROW_BASE + row)
            short.add(name, word, weight, row)
        ranked = short.ranked()
        if count != ROW_COUNT or len(ranked) != SHORT_COUNT:
            raise ValueError("public dictionary row counts changed")
        file.seek(0)
        file.write(REVERSE_HEADER.pack(REVERSE_MAGIC, ROW_COUNT, SHORT_COUNT))
        write_u32(file, offsets)
        write_u32(file, (row for _, row in ranked))
    for i, (word, _) in enumerate(ranked):
        data = word.encode()
        if data in forward:
            forward[data] = min(forward[data], SHORT_BASE + i)
    with (folder / FORWARD_FILE).open("xb") as file:
        file.write(FORWARD_MAGIC)
        write_u32(file, (forward[word] for word in sorted(forward)))
    return len(forward)


def build_codebooks(folder, texts):
    index = PositionIndex((folder / REVERSE_FILE).read_bytes(), (folder / FORWARD_FILE).read_bytes())
    (folder / POOLS).write_bytes(json_bytes(Resources().groups))
    counts = word_counts(texts)
    (folder / WORDS).write_bytes(json_bytes(sorted((w.hex(), n) for w, n in counts.items())))
    for charset, name in (("utf-8", UTF8_DICT), ("gb18030", GB_DICT)):
        (folder / name).write_bytes(b"\n".join(text.encode(charset) for text in texts)[-32768:])
    position_dict = b"".join(encode(raw, index.terms(raw)) for raw in chunks("\n".join(texts)))[-32768:]
    if sha256(position_dict).hexdigest() != POSITION_DICT_SHA256:
        raise ValueError("transformed public dictionary changed")
    (folder / POSITION_DICT).write_bytes(position_dict)
    return sha256(json_bytes(sorted((w.hex(), bits)
                                    for w, bits in WordCodec(counts).codes.items()))).hexdigest()


def compile_profile(folder):
    lock = LOCK.read_bytes().replace(b"\r\n", b"\n")
    if sha256(lock).hexdigest() != LOCK_SHA256:
        raise ValueError("public dictionary source lock changed")
    spec = json.loads(lock)
    texts = training()
    terms = build_positions(folder, spec)
    word_codes = build_codebooks(folder, texts)
    assets = {}
    for name in (*RECEIVE_ASSETS, FORWARD_FILE):
        path = folder / name
        with path.open("rb") as file:
            assets[name] = {"bytes": path.stat().st_size, "sha256": file_digest(file, "sha256").hexdigest()}
    profile = {
        "format": "ZeroInput/public-themed-verse/P1",
        "sources": {"wanxiang": spec["version"], "revision": spec["revision"],
                    "license": spec["license"], "lock_sha256_lf": LOCK_SHA256,
                    "table_order": spec["tables"],
                    "training_utf8_sha256": sha256("\n".join(texts).encode()).hexdigest()},
        "positions": {"rows": ROW_COUNT, "short_ids": SHORT_COUNT, "forward_terms": terms,
                      "rules": "full rows v1; first long or lowest short; BMP Han 1-64; static tokens"},
        "words": {"rules": "v1 Huffman MSB-first; 1-16 B literal; zero tail", "codes_sha256": word_codes},
        "verse": {"theme": "linked", "images": IMAGES,
                  "rules": "sorted five BMP Han; rightmost imagery cycles; little-end digits; minimal span",
                  "layout": "five Han plus alternating CJK comma/stop; terminal stop; LF after every four"},
        "envelope": {"rules": "v1 host cover from profile SHA-256; raw 32 B key; single AES-256-GCM",
                     "descriptor": "u8 mode; u16 BE original size", "padding_bytes": 64},
        "limits": {"original_utf8": 2048, "stream": 2045, "position_inflated": 6144,
                   "carrier_utf8": 16384, "carrier_utf16": 8192},
        "deflate": "RFC1950 zlib level 9 with no dictionary or fixed bytes by mode",
        "modes_in_priority_order": list(MODE_IDS.items()),
        "assets": assets,
    }
    manifest = json_bytes(profile)
    (folder / PROFILE_NAME).write_bytes(manifest)
    return sha256(manifest).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new public profile directory")
    folder = parser.parse_args().output.resolve()
    if folder.exists():
        raise SystemExit("profile output already exists")
    folder.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".verse-build-", dir=folder.parent) as temp:
        staged = Path(temp) / "profile"
        staged.mkdir()
        digest = compile_profile(staged)
        if digest != PROFILE_SHA256:
            raise SystemExit(f"profile verification failed ({digest})")
        if folder.exists():
            raise SystemExit("profile output already exists")
        staged.rename(folder)
    print("P1 profile", digest)


if __name__ == "__main__":
    main()
