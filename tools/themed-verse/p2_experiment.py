"""Run the paired P2 public regression experiments and end-to-end benchmark."""
import argparse
from contextlib import ExitStack
from functools import partial
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
from statistics import median
import subprocess
import sys
import tempfile
from time import perf_counter_ns
import zlib

from binary_prototype import CRYPTO, RMW
from check_p2_layouts import run as check_layouts
from compression_probe import envelope_budget
from joint_codec import STREAM_LIMIT, JointCodec, Rejection, metrics, write_new
from p1_corpus import samples
from p1_profile import MODE_IDS as P1_MODES, Profile
from p2_bits import KEY, Token, p1_cost, p1_stream, p1_tokens
from p2_codec import ExperimentCodec, file_operation, load_codec, pick
from p2_profile import MODES, VARIANTS, ExperimentProfile, build
from p2_segments import cached_terms, coverage, segment
from rime_location_codec import encode as p1_encode
from rime_probe_device import deployment, query_at
from verse import ROOT


TOOLS = Path(__file__).resolve().parent
PIPELINES = ("p1", "p2-static", "p2-native")
P1_POSITION = ("position", "position-zlib", "position-dict")


class ReceiverProcess:
    def __init__(self, folder, p1, p2=None, *, rmw_options=(), include_rmw=True):
        self.root = folder.resolve()
        self.rmw_options = rmw_options
        scripts = self.root / "tools/themed-verse"
        scripts.mkdir(parents=True)
        for source in TOOLS.glob("*.py"):
            shutil.copyfile(source, scripts / source.name)
        executables = (CRYPTO, RMW) if include_rmw and not rmw_options else (CRYPTO,)
        for executable in executables:
            target = self.root / executable.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(executable, target)
        self.entry = scripts / "p2_codec.py"
        self.p1 = self.root / "p1"
        self.p1.mkdir()
        for source in p1.receive_files:
            shutil.copyfile(source, self.p1 / source.name)
        if p2 is not None:
            self.p2 = self.root / "profile.json"
            shutil.copyfile(p2, self.p2)


def child(entry, cwd, options, operation, files, expect_error=None):
    started = perf_counter_ns()
    command = [sys.executable, "-E", "-S", "-B", entry, *options, operation, *files]
    result = subprocess.run(command, cwd=cwd, capture_output=True, timeout=90)
    elapsed = (perf_counter_ns() - started) / 1e6
    if expect_error is not None:
        if not result.returncode or result.stderr.strip() != expect_error.encode() or files[-1].exists():
            raise RuntimeError(f"expected rejection {expect_error}")
        return None
    if result.returncode:
        raise RuntimeError(f"child {operation} rejected {result.stderr!r}")
    detail = json.loads(result.stdout)
    detail["wall_ms"] = elapsed
    return detail


def arguments(p1, p2=None, digest=None, native=None):
    options = ["--p1", p1]
    if p2 is not None:
        options.extend(("--p2", p2, "--digest", digest))
    if native is not None:
        options.extend(("--native", *native))
    return options


def surface_result(tx, rx, raw, key, mode, stream):
    report = {"stream_bytes": len(stream), "mode": mode, "envelope_bytes": None}
    if not 1 <= len(stream) <= STREAM_LIMIT:
        return dict(report, failure="frame-capacity", recovered=False, pass20=False), None
    report["envelope_bytes"] = envelope_budget(3 + len(stream))
    try:
        surface = tx.seal_stream(raw, key, mode, stream)
    except Rejection as error:
        if str(error) != "carrier-capacity":
            raise
        return dict(report, failure=str(error), recovered=False, pass20=False), None
    if rx.open(surface, key) != raw:
        raise ValueError("independent receiver mismatch")
    report.update(metrics(len(raw), surface))
    report.update(failure=None if report["pass20"] else "below-20", recovered=True,
                  lines=surface.count("。".encode()) + surface.count("，".encode()))
    return report, surface


def comparison(tx, rx, old_tx, old_rx, name, raw):
    key = os.urandom(32)
    streams, excluded, variants, native = tx.inspect(raw, details=True)
    winner = pick(streams, P1_MODES)
    outcomes, surfaces = {}, {}
    for mode in dict.fromkeys((winner, *P1_POSITION, *VARIANTS)):
        if mode not in streams:
            continue
        sender, receiver = (old_tx, old_rx) if mode in P1_MODES else (tx, rx)
        outcomes[mode], surfaces[mode] = surface_result(sender, receiver, raw, key, mode, streams[mode])
    fixed = p1_tokens(streams["position"])
    base_coverage = coverage(raw, fixed, old_tx.profile.positions)
    word_counts = {"bits": p1_cost(fixed), "coverage": base_coverage}
    selectors = {"p1": winner}
    selectors["p2-static"] = pick(streams, (m for m in MODES if not m.startswith("key-")))
    if native is not None:
        selectors["p2-native"] = pick(streams, MODES)
    selected = {}
    witnesses = {}
    p2_fallbacks = {}
    for group, mode in selectors.items():
        if group == "p1" or mode not in P1_MODES:
            outcome, surface = outcomes[mode], surfaces[mode]
        else:
            if mode not in p2_fallbacks:
                p2_fallbacks[mode] = surface_result(tx, rx, raw, key, mode, streams[mode])
            outcome, surface = p2_fallbacks[mode]
        selected[group] = outcome
        if surface is not None:
            witnesses[group] = surface
    for layout_name, record in variants.items():
        group = [mode for mode in streams if mode.startswith(layout_name + "/")]
        record["winner"] = pick(streams, group)
    row = {"name": name, "input_bytes": len(raw), "sha256": sha256(raw).hexdigest(),
           "p1_position": word_counts, "layouts": variants, "excluded_p1": excluded,
           "native": native, "outcomes": outcomes, "selected": selected}
    witnesses.update({name: surfaces[record["winner"]] for name, record in variants.items()
                      if surfaces[record["winner"]] is not None})
    return row, key, witnesses


def check_independent(receiver, options, p1_options, scratch, raw, key, witnesses):
    key_path, source, result = (scratch / name for name in ("key", "verse", "recovered"))
    key_path.write_bytes(key)
    for name, surface in witnesses.items():
        result.unlink(missing_ok=True)
        source.write_bytes(surface)
        child(receiver.entry, receiver.root, p1_options if name == "p1" else options, "open",
              (key_path, source, result))
        if result.read_bytes() != raw:
            raise ValueError("fresh receiver bytes")
    result.unlink(missing_ok=True)


def check_carrier_failures(tx, rx, receiver, options, p1_options, scratch):
    key_path, carrier, result = (scratch / name for name in ("key", "verse", "recovered"))
    key = os.urandom(32)
    key_path.write_bytes(key)
    raw = "公开夹具𠀀\r\ne\u0301🫠\0".encode()
    candidates, _, _, _ = tx.inspect(raw, details=False)
    mode = "freq-reseg/dict"
    surface = tx.seal_stream(raw, key, mode, candidates[mode])
    cipher = tx.read_ciphertext(surface)
    for invalid in (surface[:-3], surface.replace(b"\n", b"\r\n", 1),
                    tx.render_ciphertext(cipher[:-1] + bytes([cipher[-1] ^ 1]))):
        carrier.write_bytes(invalid)
        child(receiver.entry, receiver.root, options, "open", (key_path, carrier, result),
              "carrier-rejected")
    carrier.write_bytes(surface)
    child(receiver.entry, receiver.root, p1_options, "open", (key_path, carrier, result),
          "carrier-rejected")
    different = list(options)
    i = different.index("--digest") + 1
    different[i] = "0" * 64
    child(receiver.entry, receiver.root, different, "open", (key_path, carrier, result),
          "p2-experiment-rejected")
    if tx.query is None:
        return
    # A known whole phrase exercises the real key path even if row IDs win its normal trial.
    word = bytes.fromhex("e6988ee69c88e69dbee997b4e785a7")
    if tx.query({"mysjz"})["mysjz"][0].encode() != word:
        raise ValueError("locked whole-phrase replay")
    for mode in ("key-direct/bare", "key-rank/dict"):
        name, packing = VARIANTS[mode]
        packed, _ = tx.experiment.layouts[name].encode([Token(KEY, ("mysjz", 0))])
        if packing == "dict":
            from binary_prototype import deflate
            packed = deflate(packed, tx.experiment.dictionary(name, packing))
        surface = tx.seal_stream(word, key, mode, packed)
        if rx.open(surface, key) != word:
            raise ValueError("separate native oracle")
        check_independent(receiver, options, p1_options, scratch, word, key, {mode: surface})


def lookup_trial(index, raw):
    durations = []
    for _ in range(3):
        start = perf_counter_ns()
        terms = index.terms(raw)
        found = perf_counter_ns()
        baseline = p1_encode(raw, terms)
        old = perf_counter_ns()
        cached = cached_terms(index, raw)
        matched = perf_counter_ns()
        optimized = p1_stream(segment(raw, cached))
        end = perf_counter_ns()
        if terms != cached or baseline != optimized:
            raise ValueError("lookup or segmentation changed the P1 stream")
        durations.append([(found - start) / 1e6, (old - found) / 1e6,
                          (matched - old) / 1e6, (end - matched) / 1e6])
    return dict(zip(("old_lookup_ms", "old_segment_ms", "cached_lookup_ms", "compact_segment_ms"),
                    map(median, zip(*durations))))


def timed_pair(name, raw, stack, scratch, repeats):
    tx_options, rx_options, tx, rx, receiver = stack
    src, key, carrier, result = (scratch / name for name in ("original", "key", "verse", "recovered"))
    values = []
    for i in range(repeats + 1):
        carrier.unlink(missing_ok=True)
        result.unlink(missing_ok=True)
        key.write_bytes(os.urandom(32))
        src.write_bytes(raw)
        if i == 0:
            sent = child(TOOLS / "p2_codec.py", scratch, tx_options, "seal", (key, src, carrier))
            src.unlink()
            opened = child(receiver.entry, receiver.root, rx_options, "open", (key, carrier, result))
        else:
            t0 = perf_counter_ns()
            sent = file_operation(tx, "seal", key, src, carrier)
            t1 = perf_counter_ns()
            opened = file_operation(rx, "open", key, carrier, result)
            t2 = perf_counter_ns()
            src.unlink()
            sent["wall_ms"], opened["wall_ms"] = (t1 - t0) / 1e6, (t2 - t1) / 1e6
        if result.read_bytes() != raw:
            raise ValueError("timed pair changed bytes")
        values.append({"temperature": "cold" if i == 0 else "warm", "seal": sent, "open": opened,
                       "total_ms": sent["wall_ms"] + opened["wall_ms"]})
    return {"name": name, "runs": values}


def run_trials(p1_path, folder, native_tx, native_rx, repeats):
    old_tx, old_rx = (load_codec(p1_path, None, None, None, sending=sending)
                      for sending in (True, False))
    query_tx = partial(query_at, *native_tx) if native_tx else None
    query_rx = partial(query_at, *native_rx) if native_rx else None
    p2_file = folder / "profile.json"
    data = build(old_tx.profile, query_tx)
    write_new(p2_file, data)
    digest = sha256(data).hexdigest()
    tx, rx = (ExperimentCodec(ExperimentProfile(old.profile, p2_file, digest), query)
              for old, query in ((old_tx, query_tx), (old_rx, query_rx)))
    static = ExperimentCodec(tx.experiment), ExperimentCodec(rx.experiment)
    reports, perf = [], dict((name, []) for name in PIPELINES if name != "p2-native" or native_tx)
    with tempfile.TemporaryDirectory(prefix="verse-p2-") as work:
        scratch = Path(work).resolve()
        receiver = ReceiverProcess(scratch / "receiver", old_rx.profile, p2_file)
        old_options = arguments(p1_path), arguments(receiver.p1)
        static_options = (arguments(p1_path, p2_file, digest),
                          arguments(receiver.p1, receiver.p2, digest))
        p2_options = (arguments(p1_path, p2_file, digest, native_tx),
                      arguments(receiver.p1, receiver.p2, digest, native_rx))
        stacks = {"p1": (*old_options, old_tx, old_rx, receiver),
                  "p2-static": (*static_options, *static, receiver)}
        if native_tx:
            stacks["p2-native"] = (*p2_options, tx, rx, receiver)
        check_carrier_failures(tx, rx, receiver, p2_options[1], old_options[1], scratch)
        for name, raw in samples():
            report, key, witnesses = comparison(tx, rx, old_tx, old_rx, name, raw)
            check_independent(receiver, p2_options[1], old_options[1], scratch, raw, key, witnesses)
            report["segmentation_timing"] = lookup_trial(old_tx.profile.positions, raw)
            reports.append(report)
            print("PASS", name, "byte-for-byte", flush=True)
            if name.endswith(".md") and repeats is not None:
                for pipeline, stack in stacks.items():
                    perf[pipeline].append(timed_pair(name, raw, stack, scratch, repeats))
    return {"profile_sha256": digest, "profile_bytes": len(data), "p1_sha256": old_tx.profile.digest.hex(),
            "zlib": zlib.ZLIB_RUNTIME_VERSION, "samples": reports, "timings": perf}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("p1", type=Path)
    parser.add_argument("output", type=Path, help="new result directory")
    parser.add_argument("--emulator")
    parser.add_argument("--native-build", type=Path)
    parser.add_argument("--warm-repeats", type=int, default=3)
    parser.add_argument("--capacity-only", action="store_true")
    args = parser.parse_args()
    if bool(args.emulator) != bool(args.native_build):
        parser.error("provide both native replay options")
    if args.emulator and not re.fullmatch(r"emulator-[0-9]+", args.emulator):
        parser.error("an isolated emulator serial is required")
    if not 1 <= args.warm_repeats <= 10:
        parser.error("warm repeats must be between 1 and 10")
    check_layouts()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=False)
    with ExitStack() as scope:
        native = None, None
        if args.emulator:
            native = tuple((args.emulator, scope.enter_context(deployment(args.emulator, args.native_build)))
                           for _ in range(2))
        report = run_trials(args.p1.resolve(), folder, *native,
                            None if args.capacity_only else args.warm_repeats)
    write_new(folder / "results.json", (json.dumps(report, indent=2) + "\n").encode())
    print("PASS integrated capacity and timing checks", report["profile_sha256"])


if __name__ == "__main__":
    main()
