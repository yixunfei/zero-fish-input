"""Derive a bounded public glide reading index from the reviewed Wanxiang core."""
import heapq
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime/dicts'
OUTPUT = ROOT / 'engine-rime/build/generated/glideAssets/glide-zh.tsv'


def main():
    readings = {}
    for name in ('zi', 'jichu'):
        data = False
        with (SOURCE / (name + '.dict.yaml')).open(encoding='utf-8') as source:
            for line in source:
                if line.strip() == '...':
                    data = True
                    continue
                if not data or line.startswith('#') or not line.strip():
                    continue
                fields = line.rstrip('\r\n').split('\t')
                code = fields[1]
                syllables = code.split(' ')
                if len(syllables) > 8 or any(not s or len(s) > 8 or not s.isascii() or not s.isalpha() for s in syllables):
                    continue
                weight = min(2147483647, int(fields[2]))
                if weight > 0:
                    readings[code] = max(readings.get(code, 0), weight)
    best = heapq.nsmallest(24000, readings.items(), key=lambda row: (-row[1], row[0]))
    assert len(best) == 24000
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(''.join(f'{code}\t{weight}\n' for code, weight in best), encoding='utf-8', newline='\n')
    print('Prepared 24000 deduplicated Wanxiang glide readings')


if __name__ == '__main__':
    main()
