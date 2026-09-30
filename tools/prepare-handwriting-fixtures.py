"""Fetch pinned LGPL public handwriting data for local evaluation, never the APK."""

import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
REVISION = "7a74e442c4130cccc226a7e7c2b683ac94c0cccb"
BASE = f"https://raw.githubusercontent.com/tegaki/tegaki/{REVISION}/tegaki-models"
MANIFEST = Path(__file__).with_name("handwriting-fixture-sources.json")


def verified(path: Path, source: dict) -> bool:
    return (path.is_file() and path.stat().st_size == source["bytes"]
            and hashlib.sha256(path.read_bytes()).hexdigest() == source["sha256"])


def prepare(destination: Path, source: dict) -> None:
    target = destination / source["path"]
    if verified(target, source):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    try:
        with urlopen(f"{BASE}/{source['path']}", timeout=30) as response, temporary.open("wb") as output:
            while chunk := response.read(65_536):
                output.write(chunk)
                if output.tell() > source["bytes"]:
                    raise ValueError("Handwriting fixture exceeds pinned size")
        if not verified(temporary, source):
            raise ValueError("Handwriting fixture checksum mismatch")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/handwriting-fixtures")
    arguments = parser.parse_args()
    sources = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for source in sources:
        prepare(arguments.output, source)
    (arguments.output / "sources.json").write_bytes(MANIFEST.read_bytes())
    stage_platform_fixtures(arguments.output, sources)
    print(f"Verified {len(sources)} pinned fixture/license files; evaluation-only, not bundled.")


def stage_platform_fixtures(directory: Path, sources: list[dict]) -> None:
    destination = ROOT / "build/handwriting-evaluation/platform-fixtures/handwriting"
    destination.mkdir(parents=True, exist_ok=True)
    samples = []
    for source in sources:
        if not source["path"].startswith("data/test/"):
            continue
        element = ET.parse(directory / source["path"]).getroot()
        extent = max(int(element.findtext("width", "1000")), int(element.findtext("height", "1000")))
        strokes = [[value for point in stroke.findall("point")
                    for value in (int(point.attrib["x"]) / extent, int(point.attrib["y"]) / extent)]
                   for stroke in element.findall("strokes/stroke")]
        samples.append({"id": source["path"], "label": element.findtext("utf8"), "strokes": strokes})
    report = {"revision": REVISION, "license": "LGPL-2.1", "samples": samples}
    (destination / "independent-traditional.json").write_text(json.dumps(report, ensure_ascii=True), encoding="utf-8")
    (destination / "COPYING").write_bytes((directory / "LGPL").read_bytes())


if __name__ == "__main__":
    main()
