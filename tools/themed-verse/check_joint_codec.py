"""P1 public-fixture acceptance and ablation from the repository root."""
import argparse
from bisect import bisect_left
from hashlib import sha256, shake_256
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zlib

from binary_prototype import CRYPTO, RMW, ROOT, layout
from check_binary_prototype import rejects, require
from compression_probe import envelope_budget, pack
from joint_codec import JointCodec, MODE_IDS, choose, metrics
from p1_corpus import samples
from p1_positions import ROW_BASE, ROW_COUNT
from p1_profile import Profile
from rmw_probe import add_probe_arguments, probe_arguments, resolve_probe


TOOLS = Path(__file__).resolve().parent
VECTORS = ROOT / "docs/themed-verse/p1-vectors.json"
UNICODE = "公开夹具𠀀\r\n🫠e\u0301\0\r".encode()
COMMON = ("raw", "zlib", "dict-utf8", "dict-gb", "rmw")
GROUPS = {
    "words": (*COMMON, "words"),
    "position": (*COMMON, "position", "position-zlib", "position-dict"),
    "joint": tuple(MODE_IDS),
}
V1_ENVELOPES = (1186, 1058, 610, 546, 610, 866, 1186, 1122, 1250)
JOINT_ENVELOPES = (1122, 994, 546, 482, 546, 802, 1186, 1122, 1250)


class Processes:
    def __init__(self, profile, scratch, rmw_options):
        self.source = scratch / "source"
        self.source.mkdir()
        self.receiver = scratch / "receiver"
        self.script = self.receiver / "tools/themed-verse/joint_codec.py"
        self.script.parent.mkdir(parents=True)
        for name in ("joint_codec", "p1_profile", "p1_positions", "verse",
                     "binary_prototype", "word_codec", "utilization_probe",
                     "compression_probe", "rmw_probe", "rime_location_codec"):
            shutil.copyfile(TOOLS / f"{name}.py", self.script.parent / f"{name}.py")
        dest = self.receiver / CRYPTO.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(CRYPTO, dest)
        self.profile = Path(profile).resolve()
        self.rmw_options = rmw_options
        self.public = self.receiver / "profile"
        self.public.mkdir()
        for path in Profile(self.profile, sending=False).receive_files:
            shutil.copyfile(path, self.public / path.name)
        self.key = self.receiver / "fixture.key"
        self.carrier = self.receiver / "carrier.txt"
        self.output = self.receiver / "restored.txt"

    def run(self, script, cwd, profile, *args):
        return subprocess.run(
            [sys.executable, "-E", "-S", "-B", script, "--profile", profile, *self.rmw_options, *args],
            cwd=cwd, capture_output=True, timeout=45,
        )

    def send(self, raw, key, expected=None, strict=False):
        path = self.source / "original.txt"
        path.write_bytes(raw)
        self.key.write_bytes(key)
        self.carrier.unlink(missing_ok=True)
        options = ("--require-20",) if strict else ()
        result = self.run(TOOLS / "joint_codec.py", self.source, self.profile,
                          "seal", self.key, path, self.carrier, *options)
        path.unlink()
        if expected is not None:
            require(result.returncode and result.stderr.strip() == expected.encode()
                    and not self.carrier.exists(), f"seal should reject {expected}")
            return None
        require(result.returncode == 0, f"seal failed {result.stderr!r}")
        require(self.carrier.is_file(), "seal output")
        return json.loads(result.stdout)

    def receive(self, surface, key, expected=None):
        self.output.unlink(missing_ok=True)
        self.carrier.write_bytes(surface)
        self.key.write_bytes(key)
        result = self.run(self.script, self.receiver, self.public,
                          "open", self.key, self.carrier, self.output)
        if expected is not None:
            require(result.returncode and not self.output.exists(), "failed open exposed output")
            require(result.stderr.strip() == expected.encode(), f"wanted {expected}")
            return None
        require(result.returncode == 0, f"independent open failed {result.stderr!r}")
        return self.output.read_bytes()


def check_vectors(codec, processes, corpus):
    fixed = json.loads(VECTORS.read_text(encoding="utf-8"))
    require(fixed["profile_sha256"] == codec.profile.digest.hex(), "fixed profile")
    modes = set()
    for vector in fixed["vectors"]:
        name = vector["name"]
        raw = bytes.fromhex(vector["public_input_hex"]) if "public_input_hex" in vector else corpus[name]
        key = bytes.fromhex(fixed["public_fixture_key_hex"])
        surface = vector["surface"].encode()
        restored = processes.receive(surface, key)
        require(restored == raw and sha256(restored).hexdigest() == vector["restored_sha256"],
                f"fixed recovery {name}")
        cipher = codec.read_ciphertext(surface)
        packet = codec.crypt("open", key, cipher)
        mode, size, stream = vector["mode"], vector["original_bytes"], bytes.fromhex(vector["stream_hex"])
        expected = bytes([MODE_IDS[mode]]) + size.to_bytes(2, "big") + stream
        require(packet == expected and size == len(raw) and len(cipher) == vector["envelope_bytes"],
                f"fixed seal fields {name}")
        require(sha256(surface).hexdigest() == vector["surface_sha256"], f"fixed surface {name}")
        require(metrics(len(raw), surface)["pass20"] == vector["pass20"], f"fixed rate {name}")
        if mode == "words" or fixed["reference_zlib"] == zlib.ZLIB_RUNTIME_VERSION:
            streams, _ = codec.candidates(raw)
            require(streams[mode] == stream, f"fixed encoder stream {name}")
        sent = processes.send(raw, os.urandom(32), strict=vector["pass20"])
        require(sent["mode"] == mode and sent["envelope_bytes"] == len(cipher),
                f"encoder mode regression {name}")
        require(processes.receive(processes.carrier.read_bytes(), processes.key.read_bytes()) == raw,
                f"random recovery {name}")
        modes.add(mode)
    require({"words", "position-dict"} <= modes, "both codecs must win selection")
    require(any(v.get("public_input_hex") == UNICODE.hex() for v in fixed["vectors"]),
            "mixed Unicode vector")


def check_capacity_and_streams(codec, processes):
    key = os.urandom(32)
    require(envelope_budget(3 + 25) == 162 and envelope_budget(3 + 26) == 226, "bucket edge")
    for size in (1, 2045, 2046, 2048):
        raw = b"x" * (size - 1) + b"\n"
        report = processes.send(raw, key)
        require((size <= 2045) == ("raw" in report["streams"]), f"raw bounds {size}")
        require(processes.receive(processes.carrier.read_bytes(), key) == raw, f"edge {size}")
    processes.send(b"x", key, "below-20", strict=True)
    processes.send(b"x" * 2049, key, "invalid-input")
    processes.send(b"\xff", key, "invalid-input")
    large = bytes(n & 127 for n in shake_256(b"large p1 public fixture").digest(2048))
    processes.send(large, key, "carrier-capacity")
    rejects(choose, {}, ("raw",))
    streams, _ = codec.candidates(UNICODE)
    for mode in ("words", "position", "position-zlib", "position-dict"):
        stream = streams[mode]
        require(codec.decode(mode, stream, len(UNICODE)) == UNICODE, mode)
        for bad in (stream[:-1], stream + b"\x00"):
            rejects(codec.decode, mode, bad, len(UNICODE))
        rejects(codec.decode, mode, stream, len(UNICODE) - 1)
    for bad in (b"\xbf\xff\xff", b"\xdf\xff", b"\x80", b"\xff\x02ab", b"\xe0\x00"):
        rejects(codec.decode, "position", bad, 8)
    rejects(codec.decode, "position-zlib", zlib.compress(b"x" * 6145), 2048)
    rejects(codec.decode, "words", b"\xe1", 1)  # Space code followed by nonzero padding.
    require("rmw" not in streams, "lossy RMW in candidates")
    boundary = "🫠".encode() * 512
    processes.send(boundary, key)
    require(processes.receive(processes.carrier.read_bytes(), key) == boundary, "multi-byte at 2048")


def check_positions(codec, processes):
    key = os.urandom(32)
    # First ID, a duplicate pronunciation row, last ID, and both short-ID ends.
    tokens = (b"\x80\0\0", b"\x80\0\x02", (ROW_BASE + ROW_COUNT - 1).to_bytes(3, "big"),
              b"\xc0\0", b"\xdf\xff")
    raw = "啊啊\u8dcd\u8e72的不难".encode()
    surface = codec.seal_stream(raw, key, "position", b"".join(tokens))
    require(processes.receive(surface, key) == raw, "full reverse index over a new message")
    require(codec.profile.positions.terms("啊".encode())["啊"][:1] >= b"\xc0",
            "short ID takes precedence")


def legal_substitution(codec):
    fixed = json.loads(VECTORS.read_bytes())
    surface = fixed["vectors"][0]["surface"]
    words = surface.replace("，", "。").replace("\n", "")[:-1].split("。")
    verse = codec.profile.verse
    previous = ""
    for index, word in enumerate(words):
        pool = verse.pool("linked", previous)
        first = bisect_left(pool, word[:4])
        end = bisect_left(pool, word)
        next_pool = verse.pool("linked", word)
        for alt in pool[first:end]:
            if verse.pool("linked", alt) is next_pool:
                altered = layout([*words[:index], alt, *words[index + 1:]]).encode()
                require(codec.read_ciphertext(altered) != codec.read_ciphertext(surface.encode()),
                        "substitution changes the ciphertext with a valid layout")
                return altered, bytes.fromhex(fixed["public_fixture_key_hex"])
        previous = word
    raise RuntimeError("expected a one-character substitution in the fixed carrier")


def check_carriers(codec, processes):
    key = os.urandom(32)
    raw = UNICODE
    surface = codec.seal_stream(raw, key, "raw", raw)
    text = surface.decode()
    lines = text.replace("，", "。").replace("\n", "")[:-1].split("。")
    damaged = (text[:-1], text + "。", "〇" + text[1:], text.replace("，", "。", 1),
               text.replace("\n", "\r\n", 1), layout([lines[1], lines[0], *lines[2:]]),
               layout(lines[:-1]), "山" * 8193, "山" * 5462)
    for changed in damaged:
        processes.receive(changed.encode(), key, "carrier-rejected")
    altered, fixture_key = legal_substitution(codec)
    processes.receive(altered, fixture_key, "carrier-rejected")
    processes.receive(surface, bytes(b ^ 1 for b in key), "carrier-rejected")
    cipher = codec.read_ciphertext(surface)
    for offset in (16, 17, 20, 44, 81, 82, len(cipher) - 1):
        changed = bytearray(cipher)
        changed[offset] ^= 1
        processes.receive(codec.render_ciphertext(changed), key, "carrier-rejected")
    for packet in (b"\xff\x00\x01x", b"\x11\x00\x01" + zlib.compress(b"xx")):
        cipher = codec.crypt("seal", key, packet)
        processes.receive(codec.render_ciphertext(cipher), key, "carrier-rejected")
    legacy = (ROOT / "security/src/test/resources/message/rustcrypto-v1.txt").read_text(encoding="utf-8")
    six = bytes.fromhex(next(line.split("=", 1)[1] for line in legacy.splitlines()
                             if line.startswith("# envelope-six=")))
    processes.receive(codec.render_ciphertext(six), key, "carrier-rejected")
    check_profile_rejections(processes, surface, key)


def check_profile_rejections(processes, surface, key):
    original = processes.public / "profile.json"
    manifest = original.read_bytes()
    original.write_bytes(manifest.replace(b"linked", b"altered", 1))
    try:
        processes.receive(surface, key, "profile-mismatch")
    finally:
        original.write_bytes(manifest)
    reversed_tables = json.loads(manifest)
    reversed_tables["sources"]["table_order"].reverse()
    original.write_text(json.dumps(reversed_tables), encoding="utf-8")
    try:
        processes.receive(surface, key, "profile-mismatch")
    finally:
        original.write_bytes(manifest)
    asset = processes.public / "position-zdict.bin"
    dictionary = asset.read_bytes()
    asset.write_bytes(dictionary[:-1] + bytes([dictionary[-1] ^ 1]))
    try:
        processes.receive(surface, key, "profile-mismatch")
    finally:
        asset.write_bytes(dictionary)
    disabled = asset.with_suffix(".missing")
    asset.rename(disabled)
    try:
        processes.receive(surface, key, "missing-resource")
    finally:
        disabled.rename(asset)
    require(processes.receive(surface, key) == UNICODE, "restore the correct public resources")
    result = processes.run(processes.script, processes.receiver, processes.public,
                           "open", processes.key, processes.carrier, processes.output)
    require(result.returncode and result.stderr.strip() == b"output-exists"
            and processes.output.read_bytes() == UNICODE, "existing result preserved")


def compare(codec, processes, corpus):
    report = []
    for name, raw in corpus.items():
        key = os.urandom(32)
        sent = processes.send(raw, key)
        streams, rejected = codec.candidates(raw)
        require(sent["streams"] == {m: len(s) for m, s in streams.items()}, f"stream sizes {name}")
        require(sent["rejected"] == rejected, f"excluded streams {name}")
        require(processes.receive(processes.carrier.read_bytes(), key) == raw, f"joint recovery {name}")
        outcomes = {"joint": {k: v for k, v in sent.items() if k not in ("streams", "rejected")}}
        for group in ("words", "position"):
            mode = choose(streams, GROUPS[group])
            surface = codec.seal_stream(raw, key, mode, streams[mode])
            require(processes.receive(surface, key) == raw, f"{group} recovery {name}")
            outcomes[group] = dict(metrics(len(raw), surface), mode=mode,
                                   envelope_bytes=envelope_budget(3 + len(streams[mode])))
        for outcome in outcomes.values():
            require(outcome["surface_bytes"] <= 16384 and outcome["utf16_units"] <= 8192,
                    f"carrier limits {name}")
            require(outcome["pass20"] == (outcome["surface_bytes"] <= 5 * len(raw)), f"rate {name}")
            outcome["recovered"] = True
            outcome["failure"] = None if outcome["pass20"] else "below-20"
        report.append({"name": name, "original_bytes": len(raw), "sha256": sha256(raw).hexdigest(),
                       "streams": sent["streams"], "rejected": rejected, "groups": outcomes})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--report", type=Path, help="new JSON report")
    add_probe_arguments(parser)
    args = parser.parse_args()
    codec = JointCodec(Profile(args.profile, sending=True), rmw_probe=resolve_probe(args, RMW))
    corpus = dict(samples())
    with tempfile.TemporaryDirectory(prefix="themed-verse-p1-") as scratch:
        processes = Processes(args.profile, Path(scratch), probe_arguments(args, RMW))
        check_vectors(codec, processes, corpus)
        check_capacity_and_streams(codec, processes)
        check_positions(codec, processes)
        check_carriers(codec, processes)
        report = compare(codec, processes, corpus)
    print("PASS fixed and random cross-process round trips, bounds, authentication and profile failures")
    documents = [item for item in report if item["name"].endswith(".md")]
    require(len(documents) == 9, "nine frozen documents")
    require(tuple(r["groups"]["words"]["envelope_bytes"] for r in documents) == V1_ENVELOPES,
            "v1 envelope regression")
    require(tuple(r["groups"]["joint"]["envelope_bytes"] for r in documents) == JOINT_ENVELOPES,
            "joint envelope regression")
    for group in GROUPS:
        hits = sum(row["groups"][group]["pass20"] for row in documents)
        print(group, "byte-exact", f"{len(documents)}/9", "20 percent", f"{hits}/9")
    if args.report:
        with args.report.open("x", encoding="utf-8", newline="\n") as file:
            json.dump({"profile_sha256": codec.profile.digest.hex(), "samples": report},
                      file, indent=2)
            file.write("\n")


if __name__ == "__main__":
    main()
