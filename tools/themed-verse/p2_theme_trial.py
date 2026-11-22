"""Compare fixed P2 research-abstract envelopes under alternative scenery rules."""
import argparse
from copy import copy
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from binary_prototype import bounded_file, deflate
from compression_probe import envelope_budget
from joint_codec import CARRIER_BYTES, MODE_IDS, JointCodec, Rejection, metrics, read_input, write_new
from p1_profile import Profile
from p2_calibration import load_codec
from p2_corpus import COUNTS, GENRES, canonical_json, read_lock, samples
from p2_experiment import ReceiverProcess
from p2_gb_trial import encode_gb
from p2_theme_pools import (FAMILIES, ThemePools, build_pools, capacity_bounds, carrier_lines,
                            continuity, fits_budget, line_budget)
from verse import Resources


TRIAL_DOMAIN = b"ZeroInput/public-themed-verse/p2-scenery/v1/"
POOL_LIMIT = 8 * 1024 * 1024
WORK = "work-record"
BASELINE = "p1-linked"
MODE = "dict-gb"


class ThemeCodec(JointCodec):
    # This fixture reuses the selected GB18030 mode in all measured research abstracts.
    mode_ids = {MODE: MODE_IDS[MODE]}
    modes_by_id = {MODE_IDS[MODE]: MODE}


def configure(base, asset=None, expected=None):
    profile = copy(base.profile)
    binding = base.binding
    if asset is not None:
        binding = sha256(TRIAL_DOMAIN + binding + sha256(asset).digest()).digest()
    if expected is not None and binding.hex() != expected:
        raise ValueError("scenery configuration mismatch")
    if asset is not None:
        record = json.loads(asset)
        if tuple(record["families"]) != FAMILIES:
            raise ValueError("scenery markers mismatch")
        profile.verse = ThemePools(record["opening"], record["pools"], record["rule"])
    return ThemeCodec(profile, binding=binding)


def frozen_rows(path, expected, lock, lock_digest, profile_digest):
    data = bounded_file(path, 1024 * 1024)
    if sha256(data).hexdigest() != expected:
        raise ValueError("GB comparison digest")
    report = json.loads(data)
    if (report["experiment"] != "independent-training-gb18030-dict"
            or report["p1_sha256"] != profile_digest or report["corpus_sha256"] != lock_digest
            or report["holdout_attempts"] != 0):
        raise ValueError("GB comparison does not identify this calibration")
    wanted = {r["id"]: (r["genre"], r["sha256"], r["bytes"])
              for r in lock["records"] if r["split"] == "calibration"}
    rows = [pair["after"] for pair in report["samples"]]
    actual = {r["id"]: (r["genre"], r["sha256"], r["original_bytes"]) for r in rows}
    if len(rows) != len(wanted) or actual != wanted:
        raise ValueError("GB comparison calibration inputs differ from the lock")
    return report, rows


def fixed_streams(lock, corpus, rows, dictionary):
    by_id = {row["id"]: row for row in rows if row["genre"] == WORK}
    for item, raw in samples(lock, corpus, "calibration"):
        if item["id"] not in by_id:
            continue
        row = by_id[item["id"]]
        stream = deflate(encode_gb(raw), dictionary)
        if (row["mode"] != MODE or row["selected_stream_bytes"] != len(stream)
                or row["envelope_bytes"] != envelope_budget(3 + len(stream))
                or row["target_bytes"] != 5 * len(raw) or not row["recovered"]):
            raise ValueError("frozen selection or envelope changed")
        yield row, raw, stream


def new_receiver(folder, profile_dir, position, gb_dictionary):
    receiver = ReceiverProcess(folder / "receiver", Profile(profile_dir, sending=False), include_rmw=False)
    position_copy = receiver.root / "trained-position.bin"
    gb_copy = receiver.root / "trained-gb18030.bin"
    shutil.copyfile(position, position_copy)
    shutil.copyfile(gb_dictionary, gb_copy)
    return receiver, position_copy, gb_copy


def recover(receiver, inputs, pool_file, surface, key, scratch, expect=None):
    keyfile, carrier, output = (scratch / name for name in ("fixture.key", "carrier.txt", "recovered"))
    keyfile.write_bytes(key)
    carrier.write_bytes(surface)
    output.unlink(missing_ok=True)
    cli = receiver.entry.with_name("p2_theme_trial.py")
    args = ["receive", receiver.p1, *inputs, keyfile, carrier, output]
    if pool_file is not None:
        args.extend(("--pools", pool_file))
    child = subprocess.run([sys.executable, "-E", "-S", "-B", cli, *args],
                           cwd=receiver.root, capture_output=True, timeout=60)
    if expect is not None:
        if not child.returncode or child.stderr.strip() != expect.encode() or output.exists():
            raise ValueError(f"expected independent rejection {expect}")
        return None
    if child.returncode:
        raise ValueError(f"independent receiver rejected {child.stderr!r}")
    raw = bounded_file(output, 2048)
    output.unlink()
    return raw


def measure(codec, row, raw, stream, receiver, inputs, pools, scratch, output):
    record = {"id": row["id"], "sha256": row["sha256"], "original_bytes": len(raw),
              "mode": MODE, "stream_bytes": len(stream), "stream_sha256": sha256(stream).hexdigest(),
              "envelope_bytes": row["envelope_bytes"], "target_bytes": row["target_bytes"],
              "max_lines": line_budget(row["target_bytes"]), "recovered": False, "pass20": False}
    key = os.urandom(32)
    try:
        surface = codec.seal_stream(raw, key, MODE, stream)
    except Rejection as error:
        if str(error) != "carrier-capacity":
            raise
        record["reason"] = str(error)
        return record
    if recover(receiver, inputs, pools, surface, key, scratch) != raw:
        raise ValueError("independent recovery changed original bytes")
    record.update(metrics(len(raw), surface))
    record.update(continuity(carrier_lines(surface)))
    record.update(recovered=True, reason=None if record["pass20"] else "below-20",
                  target_delta_bytes=len(surface) - row["target_bytes"],
                  carrier_sha256=sha256(surface).hexdigest())
    write_new(output / f"{record['id']}.txt", surface)
    return record


def run_arms(arms, specimens, base, args, position, gb):
    results = {}
    with tempfile.TemporaryDirectory(prefix="themed-scenery-") as folder:
        scratch = Path(folder).resolve()
        receiver, pos_copy, gb_copy = new_receiver(scratch, args.profile, position, gb)
        for name, variant in arms.items():
            output = args.output / name
            output.mkdir()
            asset = None if variant is None else canonical_json(variant.to_record())
            codec = configure(base, asset)
            pools = None
            if asset is not None:
                local_pool = output / "pools.json"
                write_new(local_pool, asset)
                pools = receiver.root / f"{name}.json"
                shutil.copyfile(local_pool, pools)
            inputs = (pos_copy, gb_copy, base.binding.hex(), codec.binding.hex())
            records = [measure(codec, row, raw, stream, receiver, inputs, pools, scratch, output)
                       for row, raw, stream in specimens]
            accepted = [item for item in records if item["recovered"]]
            totals = {count: sum(row[count] for row in accepted) for count in (
                "pairs", "same_image", "same_family", "same_or_next", "repeated_lines", "adjacent_repeats")}
            results[name] = {"binding": codec.binding.hex(), "pool_sha256": sha256(asset).hexdigest() if asset else None,
                             "recovered": len(accepted), "pass20": sum(row["pass20"] for row in accepted),
                             "draft_fits": sum(row["draft_fits"] for row in accepted),
                             "attempts": len(records), "continuity": totals, "samples": records}
            print(name, "byte-exact", len(accepted), "pass20", results[name]["pass20"], flush=True)
    return results


def run(args):
    lock, digest = read_lock(args.corpus / "lock.json")
    if digest != args.lock_sha256:
        raise ValueError("corpus lock digest")
    profile = Profile(args.profile, sending=False)
    gb_report, rows = frozen_rows(args.gb_trial / "results.json", args.gb_results_sha256,
                                  lock, digest, profile.digest.hex())
    position = args.position_trial / "position-zdict.bin"
    gb = args.gb_trial / "gb18030-zdict.bin"
    base = load_codec(args.profile, False, position, gb_report["binding"], gb_dictionary=gb)
    resources = Resources()
    variants = build_pools(resources.free)
    work = [row for row in rows if row["genre"] == WORK]
    gate = (COUNTS["calibration"] + 1) // 2
    bounds = {BASELINE: capacity_bounds(work, base.profile.verse.theme, base.profile.verse.groups)}
    arms = {BASELINE: None}
    for variant in variants:
        bound = capacity_bounds(work, variant.theme, variant.groups)
        bounds[variant.rule] = bound
        if bound["upper_passes"] >= gate:
            arms[variant.rule] = variant
    needed = {}
    for genre in GENRES:
        group = [row for row in rows if row["genre"] == genre]
        rates = sorted(8 * row["envelope_bytes"] / line_budget(row["target_bytes"]) for row in group)
        needed[genre] = {"tenth_bits_per_line": rates[gate - 1],
                         "full_pool_bound": sum(fits_budget(row, len(resources.free), len(resources.free))
                                                for row in group)}
    specimens = list(fixed_streams(lock, args.corpus, rows, base.profile.dicts["gb18030"]))
    if len(specimens) != COUNTS["calibration"]:
        raise ValueError("expected twenty research abstracts")
    args.output.mkdir(parents=True, exist_ok=False)
    results = run_arms(arms, specimens, base, args, position, gb)
    selected = next((rule for rule in arms if rule != BASELINE and results[rule]["pass20"] >= gate), None)
    write_new(args.output / "results.json", canonical_json({
        "experiment": "scenery-continuity", "gb_results_sha256": args.gb_results_sha256,
        "corpus_sha256": digest, "p1_sha256": profile.digest.hex(), "holdout_attempts": 0,
        "naturalness_scored": 0, "screening": bounds, "full_pool": len(resources.free), "genre_needs": needed,
        "selected_for_calibration_reading": selected, "measured": results,
    }))


def receive(args):
    base = load_codec(args.profile, False, args.position, args.base_binding, gb_dictionary=args.gb_dictionary)
    asset = bounded_file(args.pools, POOL_LIMIT) if args.pools else None
    codec = configure(base, asset, args.binding)
    key = read_input(args.key, 32, "invalid-key")
    carrier = read_input(args.carrier, CARRIER_BYTES, "carrier-rejected")
    write_new(args.result, codec.open(carrier, key))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    task = sub.add_parser("run")
    for name in ("profile", "corpus", "position_trial", "gb_trial", "output"):
        task.add_argument(name, type=Path)
    task.add_argument("--lock-sha256", required=True)
    task.add_argument("--gb-results-sha256", required=True)
    reader = sub.add_parser("receive")
    for name in ("profile", "position", "gb_dictionary"):
        reader.add_argument(name, type=Path)
    reader.add_argument("base_binding")
    reader.add_argument("binding")
    for name in ("key", "carrier", "result"):
        reader.add_argument(name, type=Path)
    reader.add_argument("--pools", type=Path)
    args = parser.parse_args()
    if args.command == "run":
        run(args)
        return
    try:
        receive(args)
    except Rejection as error:
        sys.exit(str(error))
    except (ValueError, TypeError, KeyError, OSError):
        sys.exit("configuration-rejected")


if __name__ == "__main__":
    main()
