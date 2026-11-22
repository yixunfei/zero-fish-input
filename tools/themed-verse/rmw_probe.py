"""Compare the pinned RMW text codec against the public-corpus size baseline."""
import argparse
from base64 import b64decode, b64encode
import re
from shutil import which
import subprocess
import zlib
from pathlib import Path

from utilization_probe import dictionary_stream, fixtures, public_dictionaries, surface_budget
from verse import Resources


def add_probe_arguments(parser):
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--rmw-probe", type=Path, help="built public RMW helper")
    group.add_argument("--rmw-emulator", nargs=2, metavar=("SERIAL", "DEVICE_PROBE"),
                       help="same pinned helper built for an isolated Android emulator")


def probe_arguments(args, default):
    if args.rmw_emulator is not None:
        return ("--rmw-emulator", *args.rmw_emulator)
    return ("--rmw-probe", (args.rmw_probe or default).resolve())


def resolve_probe(args, default):
    if args.rmw_emulator is None:
        return (args.rmw_probe or default).resolve()
    serial, path = args.rmw_emulator
    if (not re.fullmatch(r"emulator-[0-9]+", serial)
            or not re.fullmatch(r"/data/local/tmp/zeroInput-rmw-[a-zA-Z0-9-]+", path)):
        raise ValueError("RMW helper must be staged on an isolated emulator")
    adb = which("adb")
    if adb is None:
        raise FileNotFoundError("adb")
    return (adb, "-s", serial, "shell", "-T", path)


def run_probe(executable, data, size=None):
    options = () if size is None else (str(size),)
    if not isinstance(executable, tuple):
        return subprocess.run((executable, *options), input=data, capture_output=True, timeout=20)
    # The Windows adb stream can translate binary control characters.
    remote = " ".join((executable[-1], *options))
    pipeline = f"set -o pipefail; base64 -d | {remote} | base64"
    result = subprocess.run((*executable[:-1], pipeline), input=b64encode(data),
                            capture_output=True, timeout=20)
    if not result.returncode:
        result.stdout = b64decode(b"".join(result.stdout.split()), validate=True)
    return result


def exact_encode(executable, raw):
    result = run_probe(executable, raw)
    if result.returncode == 2:
        return None
    result.check_returncode()
    return result.stdout


def decode_rmw(executable, stream, size):
    result = run_probe(executable, stream, size)
    if result.returncode:
        raise ValueError("RMW stream rejected")
    return result.stdout


def run(executable):
    tx, rx = Resources(), Resources()
    dictionaries = public_dictionaries()
    for name, raw in fixtures():
        sizes = [dictionary_stream(raw, fmt, d) for fmt, d in dictionaries.items()]
        baseline = min(len(raw), len(zlib.compress(raw, 9)), *sizes)
        encoded = exact_encode(executable, raw)
        candidates = [baseline]
        if encoded is None:
            print(name, "RMW rejected: round-trip mismatch")
        else:
            second = zlib.compress(encoded, 9)
            if zlib.decompress(second) != encoded:
                raise ValueError("secondary compression round trip")
            candidates.extend([len(encoded), len(second)])
            size, _, octets = surface_budget(raw, len(encoded), tx, rx)
            print(name, "raw/RMW/RMW+zlib", len(raw), len(encoded), len(second),
                  "RMW frame/surface", size, octets, f"{100*len(raw)/octets:.2f}%")
        size, units, octets = surface_budget(raw, min(candidates), tx, rx)
        print("  best input/frame/surface", min(candidates), size, octets,
              f"{100*len(raw)/octets:.2f}%", "fits carrier", units <= 8192 and octets <= 16384)
    for name, text in (("short", "人民网络"), ("LF", "甲\n乙"), ("CRLF", "甲\r\n乙"),
                       ("CR", "甲\r乙"), ("emoji", "新🫠例"), ("combining", "e\u0301")):
        raw = text.encode()
        coded = exact_encode(executable, raw)
        print(name, len(raw), "mismatch" if coded is None else len(coded))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    run(parser.parse_args().executable.resolve())
