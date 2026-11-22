"""Frozen public inputs for building and evaluating the P1 profile."""
from hashlib import sha256
import json
import subprocess

from verse import ROOT


CORPUS = ROOT / "docs/themed-verse/p1-corpus.json"


def normalize(data):
    return data.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n").encode()


def extract(data, cap):
    data = normalize(data)
    if cap is None:
        return data
    # Strict original UTF-8, with at most one incomplete scalar removed at the cut.
    try:
        return data[:cap].decode("utf-8").encode()
    except UnicodeDecodeError as error:
        if error.reason != "unexpected end of data":
            raise
        return data[:error.start]


def frozen_input(record, commit, cap=None):
    path = ROOT / record["path"]
    try:
        data = extract(path.read_bytes(), cap)
    except (FileNotFoundError, UnicodeDecodeError):
        pass
    else:
        if sha256(data).hexdigest() == record["sha256"]:
            return data
    # The working tree can change after the calibration inputs are frozen.
    result = subprocess.run(["git", "cat-file", "blob", f"{commit}:{record['path']}"],
                            cwd=ROOT, capture_output=True, timeout=15, check=False)
    if result.returncode == 0:
        data = extract(result.stdout, cap)
        if sha256(data).hexdigest() == record["sha256"]:
            return data
    raise ValueError(f"frozen public source unavailable: {record['path']}")


def training():
    corpus = json.loads(CORPUS.read_bytes())
    return [frozen_input(record, corpus["commit"]).decode("utf-8")
            for record in corpus["training"]]


def samples():
    corpus = json.loads(CORPUS.read_bytes())
    for record in corpus["documents"]:
        yield record["path"].removeprefix("docs/"), frozen_input(record, corpus["commit"], 2048)
    for record in corpus["controls"]:
        data = record["utf8"].encode()
        if sha256(data).hexdigest() != record["sha256"]:
            raise ValueError("public control hash")
        yield record["name"], data
