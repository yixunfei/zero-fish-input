"""Host acceptance checks, using public messages and a disposable random key."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zlib

from binary_prototype import Prototype, ROOT, layout
from compression_probe import pack
from utilization_probe import document, fixtures


def require(value, label):
    if not value:
        raise RuntimeError(label)


def rejects(function, *args):
    try:
        function(*args)
    except (ValueError, zlib.error):
        return
    raise RuntimeError("expected rejection")


def negative_checks(tx, rx, key):
    raw = "\r\n𠀀🫠e\u0301\x00\r".encode()
    words = tx.words.encode(raw)
    require(rx.words.decode(words, len(raw)) == raw, "literal escape")
    for bad in (words[:-1], words + b"\0"):
        rejects(rx.words.decode, bad, len(raw))
    rejects(rx.words.decode, words, 2049)
    rejects(tx.words.encode, b"\xff")
    rejects(rx.decompress, "zlib", zlib.compress(b"x"*2049), 2048)
    rejects(rx.decompress, "zlib", zlib.compress(raw) + zlib.compress(b"x"), len(raw))
    rejects(rx.decompress, "words", words, len(raw)-1)
    rejects(rx.decompress, "invalid", words, len(raw))
    rejects(tx.candidates, b"a"*2049)
    rejects(tx.seal, raw, key, True)
    boundary = ("🫠" * 512).encode()
    require(rx.words.decode(tx.words.encode(boundary), 2048) == boundary, "2048-byte boundary")
    require("rmw" not in tx.candidates(raw), "lossy RMW must be excluded")
    surface, _, _, _ = tx.seal(raw, key)
    require(rx.open(surface, key) == raw, "mixed Unicode")
    for changed in (surface[:-1], surface.replace("，", "。", 1), surface.replace("\n", "\r\n", 1),
                    surface + "。", "山"*8193, "〇"+surface[1:]):
        rejects(rx.open, changed, key)
    rejects(rx.open, surface, bytes(b ^ 1 for b in key))

    # All codebook indices are legal. Fail at the metadata, body or final tag instead.
    packet = b"\x00" + len(raw).to_bytes(2, "big") + raw
    cipher = tx.crypt("seal", key, packet)
    for where in (0, 44, 82, len(cipher)-1):
        corrupt = bytearray(cipher)
        corrupt[where] ^= 1
        carrier = layout(pack(tx.verse, "linked", corrupt)[:-1].split("。"))
        rejects(rx.open, carrier, key)
    old = rx.profile
    try:
        rx.profile = bytes(b ^ 1 for b in old)
        rejects(rx.crypt, "open", key, cipher)
    finally:
        rx.profile = old
    # Even authenticated but invalid inner descriptors/streams must fail closed.
    for malformed in (b"\xff\x00\x01x", b"\x01\x00\x01"+zlib.compress(b"xx")):
        cipher = tx.crypt("seal", key, malformed)
        rejects(rx.open, layout(pack(tx.verse, "linked", cipher)[:-1].split("。")), key)


def cli_check(key, raw):
    with tempfile.TemporaryDirectory() as name:
        root = Path(name)
        k, src, carrier, out = [root / n for n in ("fixture.key", "src", "verse", "restored")]
        k.write_bytes(key)
        src.write_bytes(raw)
        script = Path(__file__).with_name("binary_prototype.py")
        cmd = [sys.executable, "-B", script]
        subprocess.run([*cmd, "seal", k, src, carrier], check=True, capture_output=True)
        subprocess.run([*cmd, "open", k, carrier, out], check=True, capture_output=True)
        require(out.read_bytes() == raw, "fresh processes")
        bad = root / "bad"
        carrier.write_bytes(carrier.read_bytes()[:-3])
        result = subprocess.run([*cmd, "open", k, carrier, bad], capture_output=True)
        require(result.returncode and not bad.exists(), "no partial output")


def main():
    tx, rx = Prototype(), Prototype()
    key = os.urandom(32)
    negative_checks(tx, rx, key)
    cli_check(key, "主机样本\r\n🫠".encode())
    vector = json.loads((ROOT / "docs/themed-verse/binary-vector.json").read_text(encoding="utf-8"))
    actual = rx.open(vector["surface"], bytes.fromhex(vector["public_key_hex"]))
    require(rx.profile.hex() == vector["profile"], "known profile")
    require(actual == vector["original_utf8"].encode("utf-8"), "known original bytes")
    require(hashlib.sha256(actual).hexdigest() == vector["original_sha256"], "known vector")
    require(tx.words.encode(actual).hex() == vector["words_hex"], "known word stream")
    print("PASS literal, bounds, tails, AEAD, canonical surface, new-process and fixed-vector checks")
    samples = list(fixtures())
    for source in ("adr/0002-private-clipboard.md", "audits/2026-11-17-code-audit.md",
                   "stego-themed-verse-handoff.md"):
        samples.append((source, document(source)[:2048].decode("utf-8", errors="ignore").encode()))
    for name, raw in samples:
        try:
            surface, mode, length, streams = tx.seal(raw, key)
        except ValueError as error:
            if str(error) not in ("insufficient carrier capacity", "insufficient frame capacity"):
                raise
            print(name, "capacity rejection")
            continue
        require(rx.open(surface, key) == raw, "fresh receiver mismatch")
        size = len(surface.encode())
        print(name, len(raw), streams.get("words"), mode, length, size,
              f"{100*len(raw)/size:.2f}%", "PASS20" if size <= 5*len(raw) else "BELOW20")


if __name__ == "__main__":
    main()
