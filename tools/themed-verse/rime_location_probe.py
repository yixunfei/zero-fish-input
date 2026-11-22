"""Compare IME row IDs and replayable native key events on fixed public documents."""
import argparse
import hashlib
import os
from pathlib import Path
import zlib

from binary_prototype import CRYPTO, MODES, Prototype, helper, layout
from compression_probe import envelope_budget, pack
from rime_location_codec import decode, encode, event, event_terms, locations, queries, tokens, wanted_words
from rime_probe_device import oracle
from utilization_probe import document, fixtures, public_training_files
from verse import LOCK


def chunks(text):
    block = bytearray()
    for char in text:
        unit = char.encode()
        if len(block)+len(unit) > 2048:
            yield bytes(block)
            block.clear()
        block.extend(unit)
    if block:
        yield bytes(block)


def train_dictionary(training, terms):
    return b"".join(encode(raw, terms) for raw in training)[-32768:]


def wrap(stream, dictionary):
    compressor = zlib.compressobj(9, zdict=dictionary)
    return compressor.compress(stream) + compressor.flush()


def term_tables(direct, cache, wanted):
    events = event_terms(cache, wanted)
    mixed = dict(direct)
    for word, code in events.items():
        if word not in mixed or len(code) < len(mixed[word]):
            mixed[word] = code
    return direct, events, mixed


def unwrap(choice, data, dictionary):
    if choice == 0:
        return data
    options = {"zdict": dictionary} if choice == 2 else {}
    dec = zlib.decompressobj(**options)
    stream = dec.decompress(data, 6145)
    if not dec.eof or dec.unused_data or len(stream) > 6144:
        raise ValueError("invalid location stream")
    return stream


def receive(query, training, streams):
    wanted = wanted_words(training)
    direct, readings, _, _ = locations(wanted)
    cache = query(queries(training, readings))
    tables = term_tables(direct, cache, wanted)
    dictionaries = [train_dictionary(training, t) for t in tables]
    needed_rows, keys = set(), set()
    decoded = []
    for trial in streams:
        variants = [unwrap(choice, data, d) for (choice, data), d in zip(trial, dictionaries)]
        decoded.append(variants)
        for stream in variants:
            for kind, token in tokens(stream):
                if kind == "row":
                    num = int.from_bytes(token, "big")
                    needed_rows.add(num-0x800000 if len(token) == 3 else 0xbfff-num)
                elif kind == "event":
                    keys.add(token[0])
    _, _, _, reverse = locations(set(), needed_rows)
    print("independent receiver: distinct replayed keys", len(keys), flush=True)
    return decoded, reverse, query(keys), dictionaries


def profile_digest(proto, dictionary):
    # Separate host-only profile. Any row ordering or dictionary change requires agreement.
    return hashlib.sha256(b"ZeroInput/row-probe/v1" + proto.profile +
                          hashlib.sha256(LOCK.read_bytes().replace(b"\r\n", b"\n")).digest() +
                          hashlib.sha256(dictionary).digest()).digest()


def encrypted_trial(tx, rx, raw, streams, row_variant, binding, rx_binding, dictionary, reverse):
    base = min(streams, key=lambda m: (envelope_budget(3+len(streams[m])), MODES.index(m)))
    mode, data = MODES.index(base), streams[base]
    choice, row_data = row_variant
    if envelope_budget(len(row_data)+3) < envelope_budget(len(data)+3):
        mode, data = 16+choice, row_data
    key = os.urandom(32)
    packet = bytes([mode]) + len(raw).to_bytes(2, "big") + data
    cipher = helper([CRYPTO, "seal"], key + binding + packet)
    surface = layout(pack(tx.verse, "linked", cipher)[:-1].split("。"))
    received = helper([CRYPTO, "open"], key + rx_binding + rx.read_ciphertext(surface))
    selected = received[0]
    original_size = int.from_bytes(received[1:3], "big")
    if selected in range(16, 19):
        stream = unwrap(selected-16, received[3:], dictionary)
        recovered = decode(stream, reverse, {}, original_size)
    else:
        recovered = rx.decompress(MODES[selected], received[3:], original_size)
    if recovered != raw:
        raise ValueError("authenticated location round trip")
    return len(cipher), len(surface.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("emulator")
    parser.add_argument("native_build", type=Path)
    args = parser.parse_args()
    boundary = "𠀀\r\n🫠e\u0301".encode()
    if decode(encode(boundary, {}), {}, {}, len(boundary)) != boundary:
        raise ValueError("literal fallback")
    for bad in (b"\x80", b"\xc0\x00", b"\xff\x02ab", event("srf", 1), b"\xe0\x01"):
        try:
            decode(bad, {}, {}, 8)
        except ValueError:
            continue
        raise ValueError("unrecognized row/key or malformed token accepted")
    samples = list(fixtures())
    for name in ("adr/0002-private-clipboard.md", "audits/2026-11-17-code-audit.md",
                 "stego-themed-verse-handoff.md"):
        samples.append((name, document(name)[:2048].decode("utf-8", errors="ignore").encode()))
    samples.append(("public-verse-excerpt", ("空山新雨后，天气晚来秋。明月松间照，清泉石上流。"
                                           "星垂平野阔，月涌大江流。海上生明月，天涯共此时。").encode()))
    training = list(chunks("\n".join(p.read_text(encoding="utf-8") for p in public_training_files())))
    raw_texts = [raw for _, raw in samples]
    wanted = wanted_words(training + raw_texts)
    direct, reading, rows, _ = locations(wanted)
    spellings = queries(raw_texts + training, reading) | {"srf", "shurufa", "mysjz", "qqssl"}
    print("dictionary rows, public key sequences", rows, len(spellings), flush=True)
    with oracle(args.emulator, args.native_build) as query:
        cache = query(spellings)
        tables = term_tables(direct, cache, wanted)
        train_keys = queries(training, reading)
        train_tables = term_tables(direct, {k: cache[k] for k in train_keys}, wanted_words(training))
        dictionaries = [train_dictionary(training, t) for t in train_tables]
        streams = []
        for raw in raw_texts:
            trial = []
            for table, dictionary in zip(tables, dictionaries):
                stream = encode(raw, table)
                alternatives = (stream, zlib.compress(stream, 9), wrap(stream, dictionary))
                choice = min(range(3), key=lambda i: len(alternatives[i]))
                trial.append((choice, alternatives[choice]))
            streams.append(trial)
        decoded, reverse, cache2, dictionaries2 = receive(query, training, streams)
        for k in ("shurufa", "srf", "mysjz", "qqssl"):
            print("example", k, ascii(cache[k][:3]))
    old, rx = Prototype(), Prototype()
    binding = profile_digest(old, dictionaries[0])
    rx_binding = profile_digest(rx, dictionaries2[0])
    print("sample | raw | v1 | row | replay | mixed | old/new envelope | final bytes | rate")
    for (name, raw), trial, variants in zip(samples, streams, decoded):
        sizes = []
        for (_, data), stream in zip(trial, variants):
            if decode(stream, reverse, cache2, len(raw)) != raw:
                raise ValueError("location round trip mismatch")
            sizes.append(len(data))
        candidates = old.candidates(raw)
        base = min(map(len, candidates.values()))
        frame, octets = encrypted_trial(old, rx, raw, candidates, trial[0], binding, rx_binding,
                                        dictionaries2[0], reverse)
        print(name, len(raw), base, *sizes, envelope_budget(base+3), frame, octets,
              f"{100*len(raw)/octets:.2f}%", "PASS20" if 5*len(raw) >= octets else "BELOW20")


if __name__ == "__main__":
    main()
