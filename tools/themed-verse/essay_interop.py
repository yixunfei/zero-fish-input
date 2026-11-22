"""Cross-language public controls for the host Essay body and its two surface spellings."""
from io import BytesIO
from pathlib import Path
import subprocess

from essay_body import COPY, ESSAY, OPENCC, build_models, put_code
from p2_bits import Bits


def packet(mode, size, stream):
    return bytes([mode]) + size.to_bytes(2, "big") + stream


def controls(models):
    good, bad = [], []
    golden = {
        8: "e016b97457cefa8e342d7bf0a0808bf09f998265cc810d9c",
        9: "5ae5d15555663ea38d0b5efc282022fc27e660997320436700",
    }
    for mode, codec in models.items():
        mixed = "說话。說话。𠀋🙂e\u0301\r\n".encode()
        stream, stats = codec.encode(mixed)
        if stream.hex() != golden[mode] or not stats["copy_count"]:
            raise ValueError("mixed-script golden vector")
        p = packet(mode, len(mixed), stream)
        good.append((mixed, p))
        bad.extend((p[:-1], p + b"\0", p[:1] + b"\0\0" + p[3:], packet(mode, len(mixed)-1, stream)))
        for size in (1, 2045, 2046, 2048):
            raw = b"x" if size == 1 else mixed + b"x" * (size - len(mixed))
            good.append((raw, packet(mode, size, codec.encode(raw)[0])))

        # A single literal followed by distance one must allow overlapping copies.
        def copy_vector(prefix, distance, size):
            bits = Bits()
            if prefix:
                put_code(bits, codec.words.codes[b""])
                bits.put(len(prefix) - 1, 4, "boundary")
                bits.put(int.from_bytes(prefix, "big"), len(prefix) * 8, "payload")
            put_code(bits, codec.words.codes[COPY])
            bits.put(0, 6, "boundary")
            if prefix:
                bits.put(distance - 1, (len(prefix) - 1).bit_length(), "payload")
            return packet(mode, size, bits.finish()[0])

        good.append((b"aaaa", copy_vector(b"a", 1, 4)))
        good.append((b"abcabc", copy_vector(b"abc", 3, 6)))
        bad.extend((copy_vector(b"", 0, 3), copy_vector(b"abc", 4, 6),
                    copy_vector(b"a", 1, 3), copy_vector(b"\xff", 1, 4)))
        bits = Bits()
        put_code(bits, codec.words.codes[b""])
        bits.put(0, 4, "boundary")
        bits.put(65, 8, "payload")
        stream, cost = bits.finish()
        if cost["padding"] == 0:
            raise ValueError("expected a padding control")
        bad.append(packet(mode, 1, stream[:-1] + bytes([stream[-1] | 1])))
    return good, bad


def verify_essay(cases, tables, model_file):
    models = build_models(tables)
    good, bad = controls(models)
    valid = cases + good
    packets = [p for _, p in valid] + bad
    for p in bad:
        try:
            models[p[0]].decode(p[3:], int.from_bytes(p[1:3], "big"))
        except ValueError:
            continue
        raise ValueError("Python accepted a malformed Essay body")
    data = len(packets).to_bytes(2, "big") + b"".join(len(p).to_bytes(2, "big") + p for p in packets)
    command = ["java", "-Xmx512m", str(Path(__file__).with_name("EssayBodyCheck.java")),
               str(ESSAY), str(model_file), str(OPENCC / "TSCharacters.txt")]
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE, check=True, timeout=120)
    pipe = BytesIO(result.stdout)
    for expected, p in valid:
        if (models[p[0]].decode(p[3:], int.from_bytes(p[1:3], "big")) != expected or
                pipe.read(2) != len(expected).to_bytes(2, "big") or pipe.read(len(expected)) != expected):
            raise ValueError("JVM/host Essay recovery mismatch")
    if pipe.read() != bytes(2 * len(bad)):
        raise ValueError("JVM malformed body was accepted or response truncated")
    return {"real_packets": len(cases), "valid_controls": len(good), "rejected_controls": len(bad)}
