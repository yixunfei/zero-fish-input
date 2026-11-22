"""Build and load the frozen shared resources for P2 regression experiments."""
from collections import Counter
from hashlib import sha256
import json

from binary_prototype import bounded_file
from build_p1_profile import LOCK_SHA256, json_bytes
from p1_corpus import training
from p1_profile import MODE_IDS as P1_MODES
from p2_bits import (BLOCK, COMMON_CODES, LAYOUTS, MAX_KEYS, MAX_KEY_LENGTH, MAX_RANK,
                     PREFIXES, ROW, ROW_LIMITS, SHORT, Layout, p1_tokens)
from p2_replay import build_readings, replay_terms
from p2_segments import cached_terms, segment
from rime_location_codec import encode as p1_encode
from rime_location_probe import chunks
from rime_probe_device import CONFIG_HASHES
from verse import LOCK, ROOT


FORMAT = "ZeroInput/public-themed-verse/P2-regression-v1"
DICTIONARY_LIMIT = 32768
PACKINGS = ("bare", "zlib", "dict")
STATIC_LAYOUTS = tuple(name for name in LAYOUTS if not name.startswith("key-"))
VARIANTS = {f"{name}/{packing}": (name, packing) for name in LAYOUTS for packing in PACKINGS}
VARIANTS["prefix/p1-dict"] = "prefix", "p1-dict"
MODES = {**P1_MODES, **{name: 32 + i for i, name in enumerate(VARIANTS)}}


def frequency_table(fixed):
    counts = {SHORT: Counter(), ROW: Counter()}
    for items in fixed:
        for token in items:
            if token.kind in counts:
                counts[token.kind][token.value] += 1
    return {kind: sorted(table, key=lambda code: (-table[code], code))[:COMMON_CODES]
            for kind, table in counts.items()}


def section_tokens(raw, terms, fixed, layouts, events=None):
    collection = {name: fixed for name in STATIC_LAYOUTS if name != "freq-reseg"}
    collection["freq-reseg"] = segment(raw, terms, layouts["freq-reseg"])
    if events is not None:
        selected = segment(raw, terms, layouts["key-rank"], events)
        collection.update({"key-direct": selected, "key-rank": selected})
    return collection


def native_rules():
    manifest = ROOT / "engine-rime/prebuilt/rime/prebuilt/manifest.json"
    cpp = ROOT / "tools/themed-verse/rime_rank_probe.cc"
    return {"prebuilt_manifest_lf_sha256": sha256(manifest.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
            "probe_lf_sha256": sha256(cpp.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
            "config_files": CONFIG_HASHES, "queries": MAX_KEYS, "key_chars": MAX_KEY_LENGTH,
            "candidate_depth": MAX_RANK, "reading": "zi single syllable by descending weight and spelling",
            "priority": "sentence, Han run, row term, other window; length desc, offset, key length and key",
            "runtime": "x86_64 isolated default deployed schema, no user dictionary"}


def build(p1, query=None):
    lock = LOCK.read_bytes().replace(b"\r\n", b"\n")
    if sha256(lock).hexdigest() != LOCK_SHA256:
        raise ValueError("source lock changed")
    texts = "\n".join(training())
    inputs = list(chunks(texts))
    terms = [cached_terms(p1.positions, raw) for raw in inputs]
    fixed = [p1_tokens(p1_encode(raw, words)) for raw, words in zip(inputs, terms)]
    frequencies = frequency_table(fixed)
    layouts = {name: Layout(name, frequencies) for name in LAYOUTS}
    readings = build_readings(json.loads(lock)) if query is not None else {}
    dictionaries = {name: bytearray() for name in layouts if name in STATIC_LAYOUTS or query is not None}
    for raw, words, tokens in zip(inputs, terms, fixed, strict=True):
        events = None
        if query is not None:
            events, _, _, _ = replay_terms(raw, words, readings, query, layouts["key-rank"])
        for name, items in section_tokens(raw, words, tokens, layouts, events).items():
            encoded, _ = layouts[name].encode(items)
            dictionaries[name].extend(encoded)
    manifest = {
        "format": FORMAT, "p1_sha256": p1.digest.hex(), "source_lock_lf_sha256": LOCK_SHA256,
        "training_sha256": sha256(texts.encode()).hexdigest(), "mode_ids": MODES,
        "layout": {"block": BLOCK, "token_count_bits": 12, "row_widths": [13, 22],
                   "literal_width": 6, "prefixes": PREFIXES, "frequent_rows": COMMON_CODES,
                   "dictionary_budget": DICTIONARY_LIMIT, "inflated_max": "4*S+2"},
        "frequencies": {str(kind): values for kind, values in frequencies.items()},
        "dictionaries": {name: data[-DICTIONARY_LIMIT:].hex() for name, data in dictionaries.items()},
        "native": native_rules() if query is not None else None, "readings": readings,
    }
    return json_bytes(manifest)


class ExperimentProfile:
    def __init__(self, p1, path, expected):
        data = bounded_file(path, 2 * 1024 * 1024)
        self.digest = sha256(data).digest()
        if self.digest.hex() != expected:
            raise ValueError("P2 profile digest")
        manifest = json.loads(data)
        if (manifest["format"] != FORMAT or manifest["p1_sha256"] != p1.digest.hex()
                or manifest["mode_ids"] != MODES or manifest["source_lock_lf_sha256"] != LOCK_SHA256):
            raise ValueError("P2 profile mismatch")
        self.p1 = p1
        self.frequencies = {kind: tuple(manifest["frequencies"][str(kind)]) for kind in (SHORT, ROW)}
        for kind, values in self.frequencies.items():
            if (len(values) > COMMON_CODES or len(set(values)) != len(values)
                    or any(type(code) is not int or not 0 <= code < ROW_LIMITS[kind] for code in values)):
                raise ValueError("frequency table")
        self.readings = manifest["readings"]
        self.native = manifest["native"]
        expected_keys = set(LAYOUTS if self.native else STATIC_LAYOUTS)
        self.dictionaries = {name: bytes.fromhex(value) for name, value in manifest["dictionaries"].items()}
        if (set(self.dictionaries) != expected_keys
                or any(not 1 <= len(data) <= DICTIONARY_LIMIT for data in self.dictionaries.values())):
            raise ValueError("P2 dictionaries")
        self.layouts = {name: Layout(name, self.frequencies) for name in self.dictionaries}

    def dictionary(self, name, packing):
        return self.p1.position_dict if packing == "p1-dict" else self.dictionaries[name]
