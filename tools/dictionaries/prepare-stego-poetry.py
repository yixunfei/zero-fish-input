"""Compile ordered public verse pairs from the reviewed Wanxiang shici table."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / "engine-rime/build/generated/publicDictionaryAssets/rime/dicts/shici.dict.yaml"
OUTPUT = ROOT / "app/build/generated/stegoPoetry/dev/zeroinput/ime/textstego/DictionaryVerses.java"

# The source sorts individual lines by reading and does not record their order in the poem.
PAIRS = """春眠不觉晓 处处闻啼鸟
海上生明月 天涯共此时
白日依山尽 黄河入海流
明月松间照 清泉石上流
空山新雨后 天气晚来秋
潮平两岸阔 风正一帆悬
海日生残夜 江春入旧年
星垂平野阔 月涌大江流
山随平野尽 江入大荒流
大漠孤烟直 长河落日圆
迟日江山丽 春风花草香
少壮不努力 老大徒伤悲
川原杳何极 日暮飞鸟还"""


def main():
    pairs = [line.split() for line in PAIRS.splitlines()]
    wanted = {word for pair in pairs for word in pair}
    available = set()
    with TABLE.open(encoding="utf-8") as stream:
        for line in stream:
            word, delimiter, _ = line.partition("\t")
            if delimiter and word in wanted:
                available.add(word)
    verses = [f'"{a}，{b}。"' for a, b in pairs if a in available and b in available]
    if len(verses) < 2:
        raise ValueError("Review verse pairs against the current poetry dictionary")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("package dev.zeroinput.ime.textstego;\n\nfinal class DictionaryVerses {\n"
                      "    static final String[] COUPLETS = {\n        " + ",\n        ".join(verses)
                      + "\n    };\n}\n", encoding="utf-8")


if __name__ == "__main__":
    main()
