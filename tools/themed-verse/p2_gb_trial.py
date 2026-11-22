"""Compare same-budget GB18030 training on frozen P2 calibration and P1 regression."""
import argparse
from functools import partial
from hashlib import sha256
import json
from pathlib import Path
import shutil
import tempfile

from binary_prototype import RMW, bounded_file, inflate
from joint_codec import write_new
from p1_corpus import samples as regression_samples
from p1_profile import Profile
from p2_calibration import (complete_measurement, gb_training_binding, load_codec,
                            prepare_measurement, recover_in_process, training_inputs)
from p2_corpus import GENRES, canonical_json, read_lock, samples
from p2_experiment import ReceiverProcess
from rmw_probe import add_probe_arguments, probe_arguments, resolve_probe


FREE_FIVE_CHARACTER_POOL = 213_300
REFERENCE_FIELDS = ("mode", "selected_stream_bytes", "shortest_stream_bytes",
                    "position_dict_bytes", "excluded", "envelope_bytes")


def encode_gb(raw):
    encoded = raw.decode("utf-8").encode("gb18030")
    if encoded.decode("gb18030").encode("utf-8") != raw:
        raise ValueError("non-exact GB18030 conversion")
    return encoded


def train_dictionary(inputs, previous, path):
    encoded = [encode_gb(raw) for _, raw in inputs]
    stream = b"\n".join(encoded)
    if len(stream) < len(previous):
        raise ValueError("GB18030 training shorter than the frozen byte budget")
    dictionary = stream[-len(previous):]
    write_new(path, dictionary)
    info = {
        "training_order": [{"id": item["id"], "gb18030_bytes": len(data)}
                           for (item, _), data in zip(inputs, encoded, strict=True)],
        "training_stream_bytes": len(stream),
        "training_stream_sha256": sha256(stream).hexdigest(),
        "bytes": len(dictionary),
        "previous_sha256": sha256(previous).hexdigest(),
        "sha256": sha256(dictionary).hexdigest(),
    }
    return dictionary, info


def read_reference(path, lock, lock_digest, profile_digest):
    data = bounded_file(path, 1024 * 1024)
    report = json.loads(data)
    if (report["experiment"] != "independent-training-position-dict"
            or report["corpus_sha256"] != lock_digest
            or report["p1_sha256"] != profile_digest or report["holdout_attempts"] != 0):
        raise ValueError("reference is not the frozen position-only calibration")
    expected = {row["id"]: (row["genre"], row["sha256"], row["bytes"])
                for row in lock["records"] if row["split"] == "calibration"}
    rows = report["samples"]
    actual = {row["id"]: (row["genre"], row["sha256"], row["original_bytes"]) for row in rows}
    if len(rows) != len(expected) or actual != expected:
        raise ValueError("reference calibration inputs differ from the lock")
    return report, {row["id"]: row for row in rows}, sha256(data).hexdigest()


def add_capacity_budget(before, after, verse):
    # Use the largest linked group at every step to bound the best possible stream.
    lines = (4 * before["target_bytes"] + 4) // 73
    span = len(verse.theme) * max(map(len, verse.groups)) ** (lines - 1)
    max_bytes = (span.bit_length() - 1) // 8
    max_envelope = 98 + 64 * ((max_bytes - 98) // 64)
    stream_budget = max(0, max_envelope - 137)
    free_span = FREE_FIVE_CHARACTER_POOL ** lines
    for row in (before, after):
        shortest, envelope = row["shortest_stream_bytes"], row.get("envelope_bytes")
        row.update(stream_budget_bytes=stream_budget,
                   stream_gap_bytes=None if shortest is None else max(0, shortest - stream_budget),
                   full_pool_bound=envelope is not None and free_span >= (1 << (8 * envelope)))


def compare(reference, trial, item, raw, receive_reference, receive_trial, previous=None):
    before, old_streams, old_selected = prepare_measurement(reference, item, raw)
    after, new_streams, new_selected = prepare_measurement(trial, item, raw)
    old_gb, new_gb = old_streams.pop("dict-gb"), new_streams.pop("dict-gb")
    if old_streams != new_streams:
        raise ValueError("a stream other than GB18030 has changed")
    gb_raw = encode_gb(raw)
    for codec, stream in ((reference, old_gb), (trial, new_gb)):
        if inflate(stream, 4 * len(raw), codec.profile.dicts["gb18030"]) != gb_raw:
            raise ValueError("GB18030 stream did not recover exactly")
    if previous is None:
        complete_measurement(reference, raw, receive_reference, before, old_selected)
    else:
        if any(before.get(key) != previous.get(key) for key in REFERENCE_FIELDS):
            raise ValueError(f"position-only stream or selector regression for {item['id']}")
        before.update(previous, stream_lengths=before["stream_lengths"])
    complete_measurement(trial, raw, receive_trial, after, new_selected)
    add_capacity_budget(before, after, trial.profile.verse)
    old_envelope, new_envelope = before.get("envelope_bytes"), after.get("envelope_bytes")
    return {"before": before, "after": after, "gb_stream_delta": len(new_gb) - len(old_gb),
            "envelope_delta": None if None in (old_envelope, new_envelope) else new_envelope - old_envelope,
            "new_pass": after["pass20"] and not before["pass20"],
            "lost_pass": before["pass20"] and not after["pass20"]}


def summarize(pairs):
    before, after = ([row[arm] for row in pairs] for arm in ("before", "after"))
    totals = {"attempts": len(pairs),
              "gb_stream_shorter": sum(row["gb_stream_delta"] < 0 for row in pairs),
              "gb_stream_longer": sum(row["gb_stream_delta"] > 0 for row in pairs),
              "lower_envelope": sum((row["envelope_delta"] or 0) < 0 for row in pairs),
              "higher_envelope": sum((row["envelope_delta"] or 0) > 0 for row in pairs),
              "new_passes": sum(row["new_pass"] for row in pairs),
              "lost_passes": sum(row["lost_pass"] for row in pairs)}
    for arm, rows in (("before", before), ("after", after)):
        gaps = sorted(row["stream_gap_bytes"] for row in rows if row["stream_gap_bytes"] is not None)
        totals[arm] = {"recovered": sum(row["recovered"] for row in rows),
                       "pass20": sum(row["pass20"] for row in rows),
                       "draft_fits": sum(row.get("draft_fits", False) for row in rows),
                       "gb_wins": sum(row.get("mode") == "dict-gb" for row in rows),
                       "gap_range_bytes": [gaps[0], gaps[-1]] if gaps else None,
                       "tenth_gap_bytes": gaps[9] if len(gaps) >= 10 else None,
                       "full_pool_bound": sum(row["full_pool_bound"] for row in rows)}
    return totals


def measure_sets(args, lock, prior, reference, trial, position, gb):
    pairs, regression = [], []
    with tempfile.TemporaryDirectory(prefix="themed-gb-training-") as folder:
        scratch = Path(folder).resolve()
        receiver = ReceiverProcess(scratch / "receiver", Profile(args.profile, sending=False),
                                   rmw_options=probe_arguments(args, RMW))
        position_copy = receiver.root / "trained-position.bin"
        gb_copy = receiver.root / "trained-gb18030.bin"
        shutil.copyfile(position, position_copy)
        shutil.copyfile(gb, gb_copy)
        receive = partial(recover_in_process, receiver, dictionary=position_copy, scratch=scratch)
        old_receive = partial(receive, digest=reference.binding.hex())
        new_receive = partial(receive, digest=trial.binding.hex(), gb_dictionary=gb_copy)
        for item, raw in samples(lock, args.corpus, "calibration"):
            pair = compare(reference, trial, item, raw, old_receive, new_receive, prior[item["id"]])
            pairs.append(pair)
            print(item["id"], pair["after"].get("mode", "no-frame"), pair["envelope_delta"],
                  pair["after"]["reason"] or "PASS20", flush=True)
        for name, raw in regression_samples():
            if name.endswith(".md"):
                item = {"id": name, "genre": "p1-regression", "sha256": sha256(raw).hexdigest()}
                regression.append(compare(reference, trial, item, raw, old_receive, new_receive))
    if len(regression) != 9:
        raise ValueError("expected nine frozen P1 regression documents")
    return pairs, regression


def run(args):
    lock, lock_digest = read_lock(args.corpus / "lock.json")
    if lock_digest != args.lock_sha256:
        raise ValueError("corpus lock digest")
    profile = Profile(args.profile, sending=False)
    old_report, prior, report_digest = read_reference(
        args.position_trial / "results.json", lock, lock_digest, profile.digest.hex()
    )
    position = args.position_trial / "position-zdict.bin"
    rmw_probe = resolve_probe(args, RMW)
    reference = load_codec(args.profile, True, position, old_report["binding"], rmw_probe=rmw_probe)
    if sha256(reference.profile.position_dict).hexdigest() != old_report["position_dictionary_sha256"]:
        raise ValueError("reference position dictionary digest")
    args.output.mkdir(parents=True, exist_ok=False)
    gb = args.output / "gb18030-zdict.bin"
    dictionary, training = train_dictionary(training_inputs(lock, args.corpus),
                                            profile.dicts["gb18030"], gb)
    binding = gb_training_binding(reference.binding, dictionary)
    trial = load_codec(args.profile, True, position, binding.hex(), gb_dictionary=gb, rmw_probe=rmw_probe)
    pairs, regression = measure_sets(args, lock, prior, reference, trial, position, gb)
    summary = {genre: summarize([row for row in pairs if row["after"]["genre"] == genre])
               for genre in GENRES}
    report = {"experiment": "independent-training-gb18030-dict",
              "p1_sha256": profile.digest.hex(), "corpus_sha256": lock_digest,
              "reference_results_sha256": report_digest, "reference_binding": reference.binding.hex(),
              "binding": binding.hex(), "gb18030_training": training, "calibration": summary,
              "rmw_backend": "android-emulator" if args.rmw_emulator else "host",
              "regression": summarize(regression), "samples": pairs, "regression_samples": regression,
              "holdout_attempts": 0}
    write_new(args.output / "results.json", canonical_json(report))
    print(json.dumps({"calibration": summary, "regression": report["regression"]}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "corpus", "position_trial", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--lock-sha256", required=True)
    add_probe_arguments(parser)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
