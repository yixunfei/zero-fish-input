"""Compile the locked public P2 profile for the local Android Debug workbench."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from struct import pack

from p2_corpus import canonical_json
from p2_theme_pools import build_pools
from verse import Resources


FIXTURES = Path(__file__).resolve().parent / "android-test-profile"
POOL_SHA = "36001880504280bd4110f28419a4f6ec22dbff5a4b1b9e062ad41e21167568b4"
DICTIONARY_SHA = "d0b0515c17072c6fd5bd83e8a3c0fd86bc0308867a9d31e00ea38d5ed400ff1f"
OPENING_SHA = "a5b5d0b3f193ab88b2e44a23270d346e7f9b9119c8790ac8f07c2d8bd67cbe32"
SEVEN_SHA = "329235ef9495609e166725a582ff79489a59fd59aacac4c24d2814e1fb807cb0"
MAPPING_SHA = "6024035deb18bbc26f7d3a97ccca8a25b6351485e6d50985282a8c7e751828d2"
BODY_SHA = "1b1534d5735747e20e6b9f01fde4baf636d8e80d560babf0b34a266b3196a6fa"
CARRIER_SHA = "f8e8993b267f405e4a56ec763009cfa76d5a13b87246bc16b14ae80b6bbc3f10"
WINDOW_SHA = "3a8b44dfe515c5d1498fa09cf9b07a0fdc41937598bc718feab9be1e6b01ca1d"
WORD_CONTEXT_SHA = "be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f"
CORPUS_SHA = "e2c4aeaa4daf6f17e2fa2d1a6871f9183cc58a008a34fd9ea50edc1c94a17652"


def verified(data, expected):
    if sha256(data).hexdigest() != expected:
        raise ValueError("public Android verse profile differs from the locked host configuration")
    return data


def gb_mapping():
    trails = (*range(0x40, 0x7f), *range(0x80, 0xff))
    double = [ord(bytes((lead, trail)).decode("gb18030"))
              for lead in range(0x81, 0xff) for trail in trails]
    # Four-byte codes form contiguous Unicode and pointer ranges in the reference codec.
    ranges = []
    for point in range(0x80, 0x110000):
        if 0xd800 <= point <= 0xdfff:
            continue
        value = chr(point).encode("gb18030")
        if len(value) != 4:
            continue
        a, b, c, d = value
        pointer = (((a - 129) * 10 + b - 48) * 126 + c - 129) * 10 + d - 48
        if ranges and ranges[-1][1] + 1 == point and ranges[-1][2] + point - ranges[-1][0] == pointer:
            ranges[-1][1] = point
        else:
            ranges.append([point, point, pointer])
    return pack(">23940H", *double) + b"".join(pack(">III", *row) for row in ranges)


def word_context_credits():
    root = Path(__file__).resolve().parents[2]
    corpus = root / "docs/themed-verse/p2-corpus-lock.json"
    lock = json.loads(verified(corpus.read_bytes().replace(b"\r\n", b"\n"), CORPUS_SHA))
    fields = ("title", "authors", "publication", "source", "source_url")
    works = [{field: row[field] for field in fields if field in row}
             for row in lock["records"] if row["split"] == "training"]
    sources = {name: lock["sources"][name] for name in sorted({work["source"] for work in works})}
    return canonical_json({"model": WORD_CONTEXT_SHA, "corpus": CORPUS_SHA, "sources": sources, "works": works})


def prepare(output):
    resources = Resources()
    _, _, variant = build_pools(resources.free)
    _, _, seven = build_pools(resources.seven)
    verified(canonical_json(variant.to_record()), POOL_SHA)
    fixture = json.loads((FIXTURES / "fixture.json").read_bytes())
    if fixture["format"] != "zero-input-verse-public-fixture-v1":
        raise ValueError("public Android verse fixture version")
    files = {
        "opening.bin": verified("".join(variant.theme).encode("utf-16-be"), OPENING_SHA),
        "seven.bin": verified("".join(seven.theme).encode("utf-16-be"), SEVEN_SHA),
        "gb-map.bin": verified(gb_mapping(), MAPPING_SHA),
        "gb18030.zdict": verified((FIXTURES / "gb18030.zdict").read_bytes(), DICTIONARY_SHA),
        "window-observation.bin": verified((FIXTURES / "window-observation-v4.bin").read_bytes(), WINDOW_SHA),
        "word-context-v1.bin": verified((FIXTURES / "word-context-v1.bin").read_bytes(), WORD_CONTEXT_SHA),
        "word-context-sources.json": word_context_credits(),
        "body.txt": verified(fixture["body"].encode(), BODY_SHA),
        "host-carrier.txt": verified(fixture["host_carrier"].encode(), CARRIER_SHA),
        "ATTRIBUTION.md": (FIXTURES / "ATTRIBUTION.md").read_bytes(),
    }
    output.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (output / name).write_bytes(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    prepare(parser.parse_args().output)
