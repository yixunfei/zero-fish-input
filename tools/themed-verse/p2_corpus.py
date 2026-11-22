"""Freeze audited public excerpts and verify the train/calibration archives offline."""
import argparse
from collections import Counter
from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
from unicodedata import normalize
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from binary_prototype import bounded_file


FORMAT = "zeroinput-p2-public-corpus-1"
GENRES = ("work-record", "free-narrative")
COUNTS = {"training": 16, "calibration": 20, "holdout": 30}
MIN_BYTES = 1024
MAX_BYTES = 2048
SHINGLE_WIDTH = 7
LONG_MATCH = 48
SEED = b"ZeroInput/themed-verse/P2/corpus/2026-11-20/"
ID = re.compile(r"[a-z0-9][a-z0-9-]{0,47}")
JSON_LIMIT = 4 * 1024 * 1024


def canonical_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def normalized_key(text):
    return "".join(c for c in normalize("NFKC", text).casefold() if c.isalnum())


def clipped(text):
    body = text.replace("\r\n", "\n").replace("\r", "\n").strip().encode("utf-8")
    raw = body[:MAX_BYTES].decode("utf-8", errors="ignore").encode()
    if len(raw) < MIN_BYTES:
        raise ValueError("excerpt too short")
    return raw, len(body)


def shingles(text, size):
    return {text[i:i+size] for i in range(len(text) - size + 1)}


@dataclass(frozen=True)
class Excerpt:
    metadata: dict
    raw: bytes
    normalized: str
    short: set
    long: set


def read_excerpt(row, folder):
    name = row["id"]
    if not ID.fullmatch(name) or row["genre"] not in GENRES:
        raise ValueError("invalid excerpt ID or genre")
    authors = sorted({normalized_key(a) for a in row["authors"]})
    if not authors or "" in authors or not row["work_group"]:
        raise ValueError(f"missing author or work attribution for {name}")
    text = bounded_file(folder / f"{name}.txt", 64 * 1024).decode("utf-8")
    raw, original_bytes = clipped(text)
    norm = normalized_key(raw.decode())
    meta = dict(row, author_groups=authors, body_bytes=original_bytes, start_byte=0,
                end_byte=len(raw), bytes=len(raw), sha256=sha256(raw).hexdigest())
    return Excerpt(meta, raw, norm, shingles(norm, SHINGLE_WIDTH), shingles(norm, LONG_MATCH))


def duplicate(left, right):
    if left.normalized == right.normalized:
        return "exact-normalized"
    if left.long & right.long:
        return "shared-48-char-run"
    shared = len(left.short & right.short)
    if 5 * shared >= len(left.short | right.short):
        return "seven-gram-jaccard-ge-0.20"
    return None


def select(index, folder):
    records = [read_excerpt(row, folder) for row in index["records"]]
    ids = {item.metadata["id"] for item in records}
    if len(ids) != len(records) or any(x.metadata["source"] not in index["sources"] for x in records):
        raise ValueError("source index IDs")
    chosen, seen_authors, seen_works, excluded = [], set(), set(), []
    for genre in GENRES:
        pool = sorted((x for x in records if x.metadata["genre"] == genre),
                      key=lambda x: (sha256(SEED + x.metadata["id"].encode()).digest(), x.metadata["id"]))
        slots = [role for role, amount in COUNTS.items() for _ in range(amount)]
        accepted = 0
        for item in pool:
            if accepted == len(slots):
                break
            row = item.metadata
            if seen_authors.intersection(row["author_groups"]) or row["work_group"] in seen_works:
                excluded.append({"id": row["id"], "reason": "author-or-work-group"})
                continue
            overlap = next(((old.metadata["id"], reason) for old in chosen
                            if (reason := duplicate(item, old))), None)
            if overlap:
                excluded.append({"id": row["id"], "reason": overlap[1], "matches": overlap[0]})
                continue
            row["split"] = slots[accepted]
            chosen.append(item)
            seen_authors.update(row["author_groups"])
            seen_works.add(row["work_group"])
            accepted += 1
        if accepted != len(slots):
            raise ValueError(f"{genre}: {accepted} independent works available, {len(slots)} needed")
    return chosen, excluded


def archive(items):
    data = BytesIO()
    with ZipFile(data, "w", compression=ZIP_DEFLATED, compresslevel=9) as file:
        for item in items:
            entry = ZipInfo(f"{item.metadata['id']}.txt")
            file.writestr(entry, item.raw, compress_type=ZIP_DEFLATED, compresslevel=9)
    return data.getvalue()


def freeze(source, output):
    index = json.loads(bounded_file(source, JSON_LIMIT))
    chosen, excluded = select(index, source.parent)
    pairs = len(chosen) * (len(chosen) - 1) // 2
    result = {"format": FORMAT, "date": "2026-11-20", "sources": index["sources"],
              "candidate_index_sha256": sha256(source.read_bytes()).hexdigest(),
              "duplicates": {"pairs_after_selection": pairs, "normalized_matches": 0,
                             "threshold": "alphanumeric NFKC casefold; run 48 or Jaccard(7) >= 0.20",
                             "excluded": excluded},
              "selection": {"sort_seed": SEED.decode(), "split_counts_per_genre": COUNTS,
                            "input_rule": "Normalize CRLF/CR to LF, strip outer whitespace, then "
                                          "keep longest complete UTF-8 scalar prefix at 2048 bytes.",
                            "author_policy": "One independent work per credited author across all splits."},
              "archives": {}, "records": [x.metadata for x in chosen]}
    output.mkdir(parents=True, exist_ok=False)
    sealed = output / "sealed"
    sealed.mkdir()
    for role in COUNTS:
        data = archive([item for item in chosen if item.metadata["split"] == role])
        target = sealed if role == "holdout" else output
        (target / f"{role}.zip").write_bytes(data)
        result["archives"][role] = {"sha256": sha256(data).hexdigest(), "bytes": len(data)}
    (output / "lock.json").write_bytes(canonical_json(result))
    return result


def read_lock(path):
    data = bounded_file(path, JSON_LIMIT)
    lock = json.loads(data)
    if lock["format"] != FORMAT:
        raise ValueError("corpus version")
    expected_counts = Counter({(genre, role): n for genre in GENRES for role, n in COUNTS.items()})
    actual = Counter((r["genre"], r["split"]) for r in lock["records"])
    if actual != expected_counts:
        raise ValueError("corpus split counts")
    seen_ids, seen_authors, seen_works = set(), set(), set()
    for row in lock["records"]:
        name, authors, work = row["id"], row["author_groups"], row["work_group"]
        if (not ID.fullmatch(name) or name in seen_ids or not authors or "" in authors
                or seen_authors.intersection(authors) or not work or work in seen_works
                or row["source"] not in lock["sources"]):
            raise ValueError("corpus identity overlap")
        seen_ids.add(name)
        seen_authors.update(authors)
        seen_works.add(work)
    return lock, sha256(data).hexdigest()


def samples(lock, folder, split):
    if split not in ("training", "calibration"):
        raise ValueError("this runner accepts training or calibration only")
    record = lock["archives"][split]
    data = bounded_file(folder / f"{split}.zip", 512 * 1024)
    if len(data) != record["bytes"] or sha256(data).hexdigest() != record["sha256"]:
        raise ValueError(f"{split} archive hash")
    rows = [r for r in lock["records"] if r["split"] == split]
    with ZipFile(BytesIO(data)) as archive_file:
        names = [f"{r['id']}.txt" for r in rows]
        if archive_file.namelist() != names:
            raise ValueError("unexpected array of archive entries")
        for row, name in zip(rows, names, strict=True):
            info = archive_file.getinfo(name)
            if not MIN_BYTES <= info.file_size == row["bytes"] <= MAX_BYTES:
                raise ValueError("excerpt size")
            with archive_file.open(info) as stream:
                raw = stream.read(MAX_BYTES + 1)
            if len(raw) != info.file_size or sha256(raw).hexdigest() != row["sha256"]:
                raise ValueError("excerpt hash")
            raw.decode("utf-8")
            yield row, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate_index", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = freeze(args.candidate_index, args.output)
    print("P2 public corpus frozen", len(result["records"]), "works",
          sha256((args.output / "lock.json").read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
