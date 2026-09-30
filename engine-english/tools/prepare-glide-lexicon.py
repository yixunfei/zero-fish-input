"""Derive public English glide spellings from the pinned CMUdict source.

Usage: python engine-english/tools/prepare-glide-lexicon.py <cmudict.dict>
The source must be fetched explicitly by the developer; this tool never downloads.
"""

from hashlib import sha256
from pathlib import Path
import re
import sys

SOURCE_SHA256 = "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22"
REVISION = "74790861f652b15e4ac49015a90074ad62a27690"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Supply the pinned local cmudict.dict source file")
    source = Path(sys.argv[1]).read_bytes()
    if sha256(source).hexdigest() != SOURCE_SHA256:
        raise SystemExit("CMUdict source hash mismatch")
    words: set[str] = set()
    for line in source.decode("utf-8").splitlines():
        token = line.partition(" ")[0].partition("(")[0]
        if 1 <= len(token) <= 32 and re.fullmatch("[a-z]+(?:'[a-z]+)?", token):
            words.add(token)
    header = (
        "# CMUdict public spellings; pronunciations and variant markers removed.\n"
        f"# Source revision: {REVISION}\n"
        "# License: glide-cmudict-LICENSE.txt (Carnegie Mellon University).\n"
        "# Alphabetical order is not a frequency estimate.\n"
    )
    output = Path(__file__).resolve().parents[1] / "src/main/resources/glide-english.txt"
    output.parent.mkdir(parents=True, exist_ok=True)
    data = (header + "\n".join(sorted(words)) + "\n").encode("ascii")
    output.write_bytes(data)
    print(f"words={len(words)} bytes={len(data)} sha256={sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
