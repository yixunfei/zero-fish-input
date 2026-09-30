"""Pack hash-pinned LGPL Tegaki models into a bounded feature-major runtime table."""

from pathlib import Path
import hashlib
import struct
from urllib.request import urlopen
import zipfile

import numpy as np
from handwriting_quality import is_han


ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "build/handwriting-stroke-model"
SOURCES = {
    "simplified": {
        "name": "tegaki-zinnia-simplified-chinese-light-0.3", "model": "handwriting-zh_CN.model",
        "archive_bytes": 7_015_643, "archive_sha256": "598787133a4d59fcf3a2fbc5654c68eaf35a8e422274efcea2035cc081f3446c",
        "model_bytes": 9_432_616, "model_sha256": "c652150e428ab1d7e64ba2a16eeb706349c34f0586ef034c564cee0c2c432cd9",
    },
    "traditional": {
        "name": "tegaki-zinnia-traditional-chinese-0.3", "model": "handwriting-zh_TW.model",
        "archive_bytes": 36_773_128, "archive_sha256": "f41032e67a4eff056813d243eabc32ea07eea404c714bdb5882a5b0fcda51690",
        "model_bytes": 52_210_768, "model_sha256": "cd47f16b64e7ecaa4c2813d0f98231dfea7b411f48be1ead5c9b2eeb19330b11",
    },
}


def verified(path: Path, size: int, digest: str) -> bool:
    return path.is_file() and path.stat().st_size == size and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def source_model(spec: dict) -> bytes:
    archive = DESTINATION / (spec["name"] + ".zip")
    if not verified(archive, spec["archive_bytes"], spec["archive_sha256"]):
        temporary = archive.with_suffix(".zip.tmp")
        try:
            url = "https://github.com/tegaki/tegaki/releases/download/v0.3/" + archive.name
            with urlopen(url, timeout=60) as source, temporary.open("wb") as output:
                while chunk := source.read(65_536):
                    output.write(chunk)
                    if output.tell() > spec["archive_bytes"]:
                        raise ValueError("Oversized pinned model archive")
            if not verified(temporary, spec["archive_bytes"], spec["archive_sha256"]):
                raise ValueError("Pinned model archive checksum mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    with zipfile.ZipFile(archive) as container:
        member = container.getinfo(spec["name"] + "/" + spec["model"])
        if member.file_size != spec["model_bytes"]:
            raise ValueError("Unexpected model member size")
        data = container.read(member)
        if hashlib.sha256(data).hexdigest() != spec["model_sha256"]:
            raise ValueError("Pinned source model checksum mismatch")
        return data


def parse(data: bytes) -> tuple[list[tuple[str, float]], np.ndarray]:
    magic, version, count = struct.unpack_from("<III", data)
    if magic ^ 0x0EF71821 != len(data) or version != 1 or count not in range(1, 20_001):
        raise ValueError("Invalid source model header")
    entries = np.empty(len(data) // 8, dtype=[("feature", "<u4"), ("character", "<u2"), ("weight", "<f4")])
    labels = []
    cursor, written = 12, 0
    for _ in range(count):
        encoded, bias = struct.unpack_from("<16sf", data, cursor)
        cursor += 20
        label = encoded.split(b"\0", 1)[0].decode("utf-8")
        accepted = is_han(label)
        label_index = len(labels)
        if accepted:
            labels.append((label, bias))
        previous = -1
        while True:
            index, value = struct.unpack_from("<if", data, cursor)
            cursor += 8
            if index == -1:
                break
            if index <= previous or index not in range(2_000_049) or not np.isfinite(value):
                raise ValueError("Invalid source feature")
            previous = index
            if accepted:
                entries[written] = (index, label_index, value)
                written += 1
    if cursor != len(data) or len(labels) != len(set(label for label, _ in labels)):
        raise ValueError("Invalid source model layout")
    return labels, entries[:written]


def pack(data: bytes, destination: Path) -> None:
    labels, entries = parse(data)
    entries = entries[np.argsort(entries["feature"], kind="stable")]
    features, counts = np.unique(entries["feature"], return_counts=True)
    offsets = np.concatenate(([0], np.cumsum(counts))).astype("<u4")
    with destination.open("wb") as output:
        output.write(struct.pack("<4sIIIII", b"ZSH1", 1, len(labels), len(features), len(entries), int(features[-1])))
        for label, bias in labels:
            output.write(struct.pack("<If", ord(label), bias))
        output.write(features.astype("<u4").tobytes())
        output.write(offsets.tobytes())
        output.write(entries["character"].astype("<u2").tobytes())
        output.write(entries["weight"].astype("<f4").tobytes())


def main() -> None:
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name, specification in SOURCES.items():
        output = DESTINATION / f"stroke-{name}.zsh"
        pack(source_model(specification), output)
        print(output.name, output.stat().st_size, hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
