"""Build the bundled, offline Han shape index from two pinned public data files."""

import argparse
import hashlib
import re
import unicodedata
from pathlib import Path
from zipfile import ZipFile


CJK_SHA = "5accf5661dd509d4bc5966afaeb7f763231a39f23b00acda89d6c040740b8ab6"
UNIHAN_SHA = "f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/shape/han-shapes.tsv"
COMPONENT = re.compile(r"([^:]+):([^()]+)\(([^()]*)\)")


def checked(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Checksum mismatch: {path.name}")
    return path


def readings_from(path):
    primary, alternatives, frequency = {}, {}, {}
    with ZipFile(path) as archive:
        for line in archive.read("Unihan_Readings.txt").decode("utf-8").splitlines():
            if not line.startswith("U+"):
                continue
            scalar, field, value = line.split("\t")
            character = chr(int(scalar[2:], 16))
            if field == "kMandarin":
                primary[character] = value.split()
            elif field == "kHanyuPinyin":
                alternatives[character] = [
                    reading for entry in value.split()
                    for reading in entry.split(":", 1)[1].split(",")
                ]
            elif field == "kHanyuPinlu":
                frequency[character] = sum(int(count) for count in re.findall(r"\((\d+)\)", value))
    readings = {
        character: list(dict.fromkeys(primary.get(character, []) + alternatives.get(character, [])))[:4]
        for character in primary.keys() | alternatives.keys()
    }
    return readings, frequency


def han(token):
    return len(token) == 1 and unicodedata.name(token, "").startswith(
        ("CJK UNIFIED IDEOGRAPH", "CJK COMPATIBILITY IDEOGRAPH", "CJK RADICAL")
    )


def components_of(token, definitions, depth=0):
    if han(token):
        return [token]
    if depth == 4 or token not in definitions:
        return []
    return [part for child in definitions[token][1] for part in components_of(child, definitions, depth + 1)]


def build(cjk, unihan):
    definitions = {}
    for line in cjk.read_text(encoding="utf-8").splitlines():
        match = COMPONENT.fullmatch(line)
        if match:
            token, layout, parts = match.groups()
            definitions[token] = layout.split("/", 1)[0], parts.split(",") if parts else []
    readings, frequency = readings_from(unihan)
    output = []
    for character, (layout, tokens) in sorted(definitions.items()):
        if character not in readings:
            continue
        direct = list(dict.fromkeys(part for token in tokens for part in components_of(token, definitions)))[:12]
        nested = list(dict.fromkeys(
            part for token in direct
            for child in definitions.get(token, ("", []))[1]
            for part in components_of(child, definitions)
            if part not in direct and part != character
        ))[:24]
        output.append("\t".join((character, ",".join(readings[character]),
                                 str(frequency.get(character, 0)), layout, "".join(direct) or "-", "".join(nested) or "-")))
    return ("\n".join(output) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decomposition", type=Path)
    parser.add_argument("unihan", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    data = build(checked(args.decomposition, CJK_SHA), checked(args.unihan, UNIHAN_SHA))
    if args.verify:
        if OUTPUT.read_bytes() != data:
            raise SystemExit("Bundled shape index differs from pinned sources")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_bytes(data)
    print(f"{data.count(bytes([10]))} characters, SHA-256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
