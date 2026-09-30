"""Stage the pinned public PP-OCRv5 model and its CTC character table."""

import hashlib
import argparse
from pathlib import Path
import subprocess
import sys
from urllib.request import urlopen

import yaml


REVISION = "ed152b8b495f84de93cda5709d768548a9127622"
BASE = f"https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/{REVISION}"
FILES = {
    "inference.onnx": (16_534_782, "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092"),
    "inference.yml": (148_345, "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e"),
}
DESTINATION = Path(__file__).resolve().parents[1] / "build" / "handwriting-model"


def verified(path: Path, size: int, digest: str) -> bool:
    if not path.is_file() or path.stat().st_size != size:
        return False
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    return actual == digest


def prepare_file(name: str, size: int, digest: str) -> Path:
    target = DESTINATION / name
    if verified(target, size, digest):
        return target
    temporary = target.with_suffix(target.suffix + ".tmp")
    try:
        with urlopen(f"{BASE}/{name}", timeout=30) as response, temporary.open("wb") as output:
            while chunk := response.read(65_536):
                output.write(chunk)
                if output.tell() > size:
                    raise ValueError(f"Oversized {name}")
        if not verified(temporary, size, digest):
            raise ValueError(f"Checksum mismatch for {name}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-quality-fixtures", action="store_true", help="Also stage public Android/desktop quality fixtures")
    arguments = parser.parse_args()
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name, (size, digest) in FILES.items():
        prepare_file(name, size, digest)
    config = yaml.safe_load((DESTINATION / "inference.yml").read_text(encoding="utf-8"))
    characters = config["PostProcess"]["character_dict"]
    if not isinstance(characters, list) or len(characters) != 18_383 or not all(
        isinstance(value, str) and value and "\n" not in value for value in characters
    ):
        raise ValueError("Invalid PP-OCRv5 character table")
    (DESTINATION / "characters.txt").write_text("\n".join(characters) + "\n", encoding="utf-8", newline="\n")
    print("Handwriting model and character table staged with verified source hashes.")
    subprocess.run([sys.executable, str(Path(__file__).with_name("prepare-handwriting-stroke-model.py"))], check=True)
    if arguments.include_quality_fixtures:
        subprocess.run([sys.executable, str(Path(__file__).with_name("prepare-handwriting-fixtures.py"))], check=True)


if __name__ == "__main__":
    main()
