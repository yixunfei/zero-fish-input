"""Prepare matched, anonymous reading material from eligible P2 calibration carriers."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from random import SystemRandom
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from binary_prototype import bounded_file, layout
from joint_codec import CARRIER_BYTES, write_new
from p2_corpus import COUNTS, ID, canonical_json
from p2_theme_pools import FAMILIES, ThemePools, carrier_lines
from p2_theme_trial import POOL_LIMIT


def ordinary(verse, count, rng):
    previous, lines = "", []
    for _ in range(count):
        previous = rng.choice(verse.pool("linked", previous))
        lines.append(previous)
    return layout(lines).encode("utf-8")


def eligible_material(trial, report, rng):
    rule = report["selected_for_calibration_reading"]
    if rule not in ("same-family", "family-or-next"):
        raise ValueError("no scenery rule qualified for calibration reading")
    arm = report["measured"][rule]
    accepted = [row for row in arm["samples"] if row["recovered"] and row["pass20"]]
    if (len(arm["samples"]) != COUNTS["calibration"]
            or 2 * len(accepted) < COUNTS["calibration"]):
        raise ValueError("calibration capacity gate")
    source = trial / rule
    data = bounded_file(source / "pools.json", POOL_LIMIT)
    if sha256(data).hexdigest() != arm["pool_sha256"]:
        raise ValueError("scenery pool digest")
    record = json.loads(data)
    if tuple(record["families"]) != FAMILIES or record["rule"] != rule:
        raise ValueError("scenery markers mismatch")
    verse = ThemePools(record["opening"], record["pools"], rule)
    items = []
    for row in accepted:
        if not ID.fullmatch(row["id"]):
            raise ValueError("invalid calibration sample ID")
        surface = bounded_file(source / f"{row['id']}.txt", CARRIER_BYTES)
        if (sha256(surface).hexdigest() != row["carrier_sha256"]
                or len(surface) != row["surface_bytes"] or len(carrier_lines(surface)) != row["lines"]):
            raise ValueError("carrier evidence mismatch")
        control = ordinary(verse, row["lines"], rng)
        if len(surface) != len(control):
            raise ValueError("reading control length mismatch")
        items.extend(((row["id"], "carrier", surface), (row["id"], "ordinary", control)))
    return rule, items


def run(trial, output):
    source = bounded_file(trial / "results.json", 1024 * 1024)
    report = json.loads(source)
    if report["experiment"] != "scenery-continuity" or report["holdout_attempts"] != 0:
        raise ValueError("expected calibration evidence")
    rng = SystemRandom()
    rule, items = eligible_material(trial, report, rng)
    write_material(output, rule, items, rng, source, "作品围绕天时、水景与山林。")


def write_material(output, rule, items, rng, source, description):
    rng.shuffle(items)
    reader = output / "readers"
    reader.mkdir(parents=True, exist_ok=False)
    sheet = [
        "# 校准试读",
        "",
        f"匿名读者编号 ______。{description}请按编号分别阅读并独立填写。",
        "",
        "相邻句与整篇连贯均按 1–5 分。1 分表示经常跳跃或松散，3 分表示部分承接，5 分表示自然连贯。"
        "请记录是否愿意读完。整篇连贯可在通读后评分，未读完可留空。可在备注中摘录突兀的相邻句。",
        "",
        "| 编号 | 相邻句衔接 | 整篇连贯 | 愿意读完（是／否） | 备注 |",
        "|---|---|---|---|---|",
    ]
    key = {}
    for i, (source_id, kind, surface) in enumerate(items, 1):
        code = f"R{i:02d}"
        write_new(reader / f"{code}.txt", surface)
        sheet.append(f"| [{code}]({code}.txt) | | | | |")
        key[code] = {"sample": source_id, "kind": kind, "sha256": sha256(surface).hexdigest()}
    write_new(reader / "scores.md", ("\n".join(sheet) + "\n").encode())
    write_new(output / "organizer.json", canonical_json({
        "rule": rule, "evidence_sha256": sha256(source).hexdigest(), "materials": key,
    }))
    print(rule, len(items) // 2, "matched pairs", len(items), "reading texts")


def sparse_reading(trial, output, tang):
    from scene_roundtrip import CORPUS_LOCK, load_routes
    from sparse_verse import PERIOD
    from verse_scene_probe import parse

    source = bounded_file(trial / "report.json", 1024 * 1024)
    report = json.loads(source)
    lock = CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")
    records = [row for row in json.loads(lock)["records"] if row["split"] == "calibration"]
    routes, profiles, _ = load_routes(tang, essay=True)
    if (report["corpus_sha256"] != sha256(lock).hexdigest()
            or report["profiles"] != {k: v.hex() for k, v in profiles.items()}):
        raise ValueError("wrong sparse reading configuration")
    rule = "5-sparse-scene"
    route, rng, items = routes[rule], SystemRandom(), []
    passed = {}
    for i, (row, meta) in enumerate(zip(report["samples"], records, strict=True)):
        if row["id"] != meta["id"] or row["genre"] != meta["genre"] or row["bytes"] != meta["bytes"]:
            raise ValueError("wrong reading sample")
        arm = row["routes"][rule]
        if arm["result"] != "exact" or not arm["pass_20"]:
            continue
        surface = bounded_file(trial / "surfaces" / rule / f"{i:03}.txt", CARRIER_BYTES)
        if len(surface) > 5 * row["bytes"] or sha256(surface).hexdigest() != arm["carrier_sha256"]:
            raise ValueError("reading carrier differs from measured result")
        lines = parse(surface.decode("utf-8"), route.width)
        control = []
        for j in range(0, len(lines), PERIOD):
            group, _ = route.scenes.where[lines[j]]
            control.extend(rng.sample(route.scenes.rows[group], 2))
            control.extend(rng.choices(route.lines, k=len(lines[j + 2:j + PERIOD])))
        ordinary_text = layout(control).encode("utf-8")
        if len(surface) != len(ordinary_text) or surface == ordinary_text:
            raise ValueError("invalid matched reading control")
        items.extend(((row["id"], "carrier", surface), (row["id"], "ordinary", ordinary_text)))
        passed[row["genre"]] = passed.get(row["genre"], 0) + 1
    if any(2 * passed.get(genre, 0) < COUNTS["calibration"] for genre in ("free-narrative", "work-record")):
        raise ValueError("insufficient qualifying sparse carriers")
    write_material(output, rule, items, rng, source, "每篇都是五言集句。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trial", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sparse-source", type=Path)
    options = parser.parse_args()
    if options.sparse_source:
        sparse_reading(options.trial, options.output, options.sparse_source)
    else:
        run(options.trial, options.output)
