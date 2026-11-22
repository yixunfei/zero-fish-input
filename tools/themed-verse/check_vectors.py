"""Receiver-only replay of the published public fixtures, from a fresh process."""
import hashlib
import json

from verse import MODES, ROOT, Resources, decode


def main():
    rows = json.loads((ROOT / "docs/themed-verse/vectors.json").read_text(encoding="utf-8"))
    expected = {(m, s) for m in MODES for s in (162, 226, 290)}
    resources = Resources()
    total = 0
    for row in rows:
        mode, size = row["mode"], row["size"]
        expected.remove((mode, size))
        data = decode(resources, mode, row["surface"], size)
        fixture = hashlib.shake_256(f"ZeroInput/public-verse/v1/{size}".encode()).digest(size)
        if data != fixture or data.hex() != row["payload"]:
            raise ValueError("vector mismatch")
        total += len(data)
    if expected:
        raise ValueError("missing vectors")
    print(f"PASS {len(rows)} public vectors, {total} bytes")


if __name__ == "__main__":
    main()
