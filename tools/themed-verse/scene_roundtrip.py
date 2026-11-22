"""Round-trip locked public bodies through authenticated verse carriers in separate processes."""
import argparse
from bisect import bisect_right
from collections import Counter
from hashlib import sha256
import json
from itertools import chain
import os
from pathlib import Path
import subprocess
import sys

# The prepared embedded Windows Python does not add the script directory.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from association_probe import ASSOCIATIONS, CORPUS_LOCK, envelope_bytes
from binary_prototype import bounded_file, helper
from essay_body import ESSAY_SHA, NORMALIZATION, build_models as build_essay_models
from p2_corpus import read_lock, samples
from prose_ngram import CARRIER_BYTES, CARRIER_UNITS, CapacityError
from scene_word_model import MODEL_SHA, decode_body, encode_body, load_model
from sparse_verse import TANG_REV, TANG_SHA, SparseVerse, attributed_pairs, sparse_routes
from surface_lexicon_probe import locked_faces
from verse import ROOT, Resources
from verse_scene_probe import FrozenVerse, SCENES, SceneVerse, banks
from word_context_probe import build_codecs


MODEL = ROOT / "tools/themed-verse/android-test-profile/word-context-v1.bin"
CRYPTO = ROOT / ("tools/stego-vectors/target/debug/scene-envelope" + (".exe" if os.name == "nt" else ""))
KEY = bytes(range(32))  # Public fixture only.
SOURCE_LOCK_SHA = "0f04754f84bedce500e965c3f895a444de13136a4d4e3bea68b81f12a2372767"
PROFILE_DIGESTS = {
    "5-frozen": "eba48c56c84b18e23c35cd9df97acc64bc8f84487f5c8091c8eb487d714461fc",
    "5-scene": "e31529b842d449fb2bef76dcf10650ceb6b93aaaa3ac3276bf1f064cfec5d9da",
    "5-anchor": "8b9ee6634ae7cf8744d034b7ea72d024dff21972ac72828df88b634556dd6dc6",
    "7-frozen": "d2bb4bc271d4972eaf4fb26bd84ba7ae0df9b3361be24dd692efb765e9d18e1c",
    "7-scene": "5b9d510d16059e46d3c72e871f8a634a4364e5f24411ccda2484c47c47b5e9aa",
    "7-anchor": "22bca52a95eedd461d86b5777d0170bf22ffe50fd2c03e51ed1b255f0367f955",
}
SPARSE_DIGESTS = {
    "5-free": "5553402507341bda5570c8f950daf0f2814f6290a6c17a58e32aea1ad7f7a0a4",
    "5-sparse-scene": "3266f27caa20f1ee63f0fd9f8168a37a85a1c38fcdb1791e23c12accb209846e",
    "5-sparse-couplet": "df764c9db962d3222d86c80ba59cc342bb62c628440f6d82d5591bff08295116",
    "7-free": "b091469109553edc13fd2d9ec9289d19e2f6cea417139aaaa730956a6b04bc73",
    "7-sparse-scene": "01a515525686b2fe0d862b425ce5c55c9436b532d5ac8b9c110ae80c24444b9e",
    "7-sparse-couplet": "327ec31d3a4f4f18cdd81ccf93eac888d3af5d9268a1b75950083f9c43490796",
}


def hash_parts(parts):
    digest = sha256()
    for part in parts:
        data = part.encode("utf-8") if isinstance(part, str) else part
        digest.update(len(data).to_bytes(4, "big"))
        digest.update(data)
    return digest.digest()


def route_digest(name, route):
    parts = ["ZeroInput/host-scene-calibration/v1", name, bytes.fromhex(MODEL_SHA)]
    if isinstance(route, SparseVerse):
        return hash_parts(chain(parts, route.profile_parts()))
    if isinstance(route, FrozenVerse):
        parts.extend((route.pools.rule, *route.pools.to_record()["families"]))
        parts.extend(("opening", *route.opening))
        for key, lines in sorted(route.pools.pools.items()):
            parts.extend((key, *lines))
    else:
        for index, row in enumerate(route.rows):
            parts.extend((str(index), *row))
    return hash_parts(parts)


def bound_profiles(digests, essay):
    if not essay:
        return digests
    return {name: hash_parts(("host-essay-lexical-copy/v1", bytes.fromhex(digest),
                              bytes.fromhex(ESSAY_SHA), bytes.fromhex(NORMALIZATION["TSCharacters.txt"]))).hex()
            for name, digest in digests.items()}


def load_routes(source=None, essay=False):
    lock = (ROOT / "tools/dictionaries/sources.lock.json").read_bytes().replace(b"\r\n", b"\n")
    if sha256(lock).hexdigest() != SOURCE_LOCK_SHA:
        raise ValueError("public dictionary source lock mismatch")
    _, tables = locked_faces()  # Each invocation verifies all 16 public tables.
    wanted = {term for words in SCENES.values() for term in words.split()}
    lexicon = {word for word in wanted if any(word in table for table in tables.values())}
    del tables
    resources = Resources()  # Independently checks the locked poetry table.
    if source:
        works = attributed_pairs(source)
        routes = sparse_routes(resources, lexicon, works)
        expected = SPARSE_DIGESTS
    else:
        works, routes = {}, {}
        for width, lines in ((5, resources.free), (7, resources.seven)):
            scene, anchor, _ = banks(lines, lexicon)
            routes[f"{width}-frozen"] = FrozenVerse(width, lines)
            routes[f"{width}-scene"] = SceneVerse(width, scene)
            routes[f"{width}-anchor"] = SceneVerse(width, anchor)
        expected = PROFILE_DIGESTS
    profiles = {name: route_digest(name, route) for name, route in routes.items()}
    if {name: digest.hex() for name, digest in profiles.items()} != expected:
        raise ValueError("public scene profile mismatch")
    if essay:
        profiles = {name: bytes.fromhex(value) for name, value in bound_profiles(expected, True).items()}
    return routes, profiles, works


def crypt(operation, digest, data, key=KEY):
    return helper([CRYPTO, operation], key + digest + data)


def open_surface(route, digest, surface, word, codecs, key=KEY):
    envelope = route.recover(surface)
    if route.render(envelope) != surface:
        raise ValueError("noncanonical surface")
    return decode_body(crypt("open", digest, envelope, key), word, codecs)


def deliver(route, digest, surface, word, codecs, target, key=KEY):
    raw = open_surface(route, digest, surface, word, codecs, key)
    with target.open("xb") as output:
        output.write(raw)


def write_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, indent=2, sort_keys=True)
        output.write("\n")


def locked_calibration(folder):
    lock, digest = read_lock(folder / "lock.json")
    if digest != sha256(CORPUS_LOCK.read_bytes().replace(b"\r\n", b"\n")).hexdigest():
        raise ValueError("P2 corpus lock mismatch")
    result = list(samples(lock, folder, "calibration"))
    if len(result) != 40:
        raise ValueError("expected 40 calibration samples")
    return digest, result


def reference_model(folder):
    lock, _ = read_lock(folder / "lock.json")
    training = [raw.decode("utf-8") for _, raw in samples(lock, folder, "training")]
    word, codecs, _ = build_codecs(training, ASSOCIATIONS.read_bytes().replace(b"\r\n", b"\n"))
    return word, {6: codecs["recent_only"], 7: codecs["bigram_recent"]}


def compare_models(raw, word, codecs, reference_word, reference_codecs):
    tokens, expected = word.tokenize(raw), reference_word.tokenize(raw)
    if tokens != expected or word.encode_tokens(tokens) != reference_word.encode_tokens(expected):
        raise ValueError("reloaded Huffman model differs from training reference")
    for mode in (6, 7):
        stream = reference_codecs[mode].encode(expected)[0]
        if codecs[mode].encode(tokens)[0] != stream or codecs[mode].decode(stream, len(raw)) != raw:
            raise ValueError("reloaded rANS model differs from training reference")


def select_packet(raw, word, codecs, reference_word, reference_codecs, essay_models, record):
    best = None
    try:
        best = encode_body(raw, word, codecs)
    except CapacityError:
        if not essay_models:
            raise
    if best is not None and best != encode_body(raw, reference_word, reference_codecs):
        raise ValueError("reloaded model selection differs from training reference")
    if essay_models:
        metrics = {"baseline_stream_bytes": len(best) - 3 if best else None, "candidates": {}}
        record["body_comparison"] = metrics
        for mode, model in essay_models.items():
            stream, details = model.encode(raw)
            metrics["candidates"][mode] = {**details, "stream_bytes": len(stream)}
            if len(stream) > 2045:
                continue
            if model.decode(stream, len(raw)) != raw:
                raise ValueError("essay candidate byte mismatch")
            if best is None or envelope_bytes(len(stream)) < envelope_bytes(len(best) - 3):
                best = bytes([mode]) + len(raw).to_bytes(2, "big") + stream
    if best is None:
        raise CapacityError("body capacity")
    return best


def send(corpus, output, source, essay=False):
    digest, entries = locked_calibration(corpus)
    word, codecs = load_model(MODEL.read_bytes())
    reference_word, reference_codecs = reference_model(corpus)
    routes, profiles, works = load_routes(source, essay)
    essay_models = build_essay_models(codecs[6].tables) if essay else {}
    decoders = codecs | essay_models
    if works:
        write_json(output / "couplet-sources.json", {
            "source": f"https://github.com/chinese-poetry/chinese-poetry/blob/{TANG_REV}/全唐诗/poet.tang.20000.json",
            "source_sha256": TANG_SHA, "fields": ["work_id", "author", "title", "one_based_paragraph"],
            "license": "Tang-corpus-LICENSE.txt",
            "pairs": {str(width): {"，".join(pair): citations for pair, citations in records.items()}
                      for width, records in works.items()}})
        license_file = Path(__file__).with_name("Tang-corpus-LICENSE.txt")
        with (output / license_file.name).open("xb") as out:
            out.write(license_file.read_bytes())
    surfaces = output / "surfaces"
    surfaces.mkdir()
    for name in routes:
        (surfaces / name).mkdir()
    records = []
    for index, (row, raw) in enumerate(entries):
        compare_models(raw, word, codecs, reference_word, reference_codecs)
        record = {"index": index, "id": row["id"], "genre": row["genre"],
                  "bytes": len(raw), "sha256": sha256(raw).hexdigest(), "routes": {}}
        records.append(record)
        try:
            packet = select_packet(raw, word, codecs, reference_word, reference_codecs, essay_models, record)
        except CapacityError:
            record["routes"] = {name: {"status": "body-capacity"} for name in routes}
            continue
        if decode_body(packet, word, decoders) != raw:
            raise ValueError("sender body round trip")
        record.update(body_mode=packet[0], body_stream_bytes=len(packet) - 3)
        for name, route in routes.items():
            envelope = crypt("seal", profiles[name], packet)
            if len(envelope) != envelope_bytes(len(packet) - 3):
                raise ValueError("unexpected wrapped-envelope size")
            measurement = {"envelope_bytes": len(envelope)}
            record["routes"][name] = measurement
            if isinstance(route, SparseVerse):
                capacity = route.guaranteed_in(5 * len(raw))
                allowed = bisect_right(range(1, 2046), capacity, key=envelope_bytes)
                measurement.update(target_guaranteed_envelope_bytes=capacity,
                                   target_max_stream_bytes=allowed,
                                   stream_savings_needed=max(0, len(packet) - 3 - allowed) if allowed else None)
            try:
                surface = route.render(envelope)
            except CapacityError:
                if len(envelope) <= route.guaranteed:
                    raise ValueError("carrier rejected a guaranteed envelope")
                measurement["status"] = "carrier-capacity"
                continue
            data = surface.encode("utf-8")
            units = len(surface.encode("utf-16-le")) // 2
            if len(data) > CARRIER_BYTES or units > CARRIER_UNITS:
                raise ValueError("surface exceeded a hard bound")
            (surfaces / name / f"{index:03}.txt").write_bytes(data)
            measurement.update(status="sent", carrier_bytes=len(data), carrier_units=units,
                               lines=surface.count("。") + surface.count("，"), carrier_sha256=sha256(data).hexdigest())
            if measurement.get("stream_savings_needed") == 0 and len(data) > 5 * len(raw):
                raise ValueError("sparse verse exceeded guaranteed target")
    write_json(output / "sender.json", {"corpus_sha256": digest, "model_sha256": MODEL_SHA,
                                        "guaranteed_envelope_bytes": {k: r.guaranteed for k, r in routes.items()},
                                        "profiles": {k: v.hex() for k, v in profiles.items()}, "samples": records})
    print(f"sender: {len(records)} locked bodies; three reloaded modes match the training reference", flush=True)


def reject(action, reason):
    try:
        action()
    except (ValueError, CapacityError, FileNotFoundError):
        return
    raise ValueError(f"negative case accepted: {reason}")


def negative_cases(route, digest, surface, word, codecs, target):
    def reject_delivery(text, reason, profile=digest, key=KEY):
        reject(lambda: deliver(route, profile, text, word, codecs, target, key), reason)
        if target.exists():
            raise ValueError(f"negative case wrote output: {reason}")

    envelope = route.recover(surface)
    reject_delivery(surface[:-1], "truncation")
    reject_delivery(surface, "wrong key", key=bytes(reversed(KEY)))
    reject_delivery(surface, "profile mismatch", profile=bytes([digest[0] ^ 1]) + digest[1:])
    for offset, reason in ((0, "header"), (44, "metadata"), (172, "body"), (-1, "outer tag")):
        changed = bytearray(envelope)
        changed[offset] ^= 1
        changed_surface = route.render(bytes(changed))
        reject_delivery(changed_surface, reason)


def check_body_controls(fixture, word, codecs):
    tokens = word.tokenize(fixture)
    streams = {5: word.encode_tokens(tokens), **{mode: codec.encode(tokens)[0] for mode, codec in codecs.items()}}
    for mode, stream in streams.items():
        packet = bytes([mode]) + len(fixture).to_bytes(2, "big") + stream
        if decode_body(packet, word, codecs) != fixture:
            raise ValueError("mixed Unicode body fixture")
        reject(lambda: decode_body(packet[:-1], word, codecs), "truncated body")
        reject(lambda: decode_body(packet + b"\0", word, codecs), "trailing body")
        reject(lambda: decode_body(packet[:1] + (len(fixture) + 1).to_bytes(2, "big") + packet[3:], word, codecs),
               "incorrect original length")
    reject(lambda: decode_body(b"\xff" + len(fixture).to_bytes(2, "big") + b"x", word, codecs), "body mode")


def receive_file(carrier, target, route, digest, word, codecs):
    try:
        surface = bounded_file(carrier, CARRIER_BYTES).decode("utf-8")
        deliver(route, digest, surface, word, codecs, target)
    except ValueError:
        return "receive-rejected"
    return "recovered"


def receive(surfaces, output, source, essay=False):
    word, codecs = load_model(MODEL.read_bytes())
    reject(lambda: load_model(MODEL.read_bytes() + b"\0"), "corrupt model")
    reject(lambda: load_model((surfaces / "missing-model-for-scene-test.bin").read_bytes()), "missing model")
    fixture = "公开夹具𠀋🙂e\u0301\r\n结束\r".encode("utf-8")
    check_body_controls(fixture, word, codecs)
    packet = encode_body(fixture, word, codecs)
    routes, profiles, _ = load_routes(source, essay)
    if essay:
        codecs.update(build_essay_models(codecs[6].tables))
    output.mkdir()
    results = {}
    for name in routes:
        (output / name).mkdir()
        results[name] = {}
        fixture_surface = routes[name].render(crypt("seal", profiles[name], packet))
        if open_surface(routes[name], profiles[name], fixture_surface, word, codecs) != fixture:
            raise ValueError("independent Unicode surface fixture")
        negative_cases(routes[name], profiles[name], fixture_surface, word, codecs, output / name / "negative-control.bin")
        for carrier in sorted((surfaces / name).glob("*.txt")):
            results[name][carrier.stem] = receive_file(
                carrier, output / name / f"{carrier.stem}.bin", routes[name], profiles[name], word, codecs)
        print(f"receiver: {name}, {len(results[name])} surfaces, Unicode and negative cases checked", flush=True)
    write_json(output / "receiver.json", {"model_sha256": MODEL_SHA,
                                          "profiles": {k: v.hex() for k, v in profiles.items()},
                                          "results": results})


def assess(corpus, output, expected):
    corpus_hash, entries = locked_calibration(corpus)
    sender = json.loads((output / "sender.json").read_bytes())
    receiver = json.loads((output / "decoded/receiver.json").read_bytes())
    if (sender["corpus_sha256"] != corpus_hash or sender["profiles"] != expected or receiver["profiles"] != expected
            or sender["model_sha256"] != MODEL_SHA or receiver["model_sha256"] != MODEL_SHA):
        raise ValueError("sender/receiver resources differ")
    summary = {name: {"all": Counter(), "work-record": Counter(), "free-narrative": Counter()} for name in expected}
    details = []
    for index, ((meta, raw), sent) in enumerate(zip(entries, sender["samples"], strict=True)):
        if sent["index"] != index or sent["id"] != meta["id"] or sent["sha256"] != sha256(raw).hexdigest():
            raise ValueError("sender sample identity mismatch")
        item = {"id": meta["id"], "genre": meta["genre"], "bytes": len(raw), "routes": {}}
        if "body_comparison" in sent:
            item.update(body_comparison=sent["body_comparison"], body_mode=sent.get("body_mode"),
                        selected_stream_bytes=sent.get("body_stream_bytes"))
        for name in expected:
            measurement = sent["routes"][name]
            status = measurement["status"]
            restored = output / "decoded" / name / f"{index:03}.bin"
            if status == "sent":
                data = bounded_file(output / "surfaces" / name / f"{index:03}.txt", CARRIER_BYTES)
                units = len(data.decode("utf-8").encode("utf-16-le")) // 2
                if (len(data) != measurement["carrier_bytes"] or units != measurement["carrier_units"]
                        or units > CARRIER_UNITS or sha256(data).hexdigest() != measurement["carrier_sha256"]):
                    raise ValueError("sender lengths differ from the complete carrier")
                receive_status = receiver["results"][name].get(f"{index:03}")
                if receive_status != "recovered" or not restored.is_file():
                    status = "receive-rejected"
                elif restored.read_bytes() != raw:
                    status = "byte-mismatch"
                else:
                    status = "exact"
            elif restored.exists():
                raise ValueError("capacity rejection wrote a result")
            elif status not in ("carrier-capacity", "body-capacity"):
                raise ValueError("unrecognized sender outcome")
            passed = status == "exact" and measurement["carrier_bytes"] <= 5 * len(raw)
            for bucket in (summary[name]["all"], summary[name][meta["genre"]]):
                bucket["attempts"] += 1
                bucket["input_bytes"] += len(raw)
                bucket[status] += 1
                if status == "exact":
                    bucket["exact_bytes"] += len(raw)
                    bucket["pass_20"] += int(passed)
                    bucket["below_20"] += int(not passed)
                    bucket["fits_draft"] += int(measurement["carrier_units"] <= 512 and measurement["carrier_bytes"] <= 1536)
            item["routes"][name] = {**measurement, "result": status, "pass_20": passed}
        details.append(item)
    result = {"corpus_sha256": corpus_hash, "model_sha256": MODEL_SHA,
              "guaranteed_envelope_bytes": sender["guaranteed_envelope_bytes"],
              "profiles": sender["profiles"], "summary": summary, "samples": details}
    write_json(output / "report.json", result)
    for name in expected:
        row = summary[name]["all"]
        print(f"{name}: exact {row['exact']}/{row['attempts']} ({row['exact_bytes']}/{row['input_bytes']} B), "
              f"20% {row['pass_20']}/{row['attempts']}, carrier capacity {row['carrier-capacity']}, "
              f"body capacity {row['body-capacity']}, "
              f"below 20% {row['below_20']}, receive errors {row['receive-rejected']}, mismatches {row['byte-mismatch']}")
    if any(summary[name]["all"][error] for name in expected for error in ("receive-rejected", "byte-mismatch")):
        raise ValueError("unexpected receiver failure")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("run", "send", "receive"):
        child = sub.add_parser(name)
        child.add_argument("--sparse-source", type=Path, help="Use sparse verse routes with the pinned Tang JSON file")
        child.add_argument("--essay-body", action="store_true", help="Compare independently bound Rime Essay body modes")
        child.add_argument("surfaces" if name == "receive" else "corpus", type=Path)
        child.add_argument("output", type=Path)
    args = parser.parse_args()
    source = args.sparse_source
    if args.command == "run":
        args.output.mkdir(parents=True, exist_ok=False)
        script = str(Path(__file__).resolve())
        options = ["--sparse-source", str(source)] if source else []
        if args.essay_body:
            options.append("--essay-body")
        subprocess.run([sys.executable, "-X", "utf8", "-B", script, "send", *options, str(args.corpus), str(args.output)], check=True)
        subprocess.run([sys.executable, "-X", "utf8", "-B", script, "receive",
                        *options, str(args.output / "surfaces"), str(args.output / "decoded")], check=True)
        assess(args.corpus, args.output, bound_profiles(SPARSE_DIGESTS if source else PROFILE_DIGESTS, args.essay_body))
    elif args.command == "send":
        send(args.corpus, args.output, source, args.essay_body)
    else:
        receive(args.surfaces, args.output, source, args.essay_body)


if __name__ == "__main__":
    main()
