"""Measure frozen P1 on calibration only, with an optional training-source ablation."""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from time import perf_counter_ns

from binary_prototype import RMW, bounded_file
from compression_probe import envelope_budget
from joint_codec import (CARRIER_BYTES, JointCodec, Rejection, choose, metrics, read_input, write_new)
from p1_profile import Profile
from p2_corpus import COUNTS, GENRES, canonical_json, read_lock, samples
from p2_experiment import ReceiverProcess
from rime_location_codec import encode as position_encode
from rime_location_probe import chunks
from rmw_probe import add_probe_arguments, probe_arguments, resolve_probe


PROFILE_DOMAIN = b"ZeroInput/public-themed-verse/p2-position-training/v1/"
GB_TRAINING_DOMAIN = b"ZeroInput/public-themed-verse/p2-gb-training/v1/"


def gb_training_binding(position_binding, dictionary):
    return sha256(GB_TRAINING_DOMAIN + position_binding + dictionary).digest()


def read_dictionary(path, size):
    data = bounded_file(path, size)
    if len(data) != size:
        raise ValueError("training dictionary mismatch")
    return data


def load_codec(path, sending, dictionary=None, expected=None, *, gb_dictionary=None, rmw_probe=RMW):
    profile = Profile(path, sending=sending)
    if dictionary is None:
        if gb_dictionary is not None:
            raise ValueError("position training dictionary required")
        return JointCodec(profile, rmw_probe=rmw_probe)
    data = read_dictionary(dictionary, len(profile.position_dict))
    binding = sha256(PROFILE_DOMAIN + profile.digest + data).digest()
    if gb_dictionary is not None:
        gb = read_dictionary(gb_dictionary, len(profile.dicts["gb18030"]))
        binding = gb_training_binding(binding, gb)
        profile.dicts["gb18030"] = gb
    if binding.hex() != expected:
        raise ValueError("training dictionary mismatch")
    profile.position_dict = data
    return JointCodec(profile, binding=binding, rmw_probe=rmw_probe)


def recover_in_process(receiver, surface, key, dictionary, digest, scratch, *, gb_dictionary=None):
    files = [scratch / name for name in ("public-key", "carrier", "recovered")]
    files[0].write_bytes(key)
    files[1].write_bytes(surface)
    files[2].unlink(missing_ok=True)
    helper = receiver.rmw_options
    if dictionary is None:
        cli = receiver.entry.with_name("joint_codec.py")
        options = ["--profile", receiver.p1, *helper, "open", *files]
    else:
        cli = receiver.entry.with_name("p2_calibration.py")
        options = ["receive", receiver.p1, dictionary, digest, *files, *helper]
        if gb_dictionary is not None:
            options.extend(("--gb-dictionary", gb_dictionary))
    result = subprocess.run([sys.executable, "-E", "-S", "-B", cli, *options],
                            cwd=receiver.root, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"independent receiver rejected {result.stderr!r}")
    restored = bounded_file(files[2], 2048)
    files[2].unlink()
    return restored


def prepare_measurement(codec, item, raw):
    start = perf_counter_ns()
    generated = codec.build_streams(raw)
    streams, rejected = codec.verify_streams(raw, generated)
    row = {"id": item["id"], "genre": item["genre"], "original_bytes": len(raw),
           "sha256": item["sha256"], "position_dict_bytes": len(generated["position-dict"]),
           "stream_lengths": {mode: len(data) for mode, data in generated.items()},
           "shortest_stream_bytes": min(map(len, streams.values()), default=None),
           "target_bytes": 5 * len(raw), "candidates_ms": (perf_counter_ns() - start) / 1e6,
           "excluded": rejected, "recovered": False, "pass20": False}
    if not streams:
        row["reason"] = "frame-capacity"
        return row, generated, None
    mode = choose(streams)
    data = streams[mode]
    row.update(mode=mode, selected_stream_bytes=len(data), envelope_bytes=envelope_budget(3 + len(data)))
    return row, generated, data


def complete_measurement(codec, raw, receive, row, data):
    if data is None:
        return row
    selected_at = perf_counter_ns()
    try:
        key = os.urandom(32)
        surface = codec.seal_stream(raw, key, row["mode"], data)
    except Rejection as error:
        if str(error) not in ("frame-capacity", "carrier-capacity"):
            raise
        row["reason"] = str(error)
        if str(error) == "carrier-capacity":
            # In the locked linked layout the UTF-8 cap is always the tighter hard cap.
            row["target_excess_lower_bound"] = max(0, CARRIER_BYTES + 1 - row["target_bytes"])
        return row
    sealed_at = perf_counter_ns()
    restored = receive(surface, key)
    end = perf_counter_ns()
    if restored != raw:
        raise ValueError("independent public excerpt recovery")
    row.update(metrics(len(raw), surface))
    row.update(recovered=True, target_excess_bytes=max(0, len(surface) - row["target_bytes"]),
               reason=None if row["pass20"] else "below-20", seal_ms=(sealed_at - selected_at) / 1e6,
               receive_ms=(end - sealed_at) / 1e6)
    return row


def measure(codec, item, raw, receive):
    row, _, data = prepare_measurement(codec, item, raw)
    return complete_measurement(codec, raw, receive, row, data)


def summary(rows, included=GENRES):
    totals = {}
    for genre in included:
        items = [row for row in rows if row["genre"] == genre]
        totals[genre] = {"attempts": len(items), "recovered": sum(r["recovered"] for r in items),
                         "pass20": sum(r["pass20"] for r in items),
                         "frame_capacity": sum(r["reason"] == "frame-capacity" for r in items),
                         "carrier_capacity": sum(r["reason"] == "carrier-capacity" for r in items),
                         "below20": sum(r["reason"] == "below-20" for r in items)}
    return totals


def training_inputs(lock, corpus):
    texts = {genre: [] for genre in GENRES}
    for item, raw in samples(lock, corpus, "training"):
        texts[item["genre"]].append((item, raw))
    return [entry for pair in zip(*(texts[g] for g in GENRES), strict=True) for entry in pair]


def training_trial(profile, corpus, lock, lock_sha256, old_report, output):
    prior = json.loads(bounded_file(old_report, 1024 * 1024))
    if (prior["corpus_sha256"] != lock_sha256 or prior["p1_sha256"] != profile.digest.hex()
            or prior["experiment"] != "p1"):
        raise ValueError("baseline does not identify this frozen calibration")
    prior_rows = {r["id"]: r for r in prior["samples"]}
    calibration = [r for r in lock["records"] if r["split"] == "calibration"]
    if set(prior_rows) != {r["id"] for r in calibration} or any(
            prior_rows[r["id"]]["sha256"] != r["sha256"] for r in calibration):
        raise ValueError("baseline rows do not identify this frozen calibration")
    included = tuple(g for g, totals in summary(prior_rows.values()).items()
                     if 2 * totals["pass20"] < COUNTS["calibration"])
    if not included:
        raise ValueError("calibration capacity supports the blind packet branch")
    balanced = "\n".join(raw.decode() for _, raw in training_inputs(lock, corpus))
    streams = b"".join(position_encode(raw, profile.positions.terms(raw)) for raw in chunks(balanced))
    budget = len(profile.position_dict)
    if len(streams) < budget:
        raise ValueError("independent position dictionary is shorter than the frozen budget")
    dictionary = streams[-budget:]
    binding = sha256(PROFILE_DOMAIN + profile.digest + dictionary).hexdigest()
    path = output / "position-zdict.bin"
    write_new(path, dictionary)
    return path, binding, included, prior_rows


def compare_to_p1(rows, prior):
    for row in rows:
        original = prior[row["id"]]
        before, after = original.get("envelope_bytes"), row.get("envelope_bytes")
        row["position_dict_delta"] = row["position_dict_bytes"] - original["position_dict_bytes"]
        row["envelope_delta"] = None if before is None or after is None else after - before
        row["new_pass"] = row["pass20"] and not original["pass20"]
        row["lost_pass"] = original["pass20"] and not row["pass20"]


def run(args):
    corpus = args.corpus.resolve()
    lock, digest = read_lock(corpus / "lock.json")
    if digest != args.lock_sha256:
        raise ValueError("corpus lock digest")
    profile = Profile(args.profile, sending=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    rmw_probe = resolve_probe(args, RMW)
    tx, trained, binding, included, prior = JointCodec(profile, rmw_probe=rmw_probe), None, None, GENRES, None
    if args.baseline is not None:
        trained, binding, included, prior = training_trial(profile, corpus, lock, digest, args.baseline, output)
        tx = load_codec(args.profile, True, trained, binding, rmw_probe=rmw_probe)
    rows = []
    with tempfile.TemporaryDirectory(prefix="themed-calibration-") as folder:
        scratch = Path(folder).resolve()
        receiver = ReceiverProcess(scratch / "receiver", Profile(args.profile, sending=False),
                                   rmw_options=probe_arguments(args, RMW))
        dictionary = None
        if trained is not None:
            dictionary = receiver.root / "trained-dictionary.bin"
            shutil.copyfile(trained, dictionary)
        for item, raw in samples(lock, corpus, "calibration"):
            if item["genre"] in included:
                rows.append(measure(tx, item, raw, lambda surface, key: recover_in_process(
                    receiver, surface, key, dictionary, binding, scratch)))
                print(item["id"], rows[-1].get("mode", "no-frame"), rows[-1]["reason"] or "PASS20", flush=True)
    if prior is not None:
        compare_to_p1(rows, prior)
    result = {"experiment": "p1" if trained is None else "independent-training-position-dict",
              "p1_sha256": profile.digest.hex(), "corpus_sha256": digest,
              "binding": tx.binding.hex(), "position_dictionary_bytes": len(profile.position_dict),
              "position_dictionary_sha256": sha256(tx.profile.position_dict).hexdigest(),
              "calibration": summary(rows, included), "holdout_attempts": 0, "samples": rows}
    if prior is not None:
        for genre, totals in result["calibration"].items():
            group = [row for row in rows if row["genre"] == genre]
            totals.update(new_passes=sum(r["new_pass"] for r in group), lost_passes=sum(r["lost_pass"] for r in group),
                          drop_buckets=sum(r["envelope_delta"] is not None and r["envelope_delta"] < 0 for r in group))
    write_new(output / "results.json", canonical_json(result))
    print(json.dumps(result["calibration"], sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    measure_command = commands.add_parser("run")
    measure_command.add_argument("profile", type=Path)
    measure_command.add_argument("corpus", type=Path)
    measure_command.add_argument("output", type=Path)
    measure_command.add_argument("--lock-sha256", required=True)
    measure_command.add_argument("--baseline", type=Path, help="compare a frozen baseline to independent training")
    add_probe_arguments(measure_command)
    receive = commands.add_parser("receive")
    for name in ("profile", "dictionary"):
        receive.add_argument(name, type=Path)
    receive.add_argument("digest")
    for name in ("key", "carrier", "result"):
        receive.add_argument(name, type=Path)
    receive.add_argument("--gb-dictionary", type=Path)
    add_probe_arguments(receive)
    args = parser.parse_args()
    if args.command == "run":
        run(args)
        return
    codec = load_codec(args.profile, False, args.dictionary, args.digest,
                       gb_dictionary=args.gb_dictionary, rmw_probe=resolve_probe(args, RMW))
    key = read_input(args.key, 32, "invalid-key")
    carrier = read_input(args.carrier, CARRIER_BYTES, "carrier-rejected")
    write_new(args.result, codec.open(carrier, key))


if __name__ == "__main__":
    main()
