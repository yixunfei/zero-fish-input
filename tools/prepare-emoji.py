"""Prepare or verify the pinned Unicode RGI catalog and offline Noto artwork.

Run --verify for a fully offline audit. Regeneration requires Pillow 12.2.0
and downloads only the exact files listed in tools/emoji/sources.lock.json.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/emoji"
DESTINATION = ROOT / "ime-ui/src/main/assets/emoji/18.0"
CACHE = ROOT / "build/emoji-preparation"
FIXTURE = ROOT / "ime-ui/src/test/resources/emoji/18.0/emoji-test.txt"
NAMESPACE = ROOT / "ime-ui/src/main/kotlin/dev/zeroinput/ime/ui"
GROUPS = {
    "Smileys & Emotion": "SMILEYS", "People & Body": "PEOPLE",
    "Component": "PEOPLE", "Animals & Nature": "NATURE",
    "Food & Drink": "FOOD", "Travel & Places": "TRAVEL",
    "Activities": "ACTIVITY", "Objects": "OBJECTS",
    "Symbols": "SYMBOLS", "Flags": "FLAGS",
}
NEW_NAMES = {
    "1FAEB": "裂开的脸 裂開的臉 崩溃 崩潰 liekai bengkui",
    "1FAF9": "向左拇指 左手 xiangzuo muzhi zuoshou",
    "1FAFA": "向右拇指 右手 xiangyou muzhi youshou",
    "1FACC": "帝王蝶 蝴蝶 diwangdie hudie",
    "1FADD": "腌黄瓜 醃黃瓜 泡菜 yanhuanggua paocai",
    "1F6D9": "灯塔 燈塔 dengta",
    "1FA8B": "流星 陨石 隕石 liuxing yunshi",
    "1FA8C": "橡皮擦 xiangpica",
    "1FA8D": "带柄网 帶柄網 抄网 抄網 捕虫网 捕蟲網 daibingwang chaowang",
}
TONES = ["浅肤色 淺膚色", "中浅肤色 中淺膚色", "中等肤色 中等膚色", "中深肤色 中深膚色", "深肤色 深膚色"]
ROW = re.compile(r"^([0-9A-F ]+)\s*;\s*(fully-qualified|component)\s*#\s*\S+\s+E([0-9.]+)\s+(.+)$")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download(url: str, expected: str, target: Path, maximum: int) -> Path:
    if target.exists() and digest(target.read_bytes()) == expected:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    with urlopen(Request(url, headers={"User-Agent": "ZeroInput pinned asset preparation"}), timeout=60) as response:
        data = response.read(maximum + 1)
    if len(data) > maximum or digest(data) != expected:
        raise ValueError("Pinned public asset checksum or size mismatch")
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(target)
    return target


def official_rows(path: Path) -> list[dict]:
    result, category = [], None
    text = path.read_text(encoding="utf-8")
    if "# Version: 18.0\n" not in text:
        raise ValueError("Unexpected Unicode version")
    for line in text.splitlines():
        if line.startswith("# group: "):
            category = GROUPS[line.removeprefix("# group: ")]
        match = ROW.match(line)
        if not match:
            continue
        codepoints = match[1].strip()
        value = "".join(chr(int(cp, 16)) for cp in codepoints.split())
        key = "_".join(cp.lower().zfill(4) for cp in codepoints.split() if cp != "FE0F")
        result.append(dict(sequence=codepoints, value=value, category=category,
                           key=key, status=match[2], version=match[3], english=match[4]))
    return result


def annotations() -> dict:
    result = {}
    for language in ("en", "zh", "zh_Hant"):
        for suffix in ("", "-derived"):
            for item in ET.parse(CACHE / f"{language}{suffix}.xml").iter("annotation"):
                key = item.attrib["cp"].replace("\ufe0f", "")
                record = result.setdefault(key, {"words": [], "names": {}})
                text = item.text or ""
                if text in ("↑↑↑", "∅∅∅"):
                    continue
                record["words"].append(text)
                if item.attrib.get("type") == "tts":
                    record["names"][language] = text
    return result


def variant_key(row: dict) -> str:
    name = row["english"].split(":")[0]
    if "holding hands" in name:
        return "people holding hands"
    if name.startswith("family"):
        return "family"
    if name in ("handshake", "kiss", "couple with heart"):
        return name
    points = [int(cp, 16) for cp in row["sequence"].split()]
    points = [cp for cp in points if not 0x1F3FB <= cp <= 0x1F3FF and cp != 0xFE0F]
    # Gender and hair presentation vary within the same user-facing action.
    while len(points) >= 2 and points[-2] == 0x200D and points[-1] in (0x2640, 0x2642, 0x1F9B0, 0x1F9B1, 0x1F9B2, 0x1F9B3):
        points = points[:-2]
    points = [0x1F9D1 if cp in (0x1F468, 0x1F469) else cp for cp in points]
    return "_".join(f"{cp:x}" for cp in points)


def write_catalog(rows: list[dict]) -> None:
    words = annotations()
    aliases = json.loads((DATA / "pinyin-aliases.json").read_text(encoding="utf-8"))
    aliases = {key.replace("\ufe0f", ""): value for key, value in aliases.items()}
    lines = ["# ZeroInput Unicode Emoji 18.0; fully-qualified RGI plus standalone components"]
    for row in rows:
        record = words.get(row["value"].replace("\ufe0f", ""), {"words": [], "names": {}})
        addition = NEW_NAMES.get(row["sequence"].split()[0], "")
        tones = [TONES[int(cp, 16) - 0x1F3FB] for cp in row["sequence"].split() if 0x1F3FB <= int(cp, 16) <= 0x1F3FF]
        chinese = record["names"].get("zh", "") or addition.split(" ")[0]
        if addition and tones:
            chinese += "：" + "、".join(t.split()[0] for t in tones)
        keywords = " ".join(dict.fromkeys(record["words"] + [addition] + tones + [
            aliases.get(row["value"].replace("\ufe0f", ""), ""), row["english"], row["sequence"],
        ])).replace(" | ", " ")
        if not chinese or any(x in keywords for x in ("\t", "\n", "\r")):
            raise ValueError("Missing Chinese name or invalid annotation")
        fields = [row["sequence"], row["category"], row["english"], chinese, keywords,
                  row["key"], variant_key(row), row["status"]]
        lines.append("\t".join(fields))
    catalog = ("\n".join(lines) + "\n").encode("utf-8")
    (DESTINATION / "catalog.tsv").write_bytes(catalog)
    version = ('package dev.zeroinput.ime.ui\n\n'
               '// Generated by tools/prepare-emoji.py from the pinned Unicode 18.0 release.\n'
               'internal object RgiEmojiVersion {\n'
               '    const val VERSION = "18.0"\n'
               f'    const val SHA256 = "{digest(catalog)}"\n'
               f'    const val COUNT = {len(rows)}\n'
               f'    const val MAX_UTF16_LENGTH = {max(len(row["value"].encode("utf-16-le")) // 2 for row in rows)}\n'
               '}\n')
    (NAMESPACE / "RgiEmojiVersion.kt").write_text(version, encoding="utf-8", newline="\n")


def prepare_image(row: dict, commit: str) -> None:
    from PIL import Image
    cached = download(f"https://raw.githubusercontent.com/googlefonts/noto-emoji/{commit}/{row['path']}",
                      row["sourceSha256"], CACHE / "png" / f"{row['key']}.png", 100_000)
    destination = DESTINATION / "images" / f"{row['key']}.webp"
    if destination.exists() and digest(destination.read_bytes()) == row["sha256"]:
        return
    with Image.open(cached) as source:
        if source.size != (128, 128):
            raise ValueError("Unexpected image size")
        source.convert("RGBA").save(destination, format="WEBP", lossless=True, method=6)
    if digest(destination.read_bytes()) != row["sha256"]:
        raise ValueError("WebP encoder differs from the pinned Pillow 12.2.0 output")


def verify(lock: dict) -> None:
    from PIL import Image
    official = official_rows(FIXTURE)
    lines = (DESTINATION / "catalog.tsv").read_text(encoding="utf-8").splitlines()[1:]
    entries = [line.split("\t") for line in lines]
    expected = {row["sequence"] for row in official}
    actual = {row[0] for row in entries}
    if actual != expected or len(entries) != len(expected):
        raise ValueError("Catalog differs from the exact official RGI/component set")
    if digest(FIXTURE.read_bytes()) != lock["sources"]["emoji-test.txt"]["sha256"]:
        raise ValueError("Official fixture checksum mismatch")
    assets = {row["key"]: row for row in lock["images"]}
    actual_images = {path.stem for path in (DESTINATION / "images").glob("*.webp")}
    if actual_images != {row[5] for row in entries} or actual_images != set(assets):
        raise ValueError("Artwork has missing or extra sequences")
    for key, row in assets.items():
        path = DESTINATION / "images" / f"{key}.webp"
        if digest(path.read_bytes()) != row["sha256"]:
            raise ValueError("Bundled image checksum mismatch")
        with Image.open(path) as image:
            if image.size != (128, 128) or image.mode != "RGBA" or image.getbbox() is None:
                raise ValueError("Invalid or empty offline artwork")
    catalog_hash = digest((DESTINATION / "catalog.tsv").read_bytes())
    if catalog_hash not in (NAMESPACE / "RgiEmojiVersion.kt").read_text():
        raise ValueError("Runtime catalog checksum is stale")
    components = sum(row["status"] == "component" for row in official)
    print(f"Unicode 18.0: {len(official) - components} RGI + {components} components; exact coverage, all artwork decoded")


def write_checksums(lock: dict) -> None:
    rows = [f"{digest((DESTINATION / 'catalog.tsv').read_bytes())}  catalog.tsv"]
    rows.extend(f"{row['sha256']}  images/{row['key']}.webp" for row in lock["images"])
    (DESTINATION / "checksums.sha256").write_text("\n".join(rows) + "\n", encoding="ascii", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Audit existing artifacts without network access")
    args = parser.parse_args()
    lock = json.loads((DATA / "sources.lock.json").read_text(encoding="utf-8"))
    if not args.verify:
        for name, source in lock["sources"].items():
            download(source["url"], source["sha256"], CACHE / name, 2_000_000)
        DESTINATION.mkdir(parents=True, exist_ok=True)
        (DESTINATION / "images").mkdir(exist_ok=True)
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(CACHE / "emoji-test.txt", FIXTURE)
        write_catalog(official_rows(FIXTURE))
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda row: prepare_image(row, lock["notoCommit"]), lock["images"]))
        write_checksums(lock)
    verify(lock)


if __name__ == "__main__":
    main()
