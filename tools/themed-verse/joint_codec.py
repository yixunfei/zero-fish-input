"""P1 host sender and independent receiver for public fixtures."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zlib

from binary_prototype import (CRYPTO, RMW, bounded_file, decode_text, deflate, helper, inflate,
                              layout, read_ciphertext, text_streams)
from compression_probe import envelope_budget, pack
from p1_profile import MODE_IDS, Profile, ProfileError
from rime_location_codec import decode as decode_positions, encode as encode_positions
from rmw_probe import add_probe_arguments, resolve_probe


MODES_BY_ID = {number: name for name, number in MODE_IDS.items()}
POSITION_MODES = ("position", "position-zlib", "position-dict")
ORIGINAL_LIMIT = 2048
STREAM_LIMIT = ORIGINAL_LIMIT - 3
CARRIER_BYTES = 16384
CARRIER_UNITS = 8192


class Rejection(ValueError):
    pass


def metrics(size, surface):
    units = len(surface.decode("utf-8").encode("utf-16-le")) // 2
    return {"surface_bytes": len(surface), "utf16_units": units, "pass20": len(surface) <= 5 * size,
            "draft_fits": units <= 512 and len(surface) <= 1536}


def choose(streams, allowed=MODE_IDS):
    eligible = [mode for mode in allowed if mode in streams]
    if not eligible:
        raise Rejection("frame-capacity")
    return min(eligible, key=lambda mode: (envelope_budget(3 + len(streams[mode])), MODE_IDS[mode]))


class JointCodec:
    mode_ids = MODE_IDS
    modes_by_id = MODES_BY_ID

    def __init__(self, profile, *, binding=None, rmw_probe=RMW):
        self.profile = profile
        self.binding = profile.digest if binding is None else binding
        self.rmw_probe = rmw_probe

    def decode(self, mode, stream, size):
        if not 1 <= size <= ORIGINAL_LIMIT or not 1 <= len(stream) <= STREAM_LIMIT:
            raise ValueError("codec bounds")
        profile = self.profile
        if mode not in POSITION_MODES:
            return decode_text(mode, stream, size, profile.words, profile.dicts, rmw_probe=self.rmw_probe)
        if mode == "position-zlib":
            stream = inflate(stream, 3 * size)
        if mode == "position-dict":
            stream = inflate(stream, 3 * size, profile.position_dict)
        # No native key events are registered in the P1 position stream.
        return decode_positions(stream, profile.positions, {}, size)

    def build_streams(self, raw, *, position=None):
        if not 1 <= len(raw) <= ORIGINAL_LIMIT:
            raise Rejection("invalid-input")
        try:
            raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise Rejection("invalid-input") from error
        profile = self.profile
        streams = text_streams(raw, profile.words, profile.dicts, rmw_probe=self.rmw_probe)
        if position is None:
            position = encode_positions(raw, profile.positions.terms(raw))
        streams.update({"position": position, "position-zlib": zlib.compress(position, 9),
                        "position-dict": deflate(position, profile.position_dict)})
        return streams

    def verify_streams(self, raw, streams):
        accepted, rejected = {}, {}
        for mode in MODE_IDS:
            stream = streams.get(mode)
            if stream is None:
                rejected[mode] = "non-exact"
            elif not 1 <= len(stream) <= STREAM_LIMIT:
                rejected[mode] = "frame-capacity"
            else:
                try:
                    exact = self.decode(mode, stream, len(raw)) == raw
                except (ValueError, zlib.error):
                    exact = False
                if exact:
                    accepted[mode] = stream
                else:
                    rejected[mode] = "non-exact"
        return accepted, rejected

    def candidates(self, raw, *, position=None):
        return self.verify_streams(raw, self.build_streams(raw, position=position))

    def crypt(self, operation, key, data):
        if len(key) != 32:
            raise ValueError("key length")
        return helper([CRYPTO, operation], key + self.binding + data)

    def render_ciphertext(self, cipher):
        plain = pack(self.profile.verse, "linked", cipher)
        return layout(plain[:-1].split("。")).encode()

    def read_ciphertext(self, surface):
        if len(surface) > CARRIER_BYTES:
            raise ValueError("carrier bounds")
        cipher = read_ciphertext(self.profile.verse, surface.decode("utf-8"))
        if self.render_ciphertext(cipher) != surface:
            raise ValueError("noncanonical carrier")
        return cipher

    def open(self, surface, key):
        try:
            packet = self.crypt("open", key, self.read_ciphertext(surface))
            if len(packet) < 4 or packet[0] not in self.modes_by_id:
                raise ValueError("invalid descriptor")
            mode = self.modes_by_id[packet[0]]
            return self.decode(mode, packet[3:], int.from_bytes(packet[1:3], "big"))
        except (ValueError, zlib.error) as error:
            raise Rejection("carrier-rejected") from error

    def _preflight(self, envelope_size, original_size, require_rate):
        verse = self.profile.verse
        span, count = len(verse.theme), 1
        largest = max(map(len, verse.groups))
        target = 1 << (8 * envelope_size)
        while span < target:
            span *= largest
            count += 1
        line_breaks = (count - 1) // 4
        if 18 * count + line_breaks > CARRIER_BYTES or 6 * count + line_breaks > CARRIER_UNITS:
            raise Rejection("carrier-capacity")
        if require_rate and 18 * count + line_breaks > 5 * original_size:
            raise Rejection("below-20")

    def seal_stream(self, raw, key, mode, stream, require_rate=False):
        if not 1 <= len(stream) <= STREAM_LIMIT:
            raise Rejection("frame-capacity")
        packet = bytes([self.mode_ids[mode]]) + len(raw).to_bytes(2, "big") + stream
        expected_size = envelope_budget(len(packet))
        self._preflight(expected_size, len(raw), require_rate)
        cipher = self.crypt("seal", key, packet)
        if len(cipher) != expected_size:
            raise Rejection("seal-failed")
        surface = self.render_ciphertext(cipher)
        observed = metrics(len(raw), surface)
        if len(surface) > CARRIER_BYTES or observed["utf16_units"] > CARRIER_UNITS:
            raise Rejection("carrier-capacity")
        if require_rate and not observed["pass20"]:
            raise Rejection("below-20")
        if self.open(surface, key) != raw:
            raise Rejection("seal-failed")
        return surface

    def seal(self, raw, key, require_rate=False):
        streams, rejected = self.candidates(raw)
        mode = choose(streams)
        stream = streams[mode]
        surface = self.seal_stream(raw, key, mode, stream, require_rate)
        return surface, dict(metrics(len(raw), surface), mode=mode,
                             envelope_bytes=envelope_budget(3 + len(stream)),
                             streams={name: len(data) for name, data in streams.items()},
                             rejected=rejected)


def read_input(path, limit, category):
    try:
        return bounded_file(path, limit)
    except FileNotFoundError as error:
        raise Rejection("missing-input") from error
    except ValueError as error:
        raise Rejection(category) from error


def write_new(path, data):
    if path.exists():
        raise FileExistsError(path)
    file = tempfile.NamedTemporaryFile(dir=path.parent, prefix=".verse-", delete=False)
    staged = Path(file.name)
    try:
        with file:
            file.write(data)
        # Both operations fail atomically if another writer has claimed the destination.
        if os.name == "nt":
            staged.rename(path)
        else:
            os.link(staged, path)
    finally:
        staged.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    add_probe_arguments(parser)
    operations = parser.add_subparsers(dest="operation", required=True)
    for operation in ("seal", "open"):
        command = operations.add_parser(operation)
        command.add_argument("key", type=Path, help="32-byte public fixture key")
        command.add_argument("input", type=Path)
        command.add_argument("output", type=Path, help="new file")
        if operation == "seal":
            command.add_argument("--require-20", action="store_true")
    args = parser.parse_args()
    sending = args.operation == "seal"
    codec = JointCodec(Profile(args.profile, sending=sending), rmw_probe=resolve_probe(args, RMW))
    key = read_input(args.key, 32, "invalid-key")
    if len(key) != 32:
        raise Rejection("invalid-key")
    if sending:
        raw = read_input(args.input, ORIGINAL_LIMIT, "invalid-input")
        surface, report = codec.seal(raw, key, args.require_20)
        write_new(args.output, surface)
        print(json.dumps(report))
    else:
        surface = read_input(args.input, CARRIER_BYTES, "carrier-rejected")
        write_new(args.output, codec.open(surface, key))


if __name__ == "__main__":
    try:
        main()
    except (ProfileError, Rejection) as error:
        sys.exit(str(error))
    except FileExistsError:
        sys.exit("output-exists")
    except FileNotFoundError:
        sys.exit("missing-resource")
    except OSError:
        sys.exit("io-error")
    except (ValueError, zlib.error, subprocess.SubprocessError):
        sys.exit("host-helper-failed")
