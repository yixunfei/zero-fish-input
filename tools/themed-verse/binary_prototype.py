"""Host-only prototype for public UTF-8 fixtures. See docs/themed-verse/prototype.md."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zlib

from compression_probe import envelope_budget, pack, unpack
from rmw_probe import decode_rmw, exact_encode
from utilization_probe import public_dictionaries
from verse import IMAGES, LOCK, ROOT, Resources
from word_codec import WordCodec


BIN = ".exe" if os.name == "nt" else ""
CRYPTO = ROOT / f"tools/stego-vectors/target/debug/verse-envelope{BIN}"
RMW = ROOT / f".tmp-evidence/rmw-target/release/verse-rmw-probe{BIN}"
MODES = ("raw", "zlib", "words", "dict-utf8", "dict-gb", "rmw")
V1_PROFILE = "95707c57d28e0795c039848c664c30914b2894e922bb096f92a7fa70d3e0be55"


def bounded_file(path, limit):
    with Path(path).open("rb") as f:
        data = f.read(limit+1)
    if len(data) > limit:
        raise ValueError("input exceeds limit")
    return data


def helper(command, data):
    result = subprocess.run(command, input=data, capture_output=True, timeout=20)
    if result.returncode:
        raise ValueError("binary operation rejected")
    return result.stdout


def layout(lines):
    parts = []
    for i, line in enumerate(lines, 1):
        parts.append(line)
        parts.append("，" if i % 2 and i < len(lines) else "。")
        if i % 4 == 0 and i < len(lines):
            parts.append("\n")
    return "".join(parts)


def read_ciphertext(verse, surface):
    if len(surface) > 8192 or len(surface.encode()) > 16384 or not surface.endswith("。"):
        raise ValueError("carrier bounds")
    plain = surface.replace("，", "。").replace("\n", "")
    lines = plain[:-1].split("。")
    if any(len(w) != 5 for w in lines) or layout(lines) != surface:
        raise ValueError("noncanonical layout")
    # The last digit crosses exactly one 64-byte frame bucket.
    span, previous = 1, ""
    for word in lines:
        before = span
        span *= len(verse.pool("linked", previous))
        previous = word
    sizes = [n for n in range(162, 2211, 64) if before < (1 << (8*n)) <= span]
    if len(sizes) != 1:
        raise ValueError("invalid frame boundary")
    return unpack(verse, "linked", plain, sizes[0])


def inflate(stream, limit, dictionary=None):
    decoder = (zlib.decompressobj() if dictionary is None else
               zlib.decompressobj(zdict=dictionary))
    plain = decoder.decompress(stream, limit + 1)
    if len(plain) > limit or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("compressed bounds or tail")
    return plain


def deflate(stream, dictionary):
    encoder = zlib.compressobj(9, zdict=dictionary)
    return encoder.compress(stream) + encoder.flush()


def decode_text(mode, stream, size, words, dicts, *, rmw_probe=RMW):
    if not 1 <= size <= 2048 or not 1 <= len(stream) <= 2045:
        raise ValueError("codec bounds")
    if mode == "raw":
        raw = stream
    elif mode == "words":
        raw = words.decode(stream, size)
    elif mode == "rmw":
        raw = decode_rmw(rmw_probe, stream, size)
    elif mode == "zlib":
        raw = inflate(stream, size)
    elif mode == "dict-utf8":
        raw = inflate(stream, size, dicts["utf-8"])
    elif mode == "dict-gb":
        # GB18030 can use four bytes for a single Unicode scalar.
        raw = inflate(stream, 4*size, dicts["gb18030"]).decode("gb18030").encode("utf-8")
    else:
        raise ValueError("codec")
    if len(raw) != size:
        raise ValueError("original length")
    raw.decode("utf-8")
    return raw


def text_streams(raw, words, dicts, *, rmw_probe=RMW):
    if not 1 <= len(raw) <= 2048:
        raise ValueError("original length")
    text = raw.decode("utf-8")
    streams = {"raw": raw, "zlib": zlib.compress(raw, 9), "words": words.encode(raw)}
    for mode, fmt in (("dict-utf8", "utf-8"), ("dict-gb", "gb18030")):
        data = text.encode(fmt)
        if data.decode(fmt) == text:
            streams[mode] = deflate(data, dicts[fmt])
    rmw = exact_encode(rmw_probe, raw)
    if rmw is not None:
        streams["rmw"] = rmw
    return streams


class Prototype:
    def __init__(self):
        self.verse = Resources()
        self.words = WordCodec()
        self.dicts = public_dictionaries()
        table_hash = json.loads(LOCK.read_text(encoding="utf-8"))["preparedFiles"]["dicts/shici.dict.yaml"]
        manifest = ["host-verse-v1", table_hash, MODES, IMAGES, "rmw-utf8=0.0.6",
                    sorted((w.hex(), bits) for w, bits in self.words.codes.items()),
                    [hashlib.sha256(d).hexdigest() for d in self.dicts.values()]]
        self.profile = hashlib.sha256(json.dumps(manifest, ensure_ascii=True).encode()).digest()
        if self.profile.hex() != V1_PROFILE:
            raise ValueError("host-v1 resources changed")

    def decompress(self, mode, stream, size):
        return decode_text(mode, stream, size, self.words, self.dicts)

    def candidates(self, raw):
        streams = text_streams(raw, self.words, self.dicts)
        return {mode: stream for mode, stream in streams.items() if len(stream) <= 2045
                and self.decompress(mode, stream, len(raw)) == raw}

    def crypt(self, operation, key, data):
        if len(key) != 32:
            raise ValueError("key length")
        return helper([CRYPTO, operation], key + self.profile + data)

    def seal(self, raw, key, require_rate=False):
        streams = self.candidates(raw)
        if not streams:
            raise ValueError("insufficient frame capacity")
        mode = min(streams, key=lambda m: (envelope_budget(3+len(streams[m])), MODES.index(m)))
        packet = bytes([MODES.index(mode)]) + len(raw).to_bytes(2, "big") + streams[mode]
        ciphertext = self.crypt("seal", key, packet)
        if len(ciphertext) != envelope_budget(len(packet)):
            raise ValueError("unexpected envelope size")
        lines = pack(self.verse, "linked", ciphertext)[:-1].split("。")
        surface = layout(lines)
        if len(surface) > 8192 or len(surface.encode()) > 16384:
            raise ValueError("insufficient carrier capacity")
        if require_rate and len(surface.encode()) > 5*len(raw):
            raise ValueError("below 20% utilization")
        if self.open(surface, key) != raw:
            raise ValueError("self-check")
        return surface, mode, len(ciphertext), {m: len(s) for m, s in streams.items()}

    def read_ciphertext(self, surface):
        return read_ciphertext(self.verse, surface)

    def open(self, surface, key):
        ciphertext = self.read_ciphertext(surface)
        packet = self.crypt("open", key, ciphertext)
        if len(packet) < 4 or packet[0] >= len(MODES):
            raise ValueError("invalid descriptor")
        return self.decompress(MODES[packet[0]], packet[3:], int.from_bytes(packet[1:3], "big"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("seal", "open"))
    parser.add_argument("key", type=Path, help="32-byte public fixture key file")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, help="new file")
    parser.add_argument("--require-20", action="store_true", help="reject a seal below 20 percent")
    args = parser.parse_args()
    proto = Prototype()
    key = bounded_file(args.key, 32)
    if args.operation == "seal":
        raw = bounded_file(args.input, 2048)
        text, mode, length, _ = proto.seal(raw, key, args.require_20)
        output = text.encode()
        print(mode, "envelope bytes", length, "surface bytes", len(output),
              "utilization", f"{100*len(raw)/len(output):.2f}%")
    else:
        if args.require_20:
            parser.error("--require-20 applies to seal")
        surface = bounded_file(args.input, 16384).decode("utf-8")
        output = proto.open(surface, key)
    with args.output.open("xb") as f:
        f.write(output)


if __name__ == "__main__":
    try:
        main()
    except FileExistsError:
        raise SystemExit("Output already exists.")
    except FileNotFoundError:
        raise SystemExit("Missing input, resource, or host helper. See prototype.md.")
    except (ValueError, zlib.error, subprocess.SubprocessError):
        raise SystemExit("Message rejected. Check the key, carrier, size and selected rate limit.")
