"""Authenticated public-fixture sender and receiver for the P2 experiments."""
import argparse
from functools import partial
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from time import perf_counter_ns
import zlib

from binary_prototype import deflate, inflate
from compression_probe import envelope_budget
from joint_codec import (CARRIER_BYTES, ORIGINAL_LIMIT, STREAM_LIMIT, JointCodec, Rejection,
                         metrics, read_input, write_new)
from p1_profile import Profile, ProfileError
from p2_bits import KEY, PREFIXES, ROW_WIDTHS, p1_stream, p1_tokens, recover
from p2_profile import MODES, VARIANTS, ExperimentProfile, section_tokens
from p2_replay import replay_terms
from p2_segments import cached_terms, coverage, segment
from rime_probe_device import REMOTE_PATTERN, query_at


def pick(streams, allowed):
    modes = [name for name in allowed if name in streams and 1 <= len(streams[name]) <= STREAM_LIMIT]
    if not modes:
        raise Rejection("frame-capacity")
    return min(modes, key=lambda name: (envelope_budget(3 + len(streams[name])), MODES[name]))


def pack_streams(raw_bits, exp, name):
    streams = {"bare": raw_bits, "zlib": zlib.compress(raw_bits, 9),
               "dict": deflate(raw_bits, exp.dictionary(name, "dict"))}
    if name == "prefix":
        streams["p1-dict"] = deflate(raw_bits, exp.dictionary(name, "p1-dict"))
    return streams


class ExperimentCodec(JointCodec):
    mode_ids = MODES
    modes_by_id = {code: name for name, code in MODES.items()}

    def __init__(self, experiment, query=None):
        super().__init__(experiment.p1, binding=experiment.digest)
        self.experiment = experiment
        self.query = query

    def decode(self, mode, stream, size):
        if mode not in VARIANTS:
            return super().decode(mode, stream, size)
        if not 1 <= size <= ORIGINAL_LIMIT or not 1 <= len(stream) <= STREAM_LIMIT:
            raise ValueError("codec bounds")
        return self.decode_variant(mode, stream, size, self.query)

    def decode_variant(self, mode, stream, size, query):
        name, packing = VARIANTS[mode]
        if name not in self.experiment.layouts:
            raise ValueError("mode unavailable")
        if packing != "bare":
            dictionary = None if packing == "zlib" else self.experiment.dictionary(name, packing)
            stream = inflate(stream, 4 * size + 2, dictionary)
        items = self.experiment.layouts[name].parse(stream, size)
        return recover(items, self.profile.positions, size, query)

    def inspect(self, raw, *, details):
        if not 1 <= len(raw) <= ORIGINAL_LIMIT:
            raise Rejection("invalid-input")
        try:
            raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise Rejection("invalid-input") from error
        exp = self.experiment
        terms = cached_terms(self.profile.positions, raw)
        position = p1_stream(segment(raw, terms))
        streams, rejected = super().candidates(raw, position=position)
        fixed = p1_tokens(position)
        events, reverse, native_cache, replay_stats = None, {}, {}, None
        if self.query is not None:
            if not exp.native:
                raise Rejection("missing-native-profile")
            events, reverse, native_cache, replay_stats = replay_terms(
                raw, terms, exp.readings, self.query, exp.layouts["key-rank"])
        variants = section_tokens(raw, terms, fixed, exp.layouts, events)
        measured = {}
        for name, items in variants.items():
            layout = exp.layouts[name]
            bare, bit_cost = layout.encode(items)
            for packing, stream in pack_streams(bare, exp, name).items():
                mode = f"{name}/{packing}"
                if self.decode_variant(mode, stream, len(raw), lambda keys: native_cache) != raw:
                    raise ValueError("variant did not round trip")
                streams[mode] = stream
            if details:
                stats = coverage(raw, items, self.profile.positions, reverse)
                if not layout.bitmap:
                    stats["row_bits"] = sum(layout.token_bits(t) - PREFIXES[t.kind][1]
                                            for t in items if t.kind in ROW_WIDTHS)
                if layout.keys:
                    keys = [t for t in items if t.kind == KEY]
                    stats["key_bits"] = sum(layout.token_bits(t) for t in keys)
                    stats["key_bytes"] = sum(len(reverse[t.value].encode()) for t in keys)
                measured[name] = {"bits": bit_cost, "coverage": stats}
        return streams, rejected, measured, replay_stats

    def seal(self, raw, key, require_rate=False):
        streams, _, _, _ = self.inspect(raw, details=False)
        mode = pick(streams, self.mode_ids)
        stream = streams[mode]
        surface = self.seal_stream(raw, key, mode, stream, require_rate)
        return surface, dict(metrics(len(raw), surface), mode=mode,
                             envelope_bytes=envelope_budget(3 + len(stream)))


def native_query(args):
    if args is None:
        return None
    serial, remote = args
    if not re.fullmatch(r"emulator-[0-9]+", serial) or not re.fullmatch(REMOTE_PATTERN, remote):
        raise ValueError("isolated emulator deployment required")
    return partial(query_at, serial, remote)


def load_codec(p1_path, p2_path, digest, query, *, sending):
    profile = Profile(p1_path, sending=sending)
    if p2_path is None:
        return JointCodec(profile)
    return ExperimentCodec(ExperimentProfile(profile, p2_path, digest), query)


def file_operation(codec, operation, key_path, source, output, require_rate=False):
    key = read_input(key_path, 32, "invalid-key")
    if len(key) != 32:
        raise Rejection("invalid-key")
    if operation == "seal":
        raw = read_input(source, ORIGINAL_LIMIT, "invalid-input")
        surface, report = codec.seal(raw, key, require_rate)
        write_new(output, surface)
        return report
    surface = read_input(source, CARRIER_BYTES, "carrier-rejected")
    write_new(output, codec.open(surface, key))
    return {}


def peak_process_bytes():
    if os.name != "nt":
        from resource import RUSAGE_SELF, getrusage
        rss = getrusage(RUSAGE_SELF).ru_maxrss
        return rss if sys.platform == "darwin" else rss * 1024
    import ctypes
    from ctypes import wintypes

    class MemoryCounters(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("faults", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ("peak", "working", "peak_paged", "paged",
                                                "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile")
        ]

    current = ctypes.windll.kernel32.GetCurrentProcess
    current.restype = wintypes.HANDLE
    counters = MemoryCounters()
    counters.size = ctypes.sizeof(counters)
    memory = ctypes.windll.psapi.GetProcessMemoryInfo
    memory.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
    if not memory(current(), ctypes.byref(counters), counters.size):
        raise OSError("process memory counters")
    return counters.peak


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--p1", type=Path, required=True)
    parser.add_argument("--p2", type=Path)
    parser.add_argument("--digest")
    parser.add_argument("--native", nargs=2, metavar=("EMULATOR", "SCRATCH"))
    parser.add_argument("--require-20", action="store_true")
    parser.add_argument("operation", choices=("seal", "open"))
    parser.add_argument("key", type=Path)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if (args.p2 is None) != (args.digest is None) or (args.native and args.p2 is None):
        parser.error("provide the P2 configuration and its digest together")
    if args.require_20 and args.operation == "open":
        parser.error("rate check applies to seal")
    start = perf_counter_ns()
    codec = load_codec(args.p1, args.p2, args.digest, native_query(args.native),
                       sending=args.operation == "seal")
    loaded = perf_counter_ns()
    result = file_operation(codec, args.operation, args.key, args.input, args.output, args.require_20)
    end = perf_counter_ns()
    result.update(load_ms=(loaded - start) / 1e6, operation_ms=(end - loaded) / 1e6,
                  peak_process_bytes=peak_process_bytes())
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (Rejection, ProfileError) as error:
        sys.exit(str(error))
    except FileExistsError:
        sys.exit("output-exists")
    except FileNotFoundError:
        sys.exit("missing-resource")
    except (ValueError, TypeError, KeyError, zlib.error, subprocess.SubprocessError, OSError):
        sys.exit("p2-experiment-rejected")
