"""Cross-check the authenticated public body packets with the actual Kotlin Debug implementation."""
import argparse
from collections import Counter
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import sys
import tomllib

sys.path.insert(0, str(Path(__file__).resolve().parent))
from scene_roundtrip import MODEL, ROOT, PROFILE_DIGESTS, SPARSE_DIGESTS, bounded_file, crypt, load_routes, locked_calibration, reject, write_json
from scene_word_model import decode_body, encode_body, load_model
from essay_interop import verify_essay


def kotlin_classpath():
    core = ROOT / "ime-core/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
    version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    module = cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version
    jars = list(module.glob(f"*/kotlin-stdlib-{version}.jar"))
    if not core.is_file() or len(jars) != 1:
        raise ValueError("build :ime-core:bundleLibRuntimeToJarDebug with the project Gradle wrapper first")
    return os.pathsep.join(map(str, (core, jars[0])))


def next_field(pipe, maximum=2048):
    prefix = pipe.read(2)
    size = int.from_bytes(prefix, "big")
    if len(prefix) != 2 or not 1 <= size <= maximum:
        raise ValueError("invalid JVM result size")
    data = pipe.read(size)
    if len(data) != size:
        raise ValueError("truncated JVM result")
    return data


def check(corpus, source, run, essay=False):
    routes, profiles, _ = load_routes(source, essay)
    corpus_sha, entries = locked_calibration(corpus)
    report = json.loads((run / "report.json").read_bytes())
    if report["corpus_sha256"] != corpus_sha or report["profiles"] != {k: v.hex() for k, v in profiles.items()}:
        raise ValueError("unexpected calibration report")
    cases, frozen, counts = [], [], Counter()
    for name, route in routes.items():
        for index, (meta, raw) in enumerate(entries):
            row = report["samples"][index]
            if row["id"] != meta["id"]:
                raise ValueError("sample identity mismatch")
            path = run / "surfaces" / name / f"{index:03}.txt"
            if row["routes"][name]["result"] in ("carrier-capacity", "body-capacity") and not path.exists():
                continue
            data = bounded_file(path, 16384)
            text = data.decode("utf-8")
            envelope = route.recover(text)
            if route.render(envelope) != text:
                raise ValueError("unexpected noncanonical fixture")
            packet = crypt("open", profiles[name], envelope)
            if essay and not counts[name]:
                old = (SPARSE_DIGESTS if source else PROFILE_DIGESTS)[name]
                reject(lambda: crypt("open", bytes.fromhex(old), envelope), "previous body profile")
            if name.endswith("-frozen"):
                frozen.append((route.width, data, envelope))
            cases.append((raw, packet))
            counts[name] += 1
    if counts["5-free" if source else "5-frozen"] != len(entries):
        raise ValueError("missing guaranteed calibration carriers")

    real_bytes = sum(len(raw) for raw, _ in cases)
    word, codecs = load_model(MODEL.read_bytes())
    essay_result = None
    if essay:
        essay_cases = [(raw, p) for raw, p in cases if p[0] in (8, 9)]
        essay_result = verify_essay(essay_cases, codecs[6].tables, MODEL)
        cases = [(raw, p) for raw, p in cases if p[0] not in (8, 9)]
    kotlin_real = len(cases)
    for size in (1, 2045, 2046, 2048):
        marker = "𠀋🙂e\u0301\r\n".encode()
        prefix = max(0, size - len(marker))
        raw = b"x" if size == 1 else "的".encode() * (prefix // 3) + b"x" * (prefix % 3) + marker
        cases.append((raw, encode_body(raw, word, codecs)))
    data = len(cases).to_bytes(2, "big") + b"".join(len(p).to_bytes(2, "big") + p for _, p in cases)
    data += len(frozen).to_bytes(2, "big") + b"".join(
        bytes([width]) + len(text).to_bytes(2, "big") + text for width, text, _ in frozen)
    assets = ROOT / "app/build/generated/verifiedWordingAssets/themed-verse"
    command = ["java", "-cp", kotlin_classpath(), str(Path(__file__).with_name("SceneBodyInterop.java")),
               str(MODEL), str(assets)]
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE, check=True, timeout=120)
    pipe = BytesIO(result.stdout)
    modes = Counter()
    for raw, packet in cases:
        original, regenerated = next_field(pipe), next_field(pipe)
        if raw != original or decode_body(regenerated, word, codecs) != raw or regenerated != packet:
            raise ValueError("Kotlin/host body mismatch")
        modes[packet[0]] += 1
    for _, _, envelope in frozen:
        if next_field(pipe, 2548) != envelope:
            raise ValueError("Kotlin frozen surface envelope mismatch")
    if pipe.read(1) or (not essay and set(modes) != {5, 6, 7}):
        raise ValueError("incomplete Kotlin body verification")
    result = {"routes": counts, "boundary_cases": 4, "modes": modes, "kotlin_real_packets": kotlin_real,
              "exact_kotlin_frozen_surfaces": len(frozen), "exact_original_bytes": sum(len(raw) for raw, _ in cases)}
    if essay:
        result["independent_essay_decoder"] = essay_result
        result["all_carrier_original_bytes"] = real_bytes
        print(f"Independent JVM Essay decoder: {essay_result}")
    write_json(run / ("jvm-body.json" if essay else "kotlin-body.json"), result)
    print(f"Kotlin decoded original bytes and reproduced host packets for {kotlin_real} carriers "
          f"and four length controls; {len(frozen)} frozen surfaces recovered with Kotlin.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("run", type=Path)
    parser.add_argument("--sparse-source", type=Path)
    parser.add_argument("--essay-body", action="store_true")
    args = parser.parse_args()
    check(args.corpus, args.sparse_source, args.run, args.essay_body)
