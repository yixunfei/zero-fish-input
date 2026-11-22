"""Public corpus screen for raw secret UTF-8 bytes / complete surface UTF-8 bytes."""
import hashlib
import zlib

from compression_probe import envelope_budget, pack, unpack
from verse import ROOT, Resources


def document(name):
    return (ROOT / "docs" / name).read_text(encoding="utf-8").encode()


def fixtures():
    for name in ("natural-wording-steganography.md", "clipboard-product-repair-plan.md",
                 *(f"releases/v0.7.{i}.md" for i in range(4, 8))):
        yield name, document(name)[:2048].decode("utf-8", errors="ignore").encode()
    rows = [f"第{i}次巡检：东侧入口照明正常，西侧入口照明正常；"
            f"已完成设备复核，剩余待办{(i * 7) % 13}项。" for i in range(1, 18)]
    yield "synthetic-form", "\n".join(rows).encode()


def dictionary_stream(raw, charset, dictionary):
    encoder = zlib.compressobj(9, zdict=dictionary)
    coded = encoder.compress(raw.decode().encode(charset)) + encoder.flush()
    decoder = zlib.decompressobj(zdict=dictionary)
    restored = (decoder.decompress(coded) + decoder.flush()).decode(charset).encode()
    if restored != raw or not decoder.eof:
        raise ValueError("dictionary compression round trip")
    return len(coded)


def public_training_files():
    prior = sorted(p for p in (ROOT / "docs/releases").glob("v*.md")
                   if p.stem.count(".") == 2 and p.stem[1:].replace(".", "").isdigit()
                   and tuple(map(int, p.stem[1:].split("."))) <= (0, 7, 3))
    return prior


def public_dictionaries():
    prior = public_training_files()
    return {fmt: b"\n".join(p.read_text(encoding="utf-8").encode(fmt)
                            for p in prior)[-32768:] for fmt in ("utf-8", "gb18030")}


def surface_budget(raw, compressed_size, tx, rx):
    # Three hypothetical authenticated bytes for codec and original length.
    size = envelope_budget(3 + compressed_size)
    dummy = hashlib.shake_256(b"rate-v1/" + raw).digest(size)
    surface = pack(tx, "linked", dummy)
    if unpack(rx, "linked", surface, size) != dummy:
        raise ValueError("surface round trip")
    lines = surface[:-1].split("。")
    formatted = "".join(w + ("，" if i % 2 == 0 and i != len(lines)-1 else "。") +
                        ("\n" if i % 4 == 3 and i != len(lines)-1 else "")
                        for i, w in enumerate(lines))
    if formatted.replace("，", "。").replace("\n", "") != surface:
        raise ValueError("layout round trip")
    return size, len(formatted), len(formatted.encode())


def main():
    dictionaries = public_dictionaries()
    tx, rx = Resources(), Resources()
    for name, raw in fixtures():
        sizes = [dictionary_stream(raw, fmt, d) for fmt, d in dictionaries.items()]
        plain_stream = zlib.compress(raw, 9)
        if zlib.decompress(plain_stream) != raw or len(raw) > 2048:
            raise ValueError("fixture")
        size, units, octets = surface_budget(raw, min(len(raw), len(plain_stream), *sizes), tx, rx)
        meets = 5 * len(raw) >= octets and units <= 8192 and octets <= 16384
        print(name, len(raw), len(plain_stream), *sizes, size, octets,
              f"{100 * len(raw) / octets:.2f}%", meets)


if __name__ == "__main__":
    main()
